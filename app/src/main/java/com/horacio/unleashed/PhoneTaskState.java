package com.horacio.unleashed;

/** Tracks a phone task across tool calls, including the gaps while the agent thinks. */
public final class PhoneTaskState {
  public enum Status {
    IDLE,
    WORKING,
    WAITING_FOR_AUTHENTICATION
  }

  static final long STALE_AFTER_MS = 120_000;
  private boolean active;
  private boolean failed;
  private int inFlight;
  private long lastActivity;
  private String taskId = "";

  public synchronized void begin(long now) {
    if (!active) taskId = java.util.UUID.randomUUID().toString();
    active = true;
    failed = false;
    inFlight++;
    lastActivity = now;
  }

  public synchronized void toolFailed(boolean backendAvailable) {
    // A tool error is not a task boundary: the agent can recover using another tool.
    // If it never finishes, the normal inactivity timeout still removes the indicator.
    if (backendAvailable) return;
    failed = true;
    if (inFlight == 0) active = false;
  }

  public synchronized void end(long now) {
    inFlight = Math.max(0, inFlight - 1);
    lastActivity = now;
    if (inFlight == 0 && failed) active = false;
  }

  public synchronized void finish() {
    active = false;
  }

  public synchronized String taskId() {
    return taskId;
  }

  public synchronized void reset() {
    active = false;
    failed = false;
    inFlight = 0;
  }

  public synchronized Status status(long now, boolean authenticationRequired) {
    if (!active) return Status.IDLE;
    if (authenticationRequired) return Status.WAITING_FOR_AUTHENTICATION;
    if (inFlight > 0 || now - lastActivity < STALE_AFTER_MS) return Status.WORKING;
    // An interrupted agent may never send finish. Do not leave a false working banner forever.
    active = false;
    return Status.IDLE;
  }
}
