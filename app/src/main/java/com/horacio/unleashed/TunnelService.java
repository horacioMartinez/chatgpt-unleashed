package com.horacio.unleashed;

import android.app.*;
import android.content.*;
import android.os.Build;
import android.os.IBinder;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Outbound-only private MCP connection. No inbound Internet listener or PC dependency. */
public class TunnelService extends Service {
  public static volatile String state = "Not connected to ChatGPT";
  public static volatile boolean active;
  public static volatile boolean connectedToChatGPT;
  public static volatile boolean connectionRejected;
  private static volatile TunnelService live;

  static void startAccess(Context context) {
    if (!new TunnelVault(context).configured())
      throw new IllegalStateException("Save tunnel credentials first");
    PhoneAccessPreferences.setEnabled(context, true);
    connectionRejected = false;
    context.startForegroundService(new Intent(context, TunnelService.class).setAction("start"));
  }

  static void stopAccess(Context context) {
    PhoneAccessPreferences.setEnabled(context, false);
    context.stopService(new Intent(context, TunnelService.class));
  }

  public static void taskStateChanged() {
    TunnelService service = live;
    if (service != null) service.ui.post(service::refreshPresentation);
  }

  /** Remove our touchable window only when it intersects the injected gesture. */
  static <T> T withUnobstructedInput(int x, int y, int endX, int endY, Callable<T> input)
      throws Exception {
    TunnelService service = live;
    if (service == null) return input.call();
    // Acquisition and release are both queued on the main thread, even on timeout/interruption.
    // This keeps a late acquisition from leaving an orphaned pause behind.
    java.util.concurrent.atomic.AtomicBoolean suppressed =
        new java.util.concurrent.atomic.AtomicBoolean();
    try {
      CompletableFuture<Void> prepared = new CompletableFuture<>();
      service.ui.post(
          () -> {
            service.refreshPresentation();
            // Join an existing pause so concurrent input cannot restore the badge too early.
            if (service.overlayInputPauses.get() > 0
                || (service.overlay != null
                    && service.overlay.obstructsGesture(x, y, endX, endY))) {
              service.overlayInputPauses.incrementAndGet();
              suppressed.set(true);
              if (service.overlay != null) service.overlay.close();
              // Allow Android to publish the removed window before DOWN.
              service.ui.postDelayed(() -> prepared.complete(null), 100);
            } else prepared.complete(null);
          });
      prepared.get(2, TimeUnit.SECONDS);
      return input.call();
    } finally {
      service.ui.post(
          () -> {
            if (suppressed.get()) service.overlayInputPauses.decrementAndGet();
            service.refreshPresentation();
          });
    }
  }

  private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
  private TaskStatusOverlay overlay;
  private final java.util.concurrent.atomic.AtomicInteger overlayInputPauses =
      new java.util.concurrent.atomic.AtomicInteger();
  private volatile String connectionState = "Starting private connection…";
  private String lastNotification;
  private final Runnable indicatorTick =
      new Runnable() {
        public void run() {
          if (!running) return;
          refreshPresentation();
          ui.postDelayed(this, 1000);
        }
      };
  private volatile boolean running;
  private final Set<HttpsURLConnection> requests = ConcurrentHashMap.newKeySet();
  private final ExecutorService poller = Executors.newSingleThreadExecutor();
  private final ExecutorService calls = Executors.newFixedThreadPool(3);
  private final Set<String> inflight = ConcurrentHashMap.newKeySet();
  private final LinkedHashMap<String, JSONObject> completed = new LinkedHashMap<>();
  private int completedBytes;
  private String tunnel, key;
  private final String instance = UUID.randomUUID().toString();
  private final McpRouter router = new McpRouter(new AppMcpBackend(), Diagnostics::record);

