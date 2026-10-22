package com.horacio.unleashed;

import org.json.*;

public final class AppMcpBackend implements McpRouter.Backend {
  private static final PhoneTaskState TASK = new PhoneTaskState();
  private static final PhoneTaskControl CONTROL = new PhoneTaskControl();
  private static final java.util.Map<Thread, Long> activeCalls =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final ThreadLocal<Long> lease = new ThreadLocal<>();

  private Runnable backendGuard() {
    long token = requireControl();
    return () -> {
      CONTROL.require(token);
      if (!BackendController.allowed())
        throw new java.util.concurrent.CancellationException("Phone backend paused");
    };
  }

  static void restoreAfterCancellation() throws Exception {
    long generation = CONTROL.generation();
    BackendController.service()
        .returnToChatGPT(
            () -> {
              if (!CONTROL.stopped()
                  || CONTROL.generation() != generation
                  || !BackendController.allowed())
                throw new java.util.concurrent.CancellationException(
                    "Cancellation navigation superseded");
            });
  }

  static void accessibilityAuthenticationChanged() {
    PhoneAccessibilityService service = PhoneAccessibilityService.current();
    if (service == null || !BackendController.allowed()) return;
    try {
      observeAccessibility(service.readScreen(() -> {}));
    } catch (Exception ignored) {
      /* A missing or protected screen is not evidence of unlock. */
    }
  }

  private static JSONObject observeAccessibility(JSONObject screen) throws Exception {
    AUTH.observeAccessibility(screen);
    screen.remove("device");
    JSONObject authentication = AUTH.state();
    PhoneAccessibilityService service = PhoneAccessibilityService.current();
    if (service != null) service.scheduleAuthenticationCheck(AUTH.transitionCheckDelayMillis());
    screen
        .put("authentication", authentication)
        .put("keepWaiting", authentication.optBoolean("keepWaiting"));
    if (authentication.optBoolean("keepWaiting")) {
      screen.put("status", authentication.getString("status"));
      screen.put("note", AuthenticationGate.guidance(authentication));
    }
    return screen;
  }

  private static final AuthenticationGate AUTH =
      new AuthenticationGate(AppMcpBackend::authenticationChanged);

  // Revoke calls before interrupting pending input so cancellation is immediate.
  static void stopPhoneTask() {
    synchronized (CONTROL) {
      CONTROL.stop();
      AUTH.cancel();
      TASK.reset();
    }
    for (java.util.Map.Entry<Thread, Long> call : activeCalls.entrySet())
      if (CONTROL.cancelled(call.getValue())) call.getKey().interrupt();
    PhoneAccessibilityService.cancelPending();
    TunnelService.taskStateChanged();
  }

  // phone_finish_task acknowledges cancellation and establishes the boundary for the next task.
  // In-flight calls keep their old leases and cannot resume when this boundary is cleared.
  public void acknowledgeTaskCancellation() {
    synchronized (CONTROL) {
      if (!CONTROL.stopped()) return;
      long generation = CONTROL.generation();
      AUTH.clear();
      TASK.reset();
      CONTROL.acknowledge(generation);
    }
    TunnelService.taskStateChanged();
  }

  public JSONObject taskCancellation() throws org.json.JSONException {
    Long current = lease.get();
    if (!CONTROL.stopped() && (current == null || !CONTROL.cancelled(current))) return null;
    return new JSONObject()
        .put("status", "cancelled")
        .put("cancelled", true)
        .put("finished", false)
        .put("keepWaiting", false)
        .put(
            "nextAction",
            "The owner pressed Cancel for the current phone task. Do not continue or retry that"
                + " task, including through alternative tools. Call phone_finish_task to"
                + " acknowledge cancellation, then tell the owner it stopped. The connection stays"
                + " enabled for future explicit requests. If the owner has already given a newer"
                + " request, acknowledge the old cancellation first, then perform only that new"
                + " request. Actions already completed are not undone.");
  }

  private long requireControl() {
    Long current = lease.get();
    long token = current == null ? CONTROL.begin() : current;
    CONTROL.require(token);
    return token;
  }

  private static void authenticationChanged() {
    if (AUTH.cancelled()) TASK.finish();
    TunnelService.taskStateChanged();
  }

