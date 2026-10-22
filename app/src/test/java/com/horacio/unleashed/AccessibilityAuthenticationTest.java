package com.horacio.unleashed;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class AccessibilityAuthenticationTest {
  @Test
  public void chatInstructionsAreNotMistakenForAnAuthenticationPrompt() throws Exception {
    assertFalse(
        AuthenticationGate.looksLikeAuthentication(
            new JSONObject()
                .put(
                    "elements",
                    new JSONArray()
                        .put(
                            node(
                                "com.openai.chatgpt",
                                WINDOW,
                                "Fingerprint required: wait instead of aborting")))));
  }

  private static final String APP = "com.mercadopago.wallet";
  private static final int WINDOW = 3;

  private static JSONObject node(String pkg, int window, String text) throws Exception {
    return new JSONObject()
        .put("package", pkg)
        .put("windowId", window)
        .put("text", text)
        .put("bounds", "[10,100][800,300]");
  }

  private static JSONObject screen(boolean accessible, String app, JSONArray nodes)
      throws Exception {
    return new JSONObject()
        .put("appPackage", app)
        .put("targetPackage", APP)
        .put("appContentAvailable", accessible)
        .put("appWindowId", WINDOW)
        .put("displayWidth", 1080)
        .put("displayHeight", 2400)
        .put("device", new JSONObject())
        .put("elements", nodes);
  }

  private static JSONObject blank() throws Exception {
    return screen(false, APP, new JSONArray());
  }

  private static JSONObject unlocked() throws Exception {
    return screen(true, APP, new JSONArray().put(node(APP, WINDOW, "Home")));
  }

  private static AuthenticationGate gate() {
    return new AuthenticationGate(() -> {});
  }

  private static AuthenticationGate assumedGate() throws Exception {
    long[] now = {0};
    AuthenticationGate gate = new AuthenticationGate(() -> {}, () -> now[0]);
    gate.observeAccessibility(blank());
    now[0] = 3000;
    gate.observeAccessibility(blank());
    return gate;
  }

  @Test
  public void inaccessibleScreenAssumesAuthenticationWithoutPromptEvidence() throws Exception {
    AuthenticationGate gate = assumedGate();
    JSONObject state = gate.state();
    assertTrue(state.getBoolean("required"));
    assertTrue(state.getBoolean("assumedAuthentication"));
    assertTrue(state.getBoolean("keepWaiting"));
    assertEquals(APP, state.getString("expectedPackage"));
    assertTrue(state.getString("nextAction").contains("do not abort"));
  }

  @Test
  public void chromeAndOtherWindowsCannotCountAsAccessibleAppContent() throws Exception {
    JSONArray nodes =
        new JSONArray()
            .put(node("com.android.systemui", 1, "12:00"))
            .put(node("com.keyboard", 2, "Space"))
            .put(node(APP, 7, "Readable background window"))
            .put(node("com.horacio.unleashed", 8, "ChatGPT Working"));
    assertFalse(AccessibilityContent.readable(nodes, WINDOW, APP, 1080, 2400));
    AuthenticationGate gate = assumedGate();
    gate.observeAccessibility(screen(true, APP, nodes));
    assertTrue(gate.waiting());
  }

  @Test
  public void offscreenAndPasswordFieldsCannotReleaseAnUnlockWait() throws Exception {
    JSONObject password = node(APP, WINDOW, "[password field]").put("password", true);
    JSONObject offscreen = node(APP, WINDOW, "Home").put("bounds", "[1200,100][1800,300]");
    JSONArray nodes = new JSONArray().put(password).put(offscreen);
    assertFalse(AccessibilityContent.readable(nodes, WINDOW, APP, 1080, 2400));
    AuthenticationGate gate = assumedGate();
    gate.observeAccessibility(screen(true, APP, nodes));
    assertTrue(gate.waiting());
  }

  @Test
  public void confirmedAuthenticationOnlyResumesInTheOriginalApp() throws Exception {
    AuthenticationGate gate = gate();
    gate.require(APP);
    gate.observeAccessibility(
        screen(
            true,
            "com.openai.chatgpt",
            new JSONArray().put(node("com.openai.chatgpt", WINDOW, "ChatGPT"))));
    assertTrue(gate.waiting());
    gate.observeAccessibility(unlocked());
    assertFalse(gate.waiting());
    assertFalse(gate.state().getBoolean("assumedAuthentication"));
  }

  @Test
  public void unreadablePromptBlocksReadableUnderlyingApp() throws Exception {
    AuthenticationGate gate = gate();
    JSONObject blocked = unlocked();
    blocked.put("appContentAvailable", false);
    blocked.getJSONObject("device").put("promptVisible", true);
    gate.observeAccessibility(blocked);
    assertTrue(gate.waiting());
  }

  @Test
  public void waitIntervalsContinueWithoutAbortingAndResumeAfterDelayedUnlock() throws Exception {
    long[] now = {0};
    AuthenticationGate gate = new AuthenticationGate(() -> {}, () -> now[0]);
    gate.observeAccessibility(blank());
    JSONObject first =
        gate.waitFor(
            2,
            APP,
            () -> {
              JSONObject s = blank();
              gate.observeAccessibility(s);
              return s;
            },
            () -> now[0],
            ms -> now[0] += ms);
    assertTrue(first.getBoolean("keepWaiting"));
    assertFalse(first.getBoolean("assumedAuthentication"));
    assertEquals("waiting_for_screen", first.getString("status"));
    int[] probes = {0};
    JSONObject second =
        gate.waitFor(
            5,
            APP,
            () -> {
              JSONObject s = ++probes[0] < 3 ? blank() : unlocked();
              gate.observeAccessibility(s);
              return s;
            },
            () -> now[0],
            ms -> now[0] += ms);
    assertEquals("ready", second.getString("status"));
    assertTrue(second.has("screen"));
    assertFalse(second.getBoolean("keepWaiting"));
  }

  @Test
  public void waitCallCannotRetargetAnExistingPauseToAnotherApp() throws Exception {
    AuthenticationGate gate = assumedGate();
    long[] now = {0};
    JSONObject result =
        gate.waitFor(1, "com.openai.chatgpt", () -> blank(), () -> now[0], ms -> now[0] += ms);
    assertEquals(APP, result.getString("expectedPackage"));
    assertTrue(result.getBoolean("keepWaiting"));
  }

  @Test
  public void cancellationAndTaskClearEndAssumedAuthentication() throws Exception {
    AuthenticationGate gate = assumedGate();
    gate.cancel();
    gate.observeAccessibility(unlocked());
    assertTrue(gate.state().getBoolean("cancelled"));
    assertFalse(gate.state().getBoolean("keepWaiting"));
    gate.clear();
    assertFalse(gate.state().getBoolean("assumedAuthentication"));
    gate.observeAccessibility(unlocked());
    assertFalse(gate.waiting());
  }

  @Test
  public void normalReadableScreenDoesNotStartAnAuthenticationPause() throws Exception {
    AuthenticationGate gate = gate();
    gate.observeAccessibility(unlocked());
    assertFalse(gate.waiting());
  }

  @Test
  public void enterNavigationAndFinishStayBlockedDuringAssumedAuthentication() throws Exception {
    class Backend extends McpRouterTest.Fake {
      @Override
      public JSONObject authenticationStatus() throws Exception {
        return new JSONObject().put("required", true).put("assumedAuthentication", true);
      }
    }
    Backend backend = new Backend();
    McpRouter router = new McpRouter(backend);
    for (String name : new String[] {"phone_action", "phone_finish_task", "phone_screenshot"}) {
      JSONObject reply =
          router
              .handle(
                  McpRouterTest.request("tools/call")
                      .put(
                          "params",
                          new JSONObject()
                              .put("name", name)
                              .put(
                                  "arguments",
                                  new JSONObject().put("action", "key").put("key", "HOME"))))
              .getJSONObject("result");
      assertFalse(reply.getBoolean("isError"));
      JSONObject result =
          new JSONObject(reply.getJSONArray("content").getJSONObject(0).getString("text"));
      assertFalse(result.getBoolean("actionPerformed"));
      assertFalse(result.getBoolean("finished"));
      assertTrue(result.getBoolean("keepWaiting"));
      assertTrue(result.getString("taskCompletion").contains("do not abort"));
    }
    assertEquals(0, backend.actions);
    assertEquals(0, backend.returns);
    assertEquals(0, backend.finishes);
  }
}
