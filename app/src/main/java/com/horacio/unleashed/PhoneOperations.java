package com.horacio.unleashed;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

/** One visible phone shared by every transport. Never acquire this lock on Android's main thread. */
final class PhoneOperations {
  private static final ReentrantLock lock = new ReentrantLock(true);

  static void acquire() throws InterruptedException {
    lock.lockInterruptibly();
  }

  static void release() {
    lock.unlock();
  }

  static <T> T run(Callable<T> operation) throws Exception {
    acquire();
    try {
      return operation.call();
    } finally {
      release();
    }
  }
}
