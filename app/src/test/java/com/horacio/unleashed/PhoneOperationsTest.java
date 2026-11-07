package com.horacio.unleashed;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;

public class PhoneOperationsTest {
  private static JSONObject call(McpRouter router, String name, JSONObject args) throws Exception {
    return router
        .handle(
            McpRouterTest.request("tools/call")
                .put("params", new JSONObject().put("name", name).put("arguments", args)))
        .getJSONObject("result");
  }

  private static JSONObject tap() throws Exception {
    return new JSONObject().put("action", "tap").put("x", 20).put("y", 20);
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    assertTrue("Operation did not reach the expected stage", latch.await(2, TimeUnit.SECONDS));
  }

  @Test(timeout = 5000)
  public void separateRoutersSerializeAuthenticationActionObservationAndCompletion() throws Exception {
    CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
    CountDownLatch queued = new CountDownLatch(1), secondAuthentication = new CountDownLatch(1);
    List<String> order = Collections.synchronizedList(new ArrayList<>());
    McpRouter first =
        new McpRouter(
            new McpRouterTest.Fake() {
              public JSONObject authenticationStatus() throws Exception {
                order.add("first authentication");
                return super.authenticationStatus();
              }

              public JSONObject action(JSONObject args) throws Exception {
                order.add("first action");
                return super.action(args);
              }

              public JSONObject readScreen() throws Exception {
                order.add("first observation");
                reading.countDown();
                await(release);
                return super.readScreen();
              }

              public void toolCompleted() {
                order.add("first completion");
                super.toolCompleted();
              }
            });
    McpRouter second =
        new McpRouter(
            new McpRouterTest.Fake() {
              public void toolStarted() {
                super.toolStarted();
                queued.countDown();
              }

              public JSONObject authenticationStatus() throws Exception {
                order.add("second authentication");
                secondAuthentication.countDown();
                return super.authenticationStatus();
              }
            });
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<JSONObject> one = workers.submit(() -> call(first, "phone_action", tap()));
      await(reading);
      Future<JSONObject> two = workers.submit(() -> call(second, "phone_action", tap()));
      await(queued);
      assertFalse(secondAuthentication.await(100, TimeUnit.MILLISECONDS));
      release.countDown();
      assertFalse(one.get(2, TimeUnit.SECONDS).getBoolean("isError"));
      assertFalse(two.get(2, TimeUnit.SECONDS).getBoolean("isError"));
      assertEquals(
          List.of(
              "first authentication",
              "first action",
              "first observation",
              "first completion",
              "second authentication"),
          order);
    } finally {
      release.countDown();
      workers.shutdownNow();
    }
  }

  @Test(timeout = 5000)
  public void queuedCallCannotRunAfterCancellationIsAcknowledged() throws Exception {
    PhoneTaskControl control = new PhoneTaskControl();
    CountDownLatch queued = new CountDownLatch(1);
    McpRouterTest.Fake backend =
        new McpRouterTest.Fake() {
          private final ThreadLocal<Long> lease = new ThreadLocal<>();

          public void toolStarted() {
            lease.set(control.begin());
            super.toolStarted();
            queued.countDown();
          }

          public JSONObject taskCancellation() throws Exception {
            Long token = lease.get();
            if (control.stopped() || (token != null && control.cancelled(token)))
              return new JSONObject().put("status", "cancelled");
            return null;
          }
        };
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      Future<JSONObject> pending;
      PhoneOperations.acquire();
      try {
        pending = worker.submit(() -> call(new McpRouter(backend), "phone_action", tap()));
        await(queued);
        control.stop();
        assertTrue(control.acknowledge(control.generation()));
      } finally {
        PhoneOperations.release();
      }
      JSONObject result = pending.get(2, TimeUnit.SECONDS);
      JSONObject value =
          new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
      assertEquals("cancelled", value.getString("status"));
      assertEquals(0, backend.actions);
      assertEquals(1, backend.completed);
    } finally {
      worker.shutdownNow();
    }
  }

  @Test(timeout = 5000)
  public void cancelledWorkerCanLeaveTheQueueBeforeTheCurrentOperationFinishes() throws Exception {
    CountDownLatch queued = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<Thread> thread =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicBoolean ownerCancelled =
        new java.util.concurrent.atomic.AtomicBoolean();
    McpRouterTest.Fake backend =
        new McpRouterTest.Fake() {
          public void toolStarted() {
            super.toolStarted();
            thread.set(Thread.currentThread());
            queued.countDown();
          }

          public JSONObject taskCancellation() throws Exception {
            return ownerCancelled.get() ? new JSONObject().put("status", "cancelled") : null;
          }
        };
    ExecutorService worker = Executors.newSingleThreadExecutor();
    PhoneOperations.acquire();
    try {
      Future<JSONObject> pending =
          worker.submit(() -> call(new McpRouter(backend), "phone_action", tap()));
      await(queued);
      ownerCancelled.set(true);
      thread.get().interrupt();
      // Cancellation returns even though this thread still owns the phone-operation lock.
      JSONObject result = pending.get(2, TimeUnit.SECONDS);
      JSONObject value =
          new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
      assertEquals("cancelled", value.getString("status"));
      assertEquals(0, backend.actions);
      assertEquals(1, backend.completed);
    } finally {
      PhoneOperations.release();
      worker.shutdownNow();
    }
  }

  @Test(timeout = 5000)
  public void authenticationWaitDoesNotBlockOtherCallsOrItsCancellation() throws Exception {
    CountDownLatch waiting = new CountDownLatch(1), cancel = new CountDownLatch(1);
    McpRouter waiter =
        new McpRouter(
            new McpRouterTest.Fake() {
              public JSONObject waitForAuthentication(int seconds, String owner) throws Exception {
                waiting.countDown();
                await(cancel); // Represents sleeping between probes, with no phone lock held.
                return new JSONObject().put("status", "cancelled");
              }

              public JSONObject cancelAuthenticationWait() throws Exception {
                cancel.countDown();
                return new JSONObject().put("status", "cancelled");
              }
            });
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      Future<JSONObject> pending =
          worker.submit(() -> call(waiter, "phone_wait_for_authentication", new JSONObject()));
      await(waiting);
      JSONObject read =
          call(new McpRouter(new McpRouterTest.Fake()), "phone_read_screen", new JSONObject());
      assertFalse(read.getBoolean("isError"));
      assertFalse(pending.isDone());
      assertFalse(
          call(waiter, "phone_wait_for_authentication", new JSONObject().put("cancel", true))
              .getBoolean("isError"));
      assertFalse(pending.get(2, TimeUnit.SECONDS).getBoolean("isError"));
    } finally {
      cancel.countDown();
      worker.shutdownNow();
    }
  }

  @Test(timeout = 5000)
  public void authenticationProbeWaitsForTheCompleteCurrentOperation() throws Exception {
    CountDownLatch attempting = new CountDownLatch(1), probed = new CountDownLatch(1);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      Future<JSONObject> pending;
      PhoneOperations.acquire();
      try {
        pending =
            worker.submit(
                () -> {
                  attempting.countDown();
                  return PhoneOperations.run(
                      () -> {
                        probed.countDown();
                        return new JSONObject();
                      });
                });
        await(attempting);
        assertFalse(probed.await(100, TimeUnit.MILLISECONDS));
      } finally {
        PhoneOperations.release();
      }
      pending.get(2, TimeUnit.SECONDS);
      assertEquals(0, probed.getCount());
    } finally {
      worker.shutdownNow();
    }
  }

  @Test(timeout = 5000)
  public void failedOperationReleasesThePhoneForAnotherWorker() throws Exception {
    McpRouter broken =
        new McpRouter(
            new McpRouterTest.Fake() {
              public JSONObject action(JSONObject args) {
                throw new IllegalStateException("Gesture failed");
              }
            });
    assertTrue(call(broken, "phone_action", tap()).getBoolean("isError"));
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      JSONObject result =
          worker
              .submit(() -> call(new McpRouter(new McpRouterTest.Fake()), "phone_action", tap()))
              .get(2, TimeUnit.SECONDS);
      assertFalse(result.getBoolean("isError"));
    } finally {
      worker.shutdownNow();
    }
  }
}
