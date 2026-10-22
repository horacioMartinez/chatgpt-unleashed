package com.horacio.unleashed;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class McpRouterTest {
  static class Fake implements McpRouter.Backend {
    int actions,
        observations,
        returns,
        waits,
        started,
        completed,
        acknowledgements,
        failures,
        finishes;
    boolean stopped, stopDuringAction, stopDuringScreenshot;
    boolean statusFails;
    boolean readFails, returnThrows;
    boolean authenticationRequired, authenticationCancelled;
    boolean returnSucceeds = true;
    String last;

    public JSONObject action(JSONObject args) throws Exception {
      actions++;
      last = args.toString();
      if (stopDuringAction) stopped = true;
      return new JSONObject().put("exitCode", 0).put("stdout", "ok");
    }

    public JSONObject readScreen() throws Exception {
      observations++;
      if (readFails) throw new IllegalStateException("Backend still starting");
      return new JSONObject().put("elements", new JSONArray());
    }

    public String screenshotBase64() {
      if (stopDuringScreenshot) stopped = true;
      return "iVBORw0KGgo=";
    }

    public JSONObject status() throws Exception {
      if (statusFails) throw new IllegalStateException("Unavailable");
      return new JSONObject().put("connected", true);
    }

    public String diagnosticTaskId() {
      return "";
    }

    public void toolStarted() {
      started++;
    }

    public void toolCompleted() {
      completed++;
    }

    public void toolFailed() {
      failures++;
    }

    public JSONObject taskCancellation() throws Exception {
      return stopped
          ? new JSONObject()
              .put("status", "cancelled")
              .put("cancelled", true)
              .put("finished", false)
              .put("keepWaiting", false)
          : null;
    }

    public void acknowledgeTaskCancellation() {
      stopped = false;
      acknowledgements++;
    }

    public JSONObject returnToChatGPT() throws Exception {
      returns++;
      if (returnThrows) throw new IllegalStateException("Backend disconnected");
      return new JSONObject()
          .put("exitCode", returnSucceeds ? 0 : 1)
          .put("returned", returnSucceeds);
    }

    public JSONObject authenticationStatus() throws Exception {
      return new JSONObject()
          .put("required", authenticationRequired)
          .put("cancelled", authenticationCancelled);
    }

    public JSONObject waitForAuthentication(int seconds, String owner) throws Exception {
      waits++;
      return new JSONObject()
          .put("keepWaiting", authenticationRequired)
          .put("status", authenticationRequired ? "waiting_for_user_authentication" : "ready");
    }

    public JSONObject cancelAuthenticationWait() throws Exception {
      authenticationRequired = false;
      authenticationCancelled = true;
      return new JSONObject().put("status", "cancelled");
    }

    public void authenticationTaskFinished() {
      finishes++;
      authenticationCancelled = false;
    }
  }

  static JSONObject request(String method) throws Exception {
    return new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method);
  }

  @Test
  public void discoveryAndUnknownToolsDoNotStartTaskIndicator() throws Exception {
    Fake backend = new Fake();
    McpRouter router = new McpRouter(backend);
    router.handle(request("initialize"));
    router.handle(request("tools/list"));
    router.handle(request("tools/call").put("params", new JSONObject().put("name", "unknown")));
    assertEquals(0, backend.started);
    assertEquals(0, backend.completed);
  }

  @Test
  public void taskIndicatorLifecycleBalancesEvenWhenToolFails() throws Exception {
    Fake backend = new Fake();
    McpRouter router = new McpRouter(backend);
    JSONObject input =
        request("tools/call").put("params", new JSONObject().put("name", "phone_status"));
    router.handle(input);
    assertEquals(1, backend.started);
    assertEquals(1, backend.completed);
    backend.statusFails = true;
    assertTrue(router.handle(input).getJSONObject("result").getBoolean("isError"));
    assertEquals(2, backend.started);
    assertEquals(2, backend.completed);
  }

  @Test
  public void stopBlocksAllPhoneToolsUntilFinishAcknowledgesIt() throws Exception {
    Fake backend = new Fake();
    backend.stopped = true;
    backend.authenticationRequired = true;
    McpRouter router = new McpRouter(backend);
    for (String name :
        new String[] {
          "phone_status",
          "phone_action",
          "phone_read_screen",
          "phone_screenshot",
          "phone_wait_for_authentication"
        }) {
      JSONObject result =
          router
              .handle(request("tools/call").put("params", new JSONObject().put("name", name)))
              .getJSONObject("result");
      assertFalse(result.getBoolean("isError"));
      JSONObject value =
          new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
      assertTrue(value.getBoolean("cancelled"));
      assertFalse(value.getBoolean("keepWaiting"));
    }
    assertEquals(0, backend.started);
    assertEquals(0, backend.actions + backend.observations + backend.waits + backend.returns);
    JSONObject result = router.handle(finish(new JSONObject())).getJSONObject("result");
    JSONObject value =
        new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
    assertTrue(value.getBoolean("finished"));
    assertTrue(value.getBoolean("cancelled"));
    assertEquals(1, backend.acknowledgements);
    assertEquals(
        0, backend.returns); // Owner stop is not blocked by an outstanding fingerprint prompt.
    backend.authenticationRequired = false;
    router.handle(
        request("tools/call")
            .put(
                "params",
                new JSONObject()
                    .put("name", "phone_action")
                    .put("arguments", new JSONObject().put("action", "key").put("key", "HOME"))));
    assertEquals(1, backend.actions); // Same connection can serve the next request.
  }

  @Test
  public void stopDuringActionReplacesItsResultWithCancellation() throws Exception {
    Fake backend = new Fake();
    backend.stopDuringAction = true;
    JSONObject result =
        new McpRouter(backend)
            .handle(
                request("tools/call")
                    .put(
                        "params",
                        new JSONObject()
                            .put("name", "phone_action")
                            .put(
                                "arguments",
                                new JSONObject().put("action", "key").put("key", "HOME"))))
            .getJSONObject("result");
    JSONObject value =
        new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
    assertTrue(value.getBoolean("cancelled"));
    assertFalse(value.has("stdout"));
    assertEquals(1, backend.started);
    assertEquals(1, backend.completed);
  }

  @Test
  public void stopDuringScreenshotDoesNotDeliverTheImage() throws Exception {
    Fake backend = new Fake();
    backend.stopDuringScreenshot = true;
    JSONObject result =
        new McpRouter(backend)
            .handle(
                request("tools/call")
                    .put("params", new JSONObject().put("name", "phone_screenshot")))
            .getJSONObject("result");
    JSONArray content = result.getJSONArray("content");
    assertEquals(1, content.length());
    assertEquals("text", content.getJSONObject(0).getString("type"));
    assertTrue(new JSONObject(content.getJSONObject(0).getString("text")).getBoolean("cancelled"));
  }

  @Test
  public void discoveryDoesNotExecutePhoneActions() throws Exception {
    Fake backend = new Fake();
    McpRouter router = new McpRouter(backend);
    assertEquals(
        6,
        router
            .handle(request("tools/list"))
            .getJSONObject("result")
            .getJSONArray("tools")
            .length());
    assertEquals(0, backend.actions);
  }

  @Test
  public void writesHaveWriteAnnotations() throws Exception {
    JSONArray tools = McpRouter.tools();
    for (int i = 0; i < tools.length(); i++) {
      JSONObject tool = tools.getJSONObject(i);
      boolean write =
          tool.getString("name")
              .matches("phone_action|phone_finish_task|phone_wait_for_authentication");
      assertEquals(!write, tool.getJSONObject("annotations").getBoolean("readOnlyHint"));
    }
  }

  @Test
  public void actionReturnsScreenInSameCall() throws Exception {
    Fake backend = new Fake();
    McpRouter router = new McpRouter(backend);
    JSONObject input =
        request("tools/call")
            .put(
                "params",
                new JSONObject()
                    .put("name", "phone_action")
                    .put(
                        "arguments",
                        new JSONObject().put("action", "tap").put("x", 100).put("y", 200)));
    JSONObject result = router.handle(input).getJSONObject("result");
    assertFalse(result.getBoolean("isError"));
    assertEquals("tap", new JSONObject(backend.last).getString("action"));
    assertEquals(100, new JSONObject(backend.last).getInt("x"));
    assertEquals(1, backend.observations);
    assertEquals(0, backend.returns); // Never jump away during an unfinished task.
  }

  @Test
  public void invalidActionCannotReachBackend() throws Exception {
    Fake backend = new Fake();
    McpRouter router = new McpRouter(backend);
    JSONObject input =
        request("tools/call")
            .put(
                "params",
                new JSONObject()
                    .put("name", "phone_action")
                    .put(
                        "arguments",
                        new JSONObject()
                            .put("action", "launch")
                            .put("package", "com.whatsapp; reboot")));
    assertTrue(router.handle(input).getJSONObject("result").getBoolean("isError"));
    assertEquals(0, backend.actions);
  }

  @Test
  public void textRemainsLiteralUnicodeInput() throws Exception {
    Fake backend = new Fake();
    JSONObject args = new JSONObject().put("action", "type").put("text", "mamá 😊 a';$(id)");
    JSONObject result =
        new McpRouter(backend)
            .handle(
                request("tools/call")
                    .put(
                        "params",
                        new JSONObject().put("name", "phone_action").put("arguments", args)))
            .getJSONObject("result");
    assertFalse(result.getBoolean("isError"));
    assertEquals(args.getString("text"), new JSONObject(backend.last).getString("text"));
  }

  @Test
  public void notificationsHaveNoResponse() throws Exception {
    assertNull(
        new McpRouter(new Fake())
            .handle(
                new JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")));
  }

  @Test
  public void unknownMethodReturnsProtocolError() throws Exception {
    assertEquals(
        -32601,
        new McpRouter(new Fake()).handle(request("bogus")).getJSONObject("error").getInt("code"));
  }

  @Test
  public void screenshotUsesImageContent() throws Exception {
    JSONObject input =
        request("tools/call").put("params", new JSONObject().put("name", "phone_screenshot"));
    JSONObject content =
        new McpRouter(new Fake())
            .handle(input)
            .getJSONObject("result")
            .getJSONArray("content")
            .getJSONObject(0);
    assertEquals("image", content.getString("type"));
    assertEquals("image/png", content.getString("mimeType"));
  }

  static JSONObject finish(JSONObject arguments) throws Exception {
    return request("tools/call")
        .put(
            "params",
            new JSONObject().put("name", "phone_finish_task").put("arguments", arguments));
  }

  @Test
  public void finishDefaultsToRestoringExistingChatGPTTask() throws Exception {
    Fake backend = new Fake();
    JSONObject result =
        new McpRouter(backend).handle(finish(new JSONObject())).getJSONObject("result");
    JSONObject value =
        new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
    assertFalse(result.getBoolean("isError"));
    assertTrue(value.getBoolean("finished"));
    assertTrue(value.getBoolean("returned"));
    assertEquals(1, backend.returns);
    assertEquals(0, backend.actions);
    assertEquals(0, backend.observations);
  }

  @Test
  public void explicitLeaveOpenDoesNotNavigate() throws Exception {
    Fake backend = new Fake();
    JSONObject result =
        new McpRouter(backend)
            .handle(finish(new JSONObject().put("returnToChatGPT", false)))
            .getJSONObject("result");
    JSONObject value =
        new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
    assertFalse(result.getBoolean("isError"));
    assertTrue(value.getBoolean("finished"));
    assertFalse(value.getBoolean("returned"));
    assertEquals(0, backend.returns);
    assertEquals(0, backend.actions);
  }

  @Test
  public void failedRestorationFinishesTaskButReportsNavigationFailure() throws Exception {
    Fake backend = new Fake();
    backend.returnSucceeds = false;
    JSONObject result =
        new McpRouter(backend).handle(finish(new JSONObject())).getJSONObject("result");
    JSONObject value =
        new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
    assertTrue(result.getBoolean("isError"));
    assertTrue(value.getBoolean("finished"));
    assertFalse(value.getBoolean("returned"));
    assertEquals(1, backend.finishes);
    assertEquals(1, backend.failures);
  }

  @Test
  public void throwingRestorationStillClearsTaskIndicator() throws Exception {
    Fake backend = new Fake();
    backend.returnThrows = true;
    JSONObject result =
        new McpRouter(backend).handle(finish(new JSONObject())).getJSONObject("result");
    assertTrue(result.getBoolean("isError"));
    assertEquals(1, backend.finishes);
    assertEquals(1, backend.failures);
    assertEquals(backend.started, backend.completed);
  }

  @Test
  public void failedFirstObservationClearsTaskIndicator() throws Exception {
    Fake backend = new Fake();
    backend.readFails = true;
    JSONObject result =
        new McpRouter(backend)
            .handle(
                request("tools/call")
                    .put("params", new JSONObject().put("name", "phone_read_screen")))
            .getJSONObject("result");
    assertTrue(result.getBoolean("isError"));
    assertEquals(1, backend.failures);
    assertEquals(backend.started, backend.completed);
  }

  @Test
  public void malformedReturnOptionCannotNavigate() throws Exception {
    Fake backend = new Fake();
    JSONObject result =
        new McpRouter(backend)
            .handle(finish(new JSONObject().put("returnToChatGPT", "false")))
            .getJSONObject("result");
    assertTrue(result.getBoolean("isError"));
    assertEquals(0, backend.returns);
  }

  @Test
  public void observationsRemindAgentToFinishBeforeAnswering() throws Exception {
    JSONObject input =
        request("tools/call").put("params", new JSONObject().put("name", "phone_read_screen"));
    JSONObject result = new McpRouter(new Fake()).handle(input).getJSONObject("result");
    JSONObject value =
        new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
    assertTrue(value.getString("taskCompletion").contains("phone_finish_task"));
  }

  @Test
  public void pendingAuthenticationPreventsPrematureReturnToChatGPT() throws Exception {
    Fake backend = new Fake();
    backend.authenticationRequired = true;
    JSONObject result =
        new McpRouter(backend).handle(finish(new JSONObject())).getJSONObject("result");
    assertFalse(result.getBoolean("isError"));
    assertEquals(0, backend.returns);
    JSONObject value =
        new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
    assertFalse(value.getBoolean("finished"));
    assertTrue(value.getJSONObject("authentication").getBoolean("required"));
  }

  @Test
  public void pendingAuthenticationPreventsNavigationAndScreenshots() throws Exception {
    Fake backend = new Fake();
    backend.authenticationRequired = true;
    for (String name : new String[] {"phone_action", "phone_screenshot"}) {
      JSONObject input =
          request("tools/call")
              .put(
                  "params",
                  new JSONObject()
                      .put("name", name)
                      .put("arguments", new JSONObject().put("action", "key").put("key", "BACK")));
      assertFalse(
          new McpRouter(backend).handle(input).getJSONObject("result").getBoolean("isError"));
    }
    assertEquals(0, backend.actions);
    assertEquals(0, backend.observations);
  }

  @Test
  public void waitingIntervalDoesNotTurnIntoTaskFailure() throws Exception {
    Fake backend = new Fake();
    backend.authenticationRequired = true;
    JSONObject input =
        request("tools/call")
            .put("params", new JSONObject().put("name", "phone_wait_for_authentication"));
    JSONObject result = new McpRouter(backend).handle(input).getJSONObject("result");
    assertFalse(result.getBoolean("isError"));
    assertEquals(1, backend.waits);
    JSONObject value =
        new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
    assertTrue(value.getBoolean("keepWaiting"));
    assertTrue(value.getString("taskCompletion").contains("repeat"));
  }

  @Test
  public void ownerCanCancelWaitThenFinishWithoutContinuingProtectedWork() throws Exception {
    Fake backend = new Fake();
    backend.authenticationRequired = true;
    McpRouter router = new McpRouter(backend);
    router.handle(
        request("tools/call")
            .put(
                "params",
                new JSONObject()
                    .put("name", "phone_wait_for_authentication")
                    .put("arguments", new JSONObject().put("cancel", true))));
    assertTrue(backend.authenticationCancelled);
    JSONObject action =
        request("tools/call")
            .put(
                "params",
                new JSONObject()
                    .put("name", "phone_action")
                    .put("arguments", new JSONObject().put("action", "key").put("key", "ENTER")));
    router.handle(action);
    assertEquals(0, backend.actions);
    assertFalse(
        router.handle(finish(new JSONObject())).getJSONObject("result").getBoolean("isError"));
    assertEquals(1, backend.returns);
    assertFalse(backend.authenticationCancelled);
  }
}
