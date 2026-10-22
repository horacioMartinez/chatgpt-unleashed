package com.horacio.unleashed;

import static com.horacio.unleashed.DiagnosticEvent.*;
import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class DiagnosticsTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void invalidStoredEnumsAreSkipped() throws Exception {
    File directory = temporary.newFolder();
    DiagnosticStore store = new DiagnosticStore(directory);
    Files.write(
        new File(directory, "events.jsonl").toPath(),
        "{\"area\":\"INVALID\",\"outcome\":\"ERROR\",\"code\":\"IO_ERROR\"}\n"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(0, store.read(false, 0).length());
    store.append(actionResult(7));
    assertEquals(1, store.read(true, 0).length());
  }

  private DiagnosticEvent actionResult(int code) throws Exception {
    return DiagnosticEvent.completed(
        Area.TOOL,
        Operation.ACTION,
        newId(),
        newId(),
        20,
        new JSONObject().put("exitCode", code).put("stderr", "private-content-do-not-store"),
        null);
  }

  private JSONObject request(String name, JSONObject args) throws Exception {
    return new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", "private-client-id-do-not-store")
        .put("method", "tools/call")
        .put("params", new JSONObject().put("name", name).put("arguments", args));
  }

  @Test
  public void historySurvivesNewStoreInstanceAndFiltersFailures() throws Exception {
    File directory = temporary.newFolder();
    DiagnosticStore first = new DiagnosticStore(directory);
    first.append(actionResult(0));
    first.append(actionResult(7));
    DiagnosticStore reopened = new DiagnosticStore(directory);
    assertEquals(2, reopened.read(false, 0).length());
    JSONArray failures = reopened.read(true, 0);
    assertEquals(1, failures.length());
    assertEquals(7, failures.getJSONObject(0).getInt("exitCode"));
    assertFalse(failures.toString().contains("private-content-do-not-store"));
  }

  @Test
  public void rotationBoundsDiskUsageAndKeepsNewestEntries() throws Exception {
    File directory = temporary.newFolder();
    DiagnosticStore store = new DiagnosticStore(directory, 1024);
    for (int i = 1; i <= 30; i++) store.append(actionResult(i));
    long bytes = 0;
    for (File file : directory.listFiles()) {
      assertTrue(file.length() <= 1024);
      bytes += file.length();
    }
    assertTrue(bytes <= 2048);
    JSONArray entries = store.read(false, 0);
    assertTrue(entries.length() < 30);
    assertEquals(30, entries.getJSONObject(entries.length() - 1).getInt("exitCode"));
    assertEquals(1, store.read(false, 1).length());
  }

  @Test
  public void partialCrashWriteDoesNotDestroyValidHistoryOrNextEntry() throws Exception {
    File directory = temporary.newFolder();
    DiagnosticStore store = new DiagnosticStore(directory);
    store.append(actionResult(7));
    Files.write(
        new File(directory, "events.jsonl").toPath(),
        "{\"incomplete\":".getBytes(),
        StandardOpenOption.APPEND);
    assertEquals(1, store.read(false, 0).length());
    new DiagnosticStore(directory).append(actionResult(9));
    JSONArray events = store.read(false, 0);
    assertEquals(2, events.length());
    assertEquals(9, events.getJSONObject(1).getInt("exitCode"));
  }

  @Test
  public void clearRemovesBothRotatedFilesAndAllowsNewEvents() throws Exception {
    File directory = temporary.newFolder();
    DiagnosticStore store = new DiagnosticStore(directory, 1024);
    for (int i = 0; i < 10; i++) store.append(actionResult(i));
    store.clear();
    assertEquals(0, new DiagnosticStore(directory).read(false, 0).length());
    assertEquals(0, directory.listFiles().length);
    store.append(actionResult(0));
    assertEquals(1, store.read(false, 0).length());
  }

  @Test
  public void concurrentRequestsProduceCompleteSeparateRecords() throws Exception {
    DiagnosticStore store = new DiagnosticStore(temporary.newFolder());
    ExecutorService writers = Executors.newFixedThreadPool(4);
    try {
      List<Future<?>> pending = new ArrayList<>();
      for (int i = 0; i < 40; i++)
        pending.add(
            writers.submit(
                () -> {
                  try {
                    store.append(actionResult(0));
                  } catch (Exception error) {
                    throw new RuntimeException(error);
                  }
                }));
      for (Future<?> result : pending) result.get();
      JSONArray events = store.read(false, 0);
      assertEquals(40, events.length());
      Set<String> ids = new HashSet<>();
      for (int i = 0; i < events.length(); i++)
        ids.add(events.getJSONObject(i).getString("requestId"));
      assertEquals(40, ids.size());
    } finally {
      writers.shutdownNow();
    }
  }

  @Test(expected = IOException.class)
  public void storageFailureIsReportedRatherThanPretendingEmptyHistory() throws Exception {
    new DiagnosticStore(temporary.newFile());
  }

  @Test
  public void nestedToolFailureRecordsExitAndOmitsArgumentsAndPrivateOutput() throws Exception {
    List<DiagnosticEvent> events = new ArrayList<>();
    McpRouterTest.Fake backend =
        new McpRouterTest.Fake() {
          @Override
          public JSONObject action(JSONObject args) throws Exception {
            return new JSONObject()
                .put("exitCode", 7)
                .put("stdout", "private-screen-do-not-store")
                .put("stderr", "sk-test-do-not-store");
          }
        };
    McpRouter router = new McpRouter(backend, events::add);
    JSONObject response =
        router.handle(
            request(
                "phone_action",
                new JSONObject()
                    .put("action", "type")
                    .put("text", "private-payload-do-not-store")));
    assertTrue(response.getJSONObject("result").getBoolean("isError"));
    assertEquals(2, events.size());
    JSONObject start = events.get(0).json(), end = events.get(1).json();
    assertEquals(start.getString("requestId"), end.getString("requestId"));
    assertEquals("ERROR", end.getString("outcome"));
    assertEquals("TOOL_FAILED", end.getString("code"));
    assertEquals(7, end.getInt("exitCode"));
    assertFalse(start.toString().contains("do-not-store"));
    assertFalse(end.toString().contains("do-not-store"));
  }

  @Test
  public void exceptionsAndUnknownToolNamesCannotLeakIntoHistory() throws Exception {
    List<DiagnosticEvent> events = new ArrayList<>();
    McpRouter router =
        new McpRouter(
            new McpRouterTest.Fake() {
              @Override
              public JSONObject status() throws Exception {
                throw new IOException("private-secret-do-not-store");
              }
            },
            events::add);
    assertTrue(
        router
            .handle(request("phone_status", new JSONObject()))
            .getJSONObject("result")
            .getBoolean("isError"));
    router.handle(request("private-tool-name-do-not-store", new JSONObject()));
    assertEquals(3, events.size());
    assertEquals(Code.IO_ERROR, events.get(1).code);
    assertEquals(Operation.UNKNOWN_TOOL, events.get(2).operation);
    for (DiagnosticEvent event : events)
      assertFalse(event.json().toString().contains("do-not-store"));
  }

  @Test
  public void authenticationPauseAndCancellationAreNotTechnicalFailures() throws Exception {
    List<DiagnosticEvent> events = new ArrayList<>();
    McpRouterTest.Fake backend = new McpRouterTest.Fake();
    backend.authenticationRequired = true;
    McpRouter router = new McpRouter(backend, events::add);
    router.handle(request("phone_finish_task", new JSONObject()));
    assertEquals(Outcome.WAITING, events.get(1).outcome);
    assertEquals(0, backend.returns);
    router.handle(
        request("phone_wait_for_authentication", new JSONObject().put("timeoutSeconds", 1)));
    assertEquals(Outcome.WAITING, events.get(3).outcome);
    router.handle(request("phone_wait_for_authentication", new JSONObject().put("cancel", true)));
    assertEquals(Outcome.CANCELLED, events.get(5).outcome);
  }

  @Test
  public void timeoutsAndHttpFailuresKeepOnlyNumericMetadata() throws Exception {
    assertEquals(
        Code.TIMEOUT,
        DiagnosticEvent.problem(Area.TOOL, Operation.ACTION, new TimeoutException(), newId()).code);
    JSONObject http =
        DiagnosticEvent.http(Operation.DELIVER_RESPONSE, 403, "sk-secret-do-not-store").json();
    assertEquals(403, http.getInt("httpStatus"));
    assertFalse(http.has("requestId"));
    assertEquals(
        Code.RESPONSE_EXPIRED, DiagnosticEvent.http(Operation.DELIVER_RESPONSE, 404, newId()).code);
  }

  @Test
  public void loggingFailureCannotBreakPhoneTools() throws Exception {
    McpRouter router =
        new McpRouter(
            new McpRouterTest.Fake(),
            event -> {
              throw new IllegalStateException("unavailable");
            });
    assertFalse(
        router
            .handle(request("phone_status", new JSONObject()))
            .getJSONObject("result")
            .getBoolean("isError"));
  }

  @Test
  public void optionalDiscoveryProbesAreNotRecordedAsPhoneFailures() throws Exception {
    List<DiagnosticEvent> events = new ArrayList<>();
    McpRouter router = new McpRouter(new McpRouterTest.Fake(), events::add);
    for (String method :
        new String[] {"resources/list", "resources/templates/list", "prompts/list"}) {
      JSONObject probe = new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method);
      assertEquals(-32601, router.handle(probe).getJSONObject("error").getInt("code"));
    }
    assertEquals(3, events.size());
    for (DiagnosticEvent event : events) assertEquals(Outcome.UNSUPPORTED, event.outcome);
    router.handle(new JSONObject().put("jsonrpc", "1.0").put("id", 1).put("method", "tools/list"));
    assertEquals(4, events.size());
    assertEquals(Code.PROTOCOL_ERROR, events.get(3).code);
  }
}
