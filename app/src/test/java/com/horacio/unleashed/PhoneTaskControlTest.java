package com.horacio.unleashed;

import static org.junit.Assert.*;

import java.util.concurrent.CancellationException;
import org.junit.Test;

public class PhoneTaskControlTest {
  @Test
  public void stopRevokesRunningAndQueuedCalls() {
    PhoneTaskControl control = new PhoneTaskControl();
    long running = control.begin(), queued = control.begin();
    control.stop();
    assertTrue(control.cancelled(running));
    assertThrows(CancellationException.class, () -> control.require(queued));
    assertThrows(CancellationException.class, control::begin);
  }

  @Test
  public void acknowledgementAllowsNewTaskButCannotReviveOldCalls() {
    PhoneTaskControl control = new PhoneTaskControl();
    long previous = control.begin();
    control.stop();
    long stopped = control.generation();
    assertTrue(control.acknowledge(stopped));
    long next = control.begin();
    assertNotEquals(previous, next);
    assertFalse(control.cancelled(next));
    assertThrows(CancellationException.class, () -> control.require(previous));
    assertFalse(control.acknowledge(stopped)); // Duplicate finish is harmless.
    control.require(next);
  }

  @Test
  public void staleAcknowledgementCannotClearAnotherStop() {
    PhoneTaskControl control = new PhoneTaskControl();
    control.stop();
    long first = control.generation();
    control.stop();
    assertFalse(control.acknowledge(first));
    assertTrue(control.stopped());
    assertTrue(control.acknowledge(control.generation()));
  }
}
