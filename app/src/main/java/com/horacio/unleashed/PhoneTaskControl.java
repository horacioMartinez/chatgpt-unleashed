package com.horacio.unleashed;

import java.util.concurrent.CancellationException;

/** Per-task cancellation latch. Old calls stay revoked after cancellation is acknowledged. */
public final class PhoneTaskControl {
  private boolean stopped;
  private long generation = System.nanoTime();

  public synchronized long begin() {
    if (stopped) throw new CancellationException("Phone task stopped by the owner");
    return generation;
  }

  public synchronized void require(long lease) {
    if (cancelled(lease)) throw new CancellationException("Phone task stopped by the owner");
  }

  public synchronized boolean cancelled(long lease) {
    return stopped || lease != generation;
  }

  public synchronized boolean stopped() {
    return stopped;
  }

  public synchronized long generation() {
    return generation;
  }

  public synchronized void stop() {
    stopped = true;
    generation++;
  }

  public synchronized boolean acknowledge(long revokedGeneration) {
    if (!stopped || generation != revokedGeneration) return false;
    generation++;
    stopped = false;
    return true;
  }
}
