package com.horacio.unleashed;

import static org.junit.Assert.*;

import org.junit.Test;

public class ConnectionStateTest {
  @Test
  public void setupCountsOnlyLiveAccessibilityAndTunnelConnection() {
    assertEquals(
        0,
        ConnectionState.completedSetupSteps(
            ConnectionState.Backend.SETUP_REQUIRED, ConnectionState.ChatGPT.NOT_LINKED));
    assertEquals(
        1,
        ConnectionState.completedSetupSteps(
            ConnectionState.Backend.RUNNING, ConnectionState.ChatGPT.NOT_LINKED));
    assertEquals(
        1,
        ConnectionState.completedSetupSteps(
            ConnectionState.Backend.SETUP_REQUIRED, ConnectionState.ChatGPT.CONNECTED));
    assertEquals(
        2,
        ConnectionState.completedSetupSteps(
            ConnectionState.Backend.RUNNING, ConnectionState.ChatGPT.CONNECTED));
    for (ConnectionState.ChatGPT chat : ConnectionState.ChatGPT.values()) {
      if (chat == ConnectionState.ChatGPT.CONNECTED) continue;
      assertEquals(0, ConnectionState.completedSetupSteps(ConnectionState.Backend.PAUSED, chat));
      assertEquals(1, ConnectionState.completedSetupSteps(ConnectionState.Backend.RUNNING, chat));
    }
    assertEquals(
        1,
        ConnectionState.completedSetupSteps(
            ConnectionState.Backend.WAITING, ConnectionState.ChatGPT.CONNECTED));
  }

  @Test
  public void missingPermissionRequiresSetupEvenWhenPreviouslyConnected() {
    assertEquals(
        ConnectionState.Backend.SETUP_REQUIRED, ConnectionState.backend(false, true, true));
  }

  @Test
  public void ownerPauseWinsOverConnectedService() {
    assertEquals(ConnectionState.Backend.PAUSED, ConnectionState.backend(true, false, true));
  }

  @Test
  public void enabledServiceMustActuallyConnectBeforeRunning() {
    assertEquals(ConnectionState.Backend.WAITING, ConnectionState.backend(true, true, false));
    assertEquals(ConnectionState.Backend.RUNNING, ConnectionState.backend(true, true, true));
  }

  @Test
  public void connectionStatesRespectSavedOwnerChoice() {
    assertEquals(
        ConnectionState.ChatGPT.NOT_LINKED,
        ConnectionState.chatGPT(false, true, true, true, false));
    assertEquals(
        ConnectionState.ChatGPT.PAUSED, ConnectionState.chatGPT(true, false, true, true, false));
    assertEquals(
        ConnectionState.ChatGPT.CONNECTING,
        ConnectionState.chatGPT(true, true, true, false, false));
    assertEquals(
        ConnectionState.ChatGPT.CONNECTED, ConnectionState.chatGPT(true, true, true, true, false));
    assertEquals(
        ConnectionState.ChatGPT.NEEDS_ATTENTION,
        ConnectionState.chatGPT(true, true, true, false, true));
  }
}