  static PhoneTaskState.Status taskStatus() {
    if (CONTROL.stopped() || AUTH.cancelled()) return PhoneTaskState.Status.IDLE;
    PhoneTaskState.Status status =
        TASK.status(android.os.SystemClock.elapsedRealtime(), AUTH.waiting());
    return status == PhoneTaskState.Status.WAITING_FOR_AUTHENTICATION && !AUTH.indicatorWaiting()
        ? PhoneTaskState.Status.WORKING
        : status;
  }

  static void resetTaskStatus() {
    TASK.reset();
  }

  public void toolStarted() {
    synchronized (CONTROL) {
      long token = CONTROL.begin();
      lease.set(token);
      activeCalls.put(Thread.currentThread(), token);
      TASK.begin(android.os.SystemClock.elapsedRealtime());
    }
    TunnelService.taskStateChanged();
  }

  public void toolCompleted() {
    synchronized (CONTROL) {
      Long current = lease.get();
      if (current != null && !CONTROL.cancelled(current))
        TASK.end(android.os.SystemClock.elapsedRealtime());
    }
    lease.remove();
    activeCalls.remove(Thread.currentThread());
    // The tunnel worker must still deliver the cancellation result and accept future work.
    Thread.interrupted();
    TunnelService.taskStateChanged();
  }

  public String diagnosticTaskId() {
    return TASK.taskId();
  }

  public void toolFailed() {
    if (!AUTH.waiting())
      TASK.toolFailed(BackendController.state() == ConnectionState.Backend.RUNNING);
    TunnelService.taskStateChanged();
  }

  static boolean authenticationWaiting() {
    return AUTH.waiting();
  }

  static void cancelAuthentication() {
    AUTH.cancel();
  }

  public JSONObject authenticationStatus() throws Exception {
    observeAccessibility(BackendController.service().readScreen(backendGuard()));
    return AUTH.state();
  }

  public JSONObject waitForAuthentication(int seconds, String expectedPackage) throws Exception {
    requireControl();
    return AUTH.waitFor(
        seconds,
        expectedPackage,
        () -> {
          try {
            return readScreen();
          } catch (Exception error) {
            Diagnostics.record(
                DiagnosticEvent.problem(
                    DiagnosticEvent.Area.TOOL,
                    DiagnosticEvent.Operation.READ_SCREEN,
                    error,
                    DiagnosticEvent.newId()));
            throw error;
          }
        },
        android.os.SystemClock::elapsedRealtime,
        Thread::sleep);
  }

  public JSONObject cancelAuthenticationWait() throws Exception {
    AUTH.cancel();
    return AUTH.state();
  }

  public void authenticationTaskFinished() {
    requireControl();
    TASK.finish();
    AUTH.clear();
    TunnelService.taskStateChanged();
  }

  @Override
  public JSONObject action(JSONObject args) throws Exception {
    PhoneAction.validate(args);
    String action = args.getString("action");
    if (action.equals("tap") || action.equals("swipe")) {
      int x = args.getInt("x"), y = args.getInt("y");
      int endX = action.equals("swipe") ? args.getInt("endX") : x;
      int endY = action.equals("swipe") ? args.getInt("endY") : y;
      return TunnelService.withUnobstructedInput(x, y, endX, endY, () -> performAction(args));
    }
    return performAction(args);
  }

  private JSONObject performAction(JSONObject args) throws Exception {
    return BackendController.service().action(args, backendGuard());
  }

  public JSONObject returnToChatGPT() throws Exception {
    return BackendController.service().returnToChatGPT(backendGuard());
  }

  public JSONObject status() throws Exception {
    return new JSONObject()
        .put("app", "unleashed")
        .put("backendState", BackendController.state().name())
        .put("connected", BackendController.state() == ConnectionState.Backend.RUNNING)
        .put("connection", BackendController.status())
        .put(
            "capabilities",
            new JSONObject()
                .put("readScreen", true)
                .put("gestures", true)
                .put("screenshots", true)
                .put("unicodeText", true)
                .put("protectedScreens", false));
  }

  public JSONObject readScreen() throws Exception {
    return observeAccessibility(BackendController.service().readScreen(backendGuard()));
  }

  public String screenshotBase64() throws Exception {
    return BackendController.service().screenshot(backendGuard());
  }
}
