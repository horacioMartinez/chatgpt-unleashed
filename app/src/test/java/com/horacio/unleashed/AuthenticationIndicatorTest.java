package com.horacio.unleashed;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class AuthenticationIndicatorTest {
  @Test
  public void labelWaitsThreeSecondsButActionsAreBlockedImmediately() {
    long[] now = {100};
    AuthenticationGate gate = new AuthenticationGate(() -> {}, () -> now[0]);
    gate.require("com.example.app");
    assertTrue(gate.waiting());
    assertFalse(gate.indicatorWaiting());
    now[0] = 3099;
    assertFalse(gate.indicatorWaiting());
    now[0] = 3100;
    assertTrue(gate.indicatorWaiting());
  }

  @Test
  public void repeatedObservationsDoNotPostponeTheLabel() throws Exception {
    long[] now = {0};
    AuthenticationGate gate = new AuthenticationGate(() -> {}, () -> now[0]);
    JSONObject screen =
        new JSONObject().put("appContentAvailable", false).put("targetPackage", "com.example.app");
    gate.observeAccessibility(screen);
    now[0] = 2999;
    gate.observeAccessibility(screen);
    assertFalse(gate.indicatorWaiting());
    now[0] = 3000;
    gate.observeAccessibility(screen);
    assertTrue(gate.indicatorWaiting());
  }

  @Test
  public void briefTransitionResetsTimerAndUnlockClearsLabelImmediately() throws Exception {
    long[] now = {0};
    AuthenticationGate gate = new AuthenticationGate(() -> {}, () -> now[0]);
    gate.require("com.example.app");
    now[0] = 2999;
    gate.observeAccessibility(AccessibilityTransitionTest.screen("com.example.app", true));
    assertFalse(gate.waiting());
    assertFalse(gate.indicatorWaiting());
    gate.require("com.example.app");
    now[0] = 3000;
    assertFalse(gate.indicatorWaiting());
    now[0] = 5999;
    assertTrue(gate.indicatorWaiting());
    gate.observeAccessibility(AccessibilityTransitionTest.screen("com.example.app", true));
    assertFalse(gate.indicatorWaiting());
  }

  @Test
  public void cancellationAndNewTaskCannotReusePreviousDelay() {
    long[] now = {0};
    AuthenticationGate gate = new AuthenticationGate(() -> {}, () -> now[0]);
    gate.require("com.example.app");
    now[0] = 3000;
    assertTrue(gate.indicatorWaiting());
    gate.cancel();
    assertFalse(gate.indicatorWaiting());
    gate.clear();
    gate.require("com.example.other");
    assertFalse(gate.indicatorWaiting());
    now[0] = 6000;
    assertTrue(gate.indicatorWaiting());
    gate.clear();
    assertFalse(gate.indicatorWaiting());
  }
}
