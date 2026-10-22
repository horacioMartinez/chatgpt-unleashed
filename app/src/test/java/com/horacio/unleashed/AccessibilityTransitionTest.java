package com.horacio.unleashed;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class AccessibilityTransitionTest {
  private static final String CHAT = "com.openai.chatgpt", WHATSAPP = "com.whatsapp";
  private final long[] now = {0};
  private final AuthenticationGate gate = new AuthenticationGate(() -> {}, () -> now[0]);

  static JSONObject screen(String app, boolean readable) throws Exception {
    return new JSONObject()
        .put("appPackage", app)
        .put("targetPackage", app)
        .put("appWindowId", 4)
        .put("displayWidth", 1080)
        .put("displayHeight", 2400)
        .put("appContentAvailable", readable)
        .put("device", new JSONObject())
        .put(
            "elements",
            readable
                ? new JSONArray()
                    .put(
                        new JSONObject()
                            .put("package", app)
                            .put("windowId", 4)
                            .put("text", "Send to")
                            .put("bounds", "[20,100][500,200]"))
                : new JSONArray());
  }

  @Test
  public void transientGapDoesNotLatchAuthenticationOrShowUnlockLabel() throws Exception {
    gate.observeAccessibility(screen(CHAT, false));
    assertFalse(gate.waiting());
    assertFalse(gate.indicatorWaiting());
    assertTrue(gate.state().getBoolean("keepWaiting"));
    assertEquals("waiting_for_screen", gate.state().getString("status"));
    now[0] = 2999;
    gate.observeAccessibility(screen(CHAT, false));
    assertFalse(gate.waiting());
    assertEquals(1, gate.transitionCheckDelayMillis());
  }

  @Test
  public void persistentGapEntersUnlockWaitAtThreeSecondsWithoutAnotherLabelDelay()
      throws Exception {
    gate.observeAccessibility(screen(WHATSAPP, false));
    now[0] = 3000;
    gate.observeAccessibility(screen(WHATSAPP, false));
    assertTrue(gate.waiting());
    assertTrue(gate.indicatorWaiting());
    assertTrue(gate.state().getBoolean("assumedAuthentication"));
    assertFalse(gate.state().getBoolean("transitionPending"));
    assertEquals(WHATSAPP, gate.state().getString("expectedPackage"));
    assertEquals(-1, gate.transitionCheckDelayMillis());
  }

  @Test
  public void chatgptToWhatsappSendToHandoffClearsPendingGap() throws Exception {
    gate.observeAccessibility(screen(CHAT, false));
    now[0] = 1000;
    gate.observeAccessibility(screen(WHATSAPP, true));
    assertEquals("ready", gate.state().getString("status"));
    assertFalse(gate.state().getBoolean("keepWaiting"));
    assertFalse(gate.indicatorWaiting());
    assertEquals(-1, gate.transitionCheckDelayMillis());
  }

  @Test
  public void destinationChangeGetsItsOwnGraceAndOwner() throws Exception {
    gate.observeAccessibility(screen(CHAT, false));
    now[0] = 2000;
    gate.observeAccessibility(screen(WHATSAPP, false));
    assertEquals(WHATSAPP, gate.state().getString("expectedPackage"));
    now[0] = 4999;
    gate.observeAccessibility(screen(WHATSAPP, false));
    assertFalse(gate.waiting());
    now[0] = 5000;
    gate.observeAccessibility(screen(WHATSAPP, false));
    assertTrue(gate.waiting());
    assertEquals(WHATSAPP, gate.state().getString("expectedPackage"));
  }

  @Test
  public void readableDestinationRecoversEvenAfterAnOutgoingAppWasAssumedLocked() throws Exception {
    gate.observeAccessibility(screen(CHAT, false));
    now[0] = 3000;
    gate.observeAccessibility(screen(CHAT, false));
    assertTrue(gate.waiting());
    gate.observeAccessibility(screen(WHATSAPP, true));
    assertFalse(gate.waiting());
    assertFalse(gate.state().getBoolean("keepWaiting"));
    assertEquals(WHATSAPP, gate.state().getString("expectedPackage"));
  }

  @Test
  public void inferredOwnerFollowsNewInaccessibleDestinationWithoutClearingPause()
      throws Exception {
    gate.observeAccessibility(screen(CHAT, false));
    now[0] = 3000;
    gate.observeAccessibility(screen(CHAT, false));
    gate.observeAccessibility(screen(WHATSAPP, false));
    assertTrue(gate.waiting());
    assertEquals(WHATSAPP, gate.state().getString("expectedPackage"));
  }

  @Test
  public void detectedBiometricPromptRemainsImmediateAndTiedToItsOwner() throws Exception {
    JSONObject locked = screen(WHATSAPP, false);
    locked.getJSONObject("device").put("promptVisible", true);
    gate.observeAccessibility(locked);
    assertTrue(gate.waiting());
    assertFalse(gate.state().getBoolean("transitionPending"));
    assertFalse(gate.state().getBoolean("assumedAuthentication"));
    gate.observeAccessibility(screen(CHAT, true));
    assertTrue(gate.waiting());
    gate.observeAccessibility(screen(WHATSAPP, true));
    assertFalse(gate.waiting());
  }

  @Test
  public void cancellationAndClearDiscardPendingCountdown() throws Exception {
    gate.observeAccessibility(screen(CHAT, false));
    gate.cancel();
    assertTrue(gate.cancelled());
    assertFalse(gate.state().getBoolean("keepWaiting"));
    gate.observeAccessibility(screen(WHATSAPP, true));
    assertTrue(gate.cancelled());
    gate.clear();
    now[0] = 5000;
    gate.observeAccessibility(screen(WHATSAPP, false));
    assertFalse(gate.waiting());
    assertEquals(3000, gate.transitionCheckDelayMillis());
  }

  @Test
  public void waitToolDoesNotForceAuthenticationDuringTheGracePeriod() throws Exception {
    gate.observeAccessibility(screen(CHAT, false));
    JSONObject result =
        gate.waitFor(
            2,
            CHAT,
            () -> {
              assertFalse(gate.waiting());
              JSONObject s = screen(now[0] < 1000 ? CHAT : WHATSAPP, now[0] >= 1000);
              gate.observeAccessibility(s);
              return s;
            },
            () -> now[0],
            ms -> now[0] += ms);
    assertEquals("ready", result.getString("status"));
    assertEquals(WHATSAPP, result.getJSONObject("screen").getString("appPackage"));
    assertFalse(result.getBoolean("keepWaiting"));
  }

  @Test
  public void delayedWaitAfterHandoffDoesNotRecreateOutgoingAppAuthentication() throws Exception {
    gate.observeAccessibility(screen(CHAT, false));
    now[0] = 800;
    gate.observeAccessibility(screen(WHATSAPP, true));
    now[0] = 4000;
    JSONObject result =
        gate.waitFor(
            2,
            CHAT,
            () -> {
              JSONObject s = screen(WHATSAPP, true);
              gate.observeAccessibility(s);
              return s;
            },
            () -> now[0],
            ms -> now[0] += ms);
    assertEquals("ready", result.getString("status"));
    assertEquals(WHATSAPP, result.getJSONObject("screen").getString("appPackage"));
    assertFalse(gate.waiting());
  }

  @Test
  public void observationErrorsStartAGraceRatherThanReturningFalseReadiness() throws Exception {
    JSONObject result =
        gate.waitFor(
            1,
            CHAT,
            () -> {
              throw new java.io.IOException("UI unavailable");
            },
            () -> now[0],
            ms -> now[0] += ms);
    assertEquals("waiting_for_screen", result.getString("status"));
    assertTrue(result.getBoolean("keepWaiting"));
    assertFalse(gate.waiting());
  }

  @Test
  public void pendingTransitionBlocksActionsButGivesScreenSettlingGuidance() throws Exception {
    class Backend extends McpRouterTest.Fake {
      @Override
      public JSONObject authenticationStatus() throws Exception {
        return new JSONObject().put("required", false).put("transitionPending", true);
      }
    }
    Backend backend = new Backend();
    McpRouter router = new McpRouter(backend);
    for (String name : new String[] {"phone_action", "phone_finish_task", "phone_screenshot"}) {
      JSONObject result =
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
      assertFalse(result.getBoolean("isError"));
      JSONObject value =
          new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
      assertTrue(value.getBoolean("keepWaiting"));
      assertFalse(value.getBoolean("actionPerformed"));
      assertFalse(value.getBoolean("finished"));
      assertTrue(value.getString("taskCompletion").contains("not yet an authentication prompt"));
    }
    assertEquals(0, backend.actions);
    assertEquals(0, backend.returns);
    assertEquals(0, backend.finishes);
  }
}