  @Override
  public void onCreate() {
    super.onCreate();
    live = this;
    overlay = new TaskStatusOverlay(this, this::stopCurrentTask);
    BackendController.init(this);
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel(
            "chatgpt-tunnel", "ChatGPT phone connection", NotificationManager.IMPORTANCE_LOW));
    // Foreground services still supply a notification to Android. The APK deliberately
    // does not request POST_NOTIFICATIONS, so Android 13+ keeps it out of the drawer.
    startForeground(2, notification());
  }

  private Notification notification() {
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            4,
            new Intent(this, TunnelActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            5,
            new Intent(this, TunnelService.class).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder notification =
        new Notification.Builder(this, "chatgpt-tunnel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.phone_access_notification_title))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "Stop phone access", stop).build());
    if (AppMcpBackend.authenticationWaiting()) {
      notification.setContentText(getString(R.string.phone_access_notification_unlock));
      PendingIntent cancel =
          PendingIntent.getService(
              this,
              6,
              new Intent(this, TunnelService.class).setAction("cancel_auth_wait"),
              PendingIntent.FLAG_IMMUTABLE);
      notification.addAction(
          new Notification.Action.Builder(null, "Cancel waiting task", cancel).build());
    }
    return notification.build();
  }

  private void stopCurrentTask() {
    AppMcpBackend.stopPhoneTask();
    overlay.close();
    // Keep polling and delivering results: ChatGPT must receive the cancellation rather than
    // mistake a disconnected tool for an error to retry. Only the current task is revoked.
    new Thread(
            () -> {
              try {
                PhoneAccessibilityService.cancelPending();
                AppMcpBackend.restoreAfterCancellation();
              } catch (Exception error) {
                Diagnostics.record(
                    DiagnosticEvent.problem(
                        DiagnosticEvent.Area.TOOL,
                        DiagnosticEvent.Operation.FINISH_TASK,
                        error,
                        DiagnosticEvent.newId()));
              }
            },
            "stop-current-phone-task")
        .start();
  }

  private void update(String value) {
    connectionState = value;
    ui.post(this::refreshPresentation);
  }

  private void refreshPresentation() {
    if (!running) return;
    PhoneTaskState.Status task = AppMcpBackend.taskStatus();
    String value =
        task == PhoneTaskState.Status.IDLE ? connectionState : TaskStatusOverlay.label(this, task);
    state = value;
    if (overlayInputPauses.get() > 0) overlay.close();
    else overlay.render(task);
    String notice = AppMcpBackend.authenticationWaiting() ? "unlock" : "active";
    NotificationManager manager = getSystemService(NotificationManager.class);
    if (Build.VERSION.SDK_INT < 33
        && manager.areNotificationsEnabled()
        && !notice.equals(lastNotification)) {
      manager.notify(2, notification());
      lastNotification = notice;
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int id) {
    if (intent != null && "cancel_auth_wait".equals(intent.getAction())) {
      AppMcpBackend.cancelAuthentication();
      if (!running) stopSelf();
      return running ? START_STICKY : START_NOT_STICKY;
    }
    if (intent != null && "stop".equals(intent.getAction())) {
      PhoneAccessPreferences.setEnabled(this, false);
      stopSelf();
      return START_NOT_STICKY;
    }
    // Explicit starts go through startAccess(); boot/update/sticky starts never re-enable access.
    if (!PhoneAccessPreferences.enabled(this) || !new TunnelVault(this).configured()) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (running) return START_STICKY;
    running = true;
    active = true;
    connectedToChatGPT = false;
    connectionRejected = false;
    BackendController.resumeForAccount();
    ui.post(indicatorTick);
    poller.execute(
        () -> {
          try {
            TunnelVault vault = new TunnelVault(this);
            if (!vault.configured()) throw new IOException("configure");
            tunnel = vault.tunnel();
            key = vault.key();
          } catch (Exception error) {
            Diagnostics.record(
                DiagnosticEvent.event(
                    DiagnosticEvent.Area.TUNNEL,
                    DiagnosticEvent.Operation.START,
                    DiagnosticEvent.Outcome.ERROR,
                    DiagnosticEvent.Code.CONNECTION_UNAVAILABLE));
            state = "Configure a tunnel and runtime key first";
            connectionRejected = true;
            stopSelf();
            return;
          }
          int failures = 0;
          boolean connected = false;
          while (running) {
            try {
              HttpResult response = http("GET", "/poll?limit=1&timeout_ms=15000", null, null);
              if (!running) break;
              if (response.code != 200 && response.code != 204)
                throw new HttpFailure(response.code);
              if (!connected || failures > 0)
                Diagnostics.record(
                    DiagnosticEvent.event(
                        DiagnosticEvent.Area.TUNNEL,
                        DiagnosticEvent.Operation.CONNECT,
                        DiagnosticEvent.Outcome.SUCCESS,
                        DiagnosticEvent.Code.NONE));
              connected = true;
              connectedToChatGPT = true;
              failures = 0;
              update("Connected • waiting for ChatGPT");
              if (response.code == 204) continue;
              JSONArray commands = new JSONObject(response.body).getJSONArray("commands");
              for (int i = 0; i < commands.length() && i < 8; i++) {
                JSONObject command = commands.getJSONObject(i);
                String requestId = command.getString("request_id");
                if (!inflight.add(requestId)) continue;
                calls.execute(() -> process(command, requestId));
              }
            } catch (Exception error) {
              if (!running) break;
              connectedToChatGPT = false;
              recordTransportFailure(
                  DiagnosticEvent.Operation.POLL, error, DiagnosticEvent.newId());
              if (error instanceof HttpFailure
                  && (((HttpFailure) error).status == 401
                      || ((HttpFailure) error).status == 403
                      || ((HttpFailure) error).status == 404)) {
                state =
                    "Connection rejected (HTTP "
                        + ((HttpFailure) error).status
                        + "). Check the key, tunnel and workspace permissions.";
                connectionRejected = true;
                stopSelf();
                break;
              }
              failures++;
              update("Network connection interrupted; retrying…");
              try {
                Thread.sleep(Math.min(30000, 1000L << Math.min(failures, 5)));
              } catch (InterruptedException stop) {
                break;
              }
            }
          }
        });
    return START_STICKY;
  }

  private void process(JSONObject command, String requestId) {
    String trace = DiagnosticEvent.newId();
    try {
      if (!running) return;
      JSONObject response;
      synchronized (completed) {
        response = completed.get(requestId);
      }
      if (response == null) {
        // Don't execute work that was queued longer than the tunnel's absolute budget.
        String created = command.optString("created_at");
        if (!created.isEmpty()
            && System.currentTimeMillis() - java.time.Instant.parse(created).toEpochMilli()
                > 120000) {
          Diagnostics.record(
              DiagnosticEvent.event(
                  DiagnosticEvent.Area.TUNNEL,
                  DiagnosticEvent.Operation.PROCESS_REQUEST,
                  DiagnosticEvent.Outcome.ERROR,
                  DiagnosticEvent.Code.RESPONSE_EXPIRED));
          return;
        }
        response = TunnelProtocol.response(command, router, trace);
        synchronized (completed) {
          int size = response.toString().length() * 2;
          if (size < 12 * 1024 * 1024) {
            completed.put(requestId, response);
            completedBytes += size;
            while (completed.size() > 32 || completedBytes > 12 * 1024 * 1024) {
              String first = completed.keySet().iterator().next();
              completedBytes -= completed.remove(first).toString().length() * 2;
            }
          }
        }
      }
      long deliveryStarted = System.nanoTime();
      Diagnostics.record(
          DiagnosticEvent.started(
              DiagnosticEvent.Area.TUNNEL, DiagnosticEvent.Operation.DELIVER_RESPONSE, trace, ""));
      for (int attempt = 0; attempt < 3 && running; attempt++) {
        try {
          HttpResult posted =
              http("POST", "/response", response.toString(), command.getString("shard_token"));
          if (posted.code == 200 || posted.code == 204) {
            Diagnostics.record(
                DiagnosticEvent.completed(
                    DiagnosticEvent.Area.TUNNEL,
                    DiagnosticEvent.Operation.DELIVER_RESPONSE,
                    trace,
                    "",
                    (System.nanoTime() - deliveryStarted) / 1_000_000L,
                    null,
                    null));
            break;
          }
          Diagnostics.record(
              DiagnosticEvent.http(DiagnosticEvent.Operation.DELIVER_RESPONSE, posted.code, trace));
          if (posted.code == 404) break;
          if (posted.code == 401 || posted.code == 403) {
            update("Response rejected; check runtime permissions");
            break;
          }
        } catch (IOException error) {
          recordTransportFailure(DiagnosticEvent.Operation.DELIVER_RESPONSE, error, trace);
          if (attempt == 2) update("Could not deliver tool result; check network");
        }
        Thread.sleep(500L * (attempt + 1));
      }
    } catch (Exception error) {
      if (running) recordTransportFailure(DiagnosticEvent.Operation.PROCESS_REQUEST, error, trace);
      if (running) update("Tool request could not be processed");
    } finally {
      inflight.remove(requestId);
    }
  }

  private static final class HttpFailure extends IOException {
    final int status;

    HttpFailure(int status) {
      this.status = status;
    }
  }

  private static void recordTransportFailure(
      DiagnosticEvent.Operation operation, Exception error, String trace) {
    Diagnostics.record(
        error instanceof HttpFailure
            ? DiagnosticEvent.http(operation, ((HttpFailure) error).status, trace)
            : DiagnosticEvent.problem(DiagnosticEvent.Area.TUNNEL, operation, error, trace));
  }

  private static final class HttpResult {
    final int code;
    final String body;

    HttpResult(int code, String body) {
      this.code = code;
      this.body = body;
    }
  }

  private HttpResult http(String method, String suffix, String body, String shard)
      throws IOException {
    // Destination is fixed; redirect responses never receive the runtime credential.
    HttpsURLConnection connection =
        (HttpsURLConnection)
            new URL("https://api.openai.com/v1/tunnels/" + tunnel + suffix).openConnection();
    requests.add(connection);
    try {
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(30000);
      connection.setRequestMethod(method);
      connection.setRequestProperty("Authorization", "Bearer " + key);
      connection.setRequestProperty("Accept", "application/json");
      connection.setRequestProperty("User-Agent", "unleashed-app/" + BuildConfig.VERSION_NAME);
      connection.setRequestProperty("x-tunnel-client-name", "unleashed-app-android");
      connection.setRequestProperty("x-tunnel-client-version", BuildConfig.VERSION_NAME);
      connection.setRequestProperty("x-tunnel-client-wire-protocol-version", "2026-08-25");
      connection.setRequestProperty("x-tunnel-client-instance-id", instance);
      connection.setRequestProperty(
          "x-tunnel-mcp-server-info",
          "{\"version\":2,\"channels\":[{\"name\":\"main\",\"stateless\":true,\"proc_affinity\":true}]}");
      if (shard != null) connection.setRequestProperty("x-tunnel-shard-token", shard);
      if (body != null) {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > 9 * 1024 * 1024) throw new IOException("Response too large");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
          output.write(bytes);
        }
      }
      int code = connection.getResponseCode();
      String payload = "";
      if (code == 200) {
        try (InputStream input = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream()) {
          byte[] block = new byte[8192];
          int count;
          while ((count = input.read(block)) != -1) {
            if (output.size() + count > 10 * 1024 * 1024)
              throw new IOException("Response too large");
            output.write(block, 0, count);
          }
          payload = new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
      }
      return new HttpResult(code, payload);
    } finally {
      requests.remove(connection);
      connection.disconnect();
    }
  }

  @Override
  public void onDestroy() {
    if (live == this) live = null;
    running = false;
    active = false;
    connectedToChatGPT = false;
    PhoneAccessibilityService.cancelPending();
    AppMcpBackend.cancelAuthentication();
    AppMcpBackend.resetTaskStatus();
    ui.removeCallbacksAndMessages(null);
    if (overlay != null) overlay.close();
    for (HttpsURLConnection request : requests) request.disconnect();
    poller.shutdownNow();
    calls.shutdownNow();
    key = null;
    state = state.startsWith("Connection rejected") ? state : "ChatGPT access stopped";
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
