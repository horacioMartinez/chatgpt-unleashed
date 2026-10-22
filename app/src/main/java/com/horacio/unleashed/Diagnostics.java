package com.horacio.unleashed;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Process-wide diagnostics writer. Failures here never interrupt phone control. */
public final class Diagnostics {
  private static DiagnosticStore store;
  private static boolean initialized;
  private static volatile boolean available = true;
  private static final AtomicInteger dropped = new AtomicInteger();
  private static final ExecutorService writer =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(256),
          runnable -> new Thread(runnable, "phone-diagnostics"));

  public static synchronized void init(Context context) {
    if (initialized) return;
    initialized = true;
    try {
      store = new DiagnosticStore(new File(context.getFilesDir(), "diagnostics"));
    } catch (Exception error) {
      available = false;
    }
    Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          try {
            if (store != null)
              store.append(
                  DiagnosticEvent.event(
                      DiagnosticEvent.Area.APP,
                      DiagnosticEvent.Operation.PROCESS_REQUEST,
                      DiagnosticEvent.Outcome.ERROR,
                      DiagnosticEvent.Code.UNCAUGHT_EXCEPTION));
          } catch (Throwable cannotRecord) {
            /* Preserve Android's normal crash handling. */
          }
          if (previous != null) previous.uncaughtException(thread, error);
        });
    record(
        DiagnosticEvent.event(
            DiagnosticEvent.Area.APP,
            DiagnosticEvent.Operation.START,
            DiagnosticEvent.Outcome.SUCCESS,
            DiagnosticEvent.Code.NONE));
  }

  public static void record(DiagnosticEvent event) {
    if (store == null) return;
    try {
      writer.execute(
          () -> {
            try {
              store.append(event);
              available = true;
            } catch (Exception cannotWrite) {
              available = false;
              dropped.incrementAndGet();
            }
          });
    } catch (RejectedExecutionException full) {
      dropped.incrementAndGet();
      available = false;
    }
  }

  private static <T> CompletableFuture<T> submit(Callable<T> task) {
    CompletableFuture<T> result = new CompletableFuture<>();
    try {
      writer.execute(
          () -> {
            try {
              if (store == null) throw new java.io.IOException("Diagnostics storage unavailable");
              result.complete(task.call());
            } catch (Exception error) {
              available = false;
              result.completeExceptionally(error);
            }
          });
    } catch (RejectedExecutionException full) {
      result.completeExceptionally(full);
    }
    return result;
  }

  public static CompletableFuture<JSONArray> recent(boolean errorsOnly) {
    return submit(() -> store.read(errorsOnly, 200));
  }

  public static CompletableFuture<Void> clear() {
    return submit(
        () -> {
          store.clear();
          dropped.set(0);
          available = true;
          return null;
        });
  }

  public static CompletableFuture<JSONObject> report() {
    return submit(
        () ->
            new JSONObject()
                .put("schemaVersion", 1)
                .put("generatedAt", java.time.Instant.now().toString())
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("androidVersion", Build.VERSION.RELEASE)
                .put("androidSdk", Build.VERSION.SDK_INT)
                .put("deviceModel", Build.MODEL)
                .put("backendState", BackendController.state().name())
                .put("tunnelActive", TunnelService.active)
                .put("loggingAvailable", available)
                .put("droppedEvents", dropped.get())
                .put("maximumHistoryBytes", DiagnosticStore.FILE_LIMIT * 2)
                .put("events", store.read(false, 0)));
  }

  public static boolean available() {
    return available;
  }

  public static int droppedEvents() {
    return dropped.get();
  }
}
