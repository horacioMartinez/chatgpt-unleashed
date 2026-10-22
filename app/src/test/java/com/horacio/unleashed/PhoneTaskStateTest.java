package com.horacio.unleashed;

import static org.junit.Assert.*;

import org.junit.Test;

public class PhoneTaskStateTest {
  @Test
  public void lostBackendClearsIndicatorAndNextRequestCanStart() {
    PhoneTaskState state = new PhoneTaskState();
    state.begin(0);
    state.end(10);
    state.toolFailed(false);
    assertEquals(PhoneTaskState.Status.IDLE, state.status(11, false));
    state.begin(12);
    assertEquals(PhoneTaskState.Status.WORKING, state.status(13, false));
  }

  @Test
  public void failureDoesNotHideAnotherRunningCall() {
    PhoneTaskState state = new PhoneTaskState();
    state.begin(0);
    state.begin(1);
    state.end(2);
    state.toolFailed(false);
    assertEquals(PhoneTaskState.Status.WORKING, state.status(3, false));
    state.end(4);
    assertEquals(PhoneTaskState.Status.IDLE, state.status(5, false));
  }

  @Test
  public void recoverableToolErrorKeepsIndicatorAndTaskAcrossRetry() {
    PhoneTaskState state = new PhoneTaskState();
    state.begin(0);
    String task = state.taskId();
    state.end(10);
    state.toolFailed(true);
    assertEquals(PhoneTaskState.Status.WORKING, state.status(11, false));
    state.begin(500);
    assertEquals(task, state.taskId());
    state.end(600);
    assertEquals(PhoneTaskState.Status.WORKING, state.status(601, false));
    state.finish();
    assertEquals(PhoneTaskState.Status.IDLE, state.status(602, false));
  }

  @Test
  public void abandonedRecoveryStillExpiresAndErrorCannotReviveFinishedTask() {
    PhoneTaskState state = new PhoneTaskState();
    state.begin(0);
    state.end(10);
    state.toolFailed(true);
    assertEquals(PhoneTaskState.Status.IDLE, state.status(120_010, false));
    state.begin(200_000);
    state.finish();
    state.end(200_001);
    state.toolFailed(true);
    assertEquals(PhoneTaskState.Status.IDLE, state.status(200_002, false));
  }

  @Test
  public void staysWorkingBetweenToolsUntilTaskFinishes() {
    PhoneTaskState state = new PhoneTaskState();
    assertEquals(PhoneTaskState.Status.IDLE, state.status(0, false));
    state.begin(100);
    state.end(200);
    assertEquals(PhoneTaskState.Status.WORKING, state.status(30_000, false));
    state.begin(40_000);
    state.end(41_000);
    assertEquals(PhoneTaskState.Status.WORKING, state.status(130_000, false));
    state.finish();
    assertEquals(PhoneTaskState.Status.IDLE, state.status(130_001, false));
  }

  @Test
  public void authenticationRemainsVisibleAcrossWaitIntervalsAndResumes() {
    PhoneTaskState state = new PhoneTaskState();
    state.begin(0);
    state.end(40_000);
    assertEquals(PhoneTaskState.Status.WAITING_FOR_AUTHENTICATION, state.status(300_000, true));
    state.begin(300_001);
    assertEquals(PhoneTaskState.Status.WORKING, state.status(300_002, false));
    state.end(300_003);
    assertEquals(PhoneTaskState.Status.WORKING, state.status(300_004, false));
  }

  @Test
  public void slowOrOverlappingCallsDoNotExpireWhileRunning() {
    PhoneTaskState state = new PhoneTaskState();
    state.begin(0);
    state.begin(10);
    state.end(20);
    assertEquals(PhoneTaskState.Status.WORKING, state.status(300_000, false));
    state.end(300_001);
    assertEquals(PhoneTaskState.Status.WORKING, state.status(400_000, false));
  }

  @Test
  public void interruptedAgentDoesNotLeaveWorkingBadgeForever() {
    PhoneTaskState state = new PhoneTaskState();
    state.begin(0);
    state.end(10);
    assertEquals(PhoneTaskState.Status.IDLE, state.status(120_010, false));
    state.begin(200_000);
    assertEquals(PhoneTaskState.Status.WORKING, state.status(200_001, false));
  }

  @Test
  public void completingOutstandingCallAfterFinishDoesNotResurrectBadge() {
    PhoneTaskState state = new PhoneTaskState();
    state.begin(0);
    state.begin(1);
    state.finish();
    state.end(2);
    state.end(3);
    assertEquals(PhoneTaskState.Status.IDLE, state.status(4, false));
  }

  @Test
  public void stoppingServiceRemovesEvenAuthenticationStatus() {
    PhoneTaskState state = new PhoneTaskState();
    state.begin(0);
    state.reset();
    state.end(10);
    assertEquals(PhoneTaskState.Status.IDLE, state.status(11, true));
  }
}
