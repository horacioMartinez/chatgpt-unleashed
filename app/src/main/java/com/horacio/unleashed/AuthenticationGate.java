package com.horacio.unleashed;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** A human authentication pause, shared across MCP calls. Never supplies credentials or input. */
public final class AuthenticationGate {
  public interface Probe {
    JSONObject read() throws Exception;
  }

  public interface Sleeper {
    void sleep(long millis) throws InterruptedException;
  }

  private final Runnable changed;
  static final long INDICATOR_DELAY_MS = 3000;
  private final LongSupplier clock;
  private long requiredSince;
  private long transitionSince;
  private boolean transitionPending;
  private String transitionPackage = "";
  private boolean required, cancelled;
  private boolean assumedAuthentication;
  private String expectedPackage = "";

  public AuthenticationGate(Runnable changed) {
    this(changed, () -> System.nanoTime() / 1_000_000L);
  }

  AuthenticationGate(Runnable changed, LongSupplier clock) {
    this.changed = changed;
    this.clock = clock;
  }

  public synchronized void require(String owner) {
    if (cancelled) return;
    resetTransition();
    boolean notify = !required;
    if (notify) requiredSince = clock.getAsLong();
    required = true;
    assumedAuthentication = false;
    if ((notify || expectedPackage.isEmpty())
        && owner != null
        && !owner.isEmpty()
        && !owner.equals("com.android.systemui")) expectedPackage = owner;
    if (notify) changed.run();
  }

  public synchronized void cancel() {
    if (!required && !transitionPending) return;
    resetTransition();
    required = false;
    cancelled = true;
    changed.run();
  }

  public synchronized void clear() {
    resetTransition();
    required = false;
    cancelled = false;
    expectedPackage = "";
    assumedAuthentication = false;
    changed.run();
  }

  public synchronized JSONObject state() throws org.json.JSONException {
    return new JSONObject()
        .put("required", required)
        .put("cancelled", cancelled)
        .put(
            "status",
            cancelled
                ? "cancelled"
                : required
                    ? "waiting_for_user_authentication"
                    : transitionPending ? "waiting_for_screen" : "ready")
        .put("expectedPackage", transitionPending ? transitionPackage : expectedPackage)
        .put("transitionPending", transitionPending)
        .put("keepWaiting", (required || transitionPending) && !cancelled)
        .put("assumedAuthentication", assumedAuthentication)
        .put(
            "reason",
            assumedAuthentication
                ? "inaccessible_screen_assumed_authentication"
                : transitionPending ? "screen_transition" : "")
        .put(
            "nextAction",
            cancelled
                ? "End this task; do not continue protected operations."
                : transitionPending
                    ? transitionInstructions()
                    : required
                        ? instructions()
                        : "Continue the original task using a fresh screen observation.");
  }

  public synchronized boolean waiting() {
    return required;
  }

  /** Inferred waits include the transition grace; confirmed prompts retain the label delay. */
  public synchronized boolean indicatorWaiting() {
    return required && clock.getAsLong() - requiredSince >= INDICATOR_DELAY_MS;
  }

  public synchronized boolean cancelled() {
    return cancelled;
  }

  synchronized long transitionCheckDelayMillis() {
    return transitionPending
        ? Math.max(1, INDICATOR_DELAY_MS - (clock.getAsLong() - transitionSince))
        : -1;
  }

  private void resetTransition() {
    transitionPending = false;
    transitionPackage = "";
    transitionSince = 0;
  }

  /** Brief gaps are transitions; only persistent inaccessibility becomes an inferred unlock. */
  public synchronized void observeAccessibility(JSONObject screen) {
    if (cancelled) return;
    JSONObject device = screen.optJSONObject("device");
    boolean blocked =
        device != null && (device.optBoolean("deviceLocked") || device.optBoolean("promptVisible"));
    String app = screen.optString("appPackage");
    String target = screen.optString("targetPackage", app);
    if (blocked || looksLikeAuthentication(screen)) {
      // Positive authentication evidence can identify the destination after an inferred gap.
      if (assumedAuthentication && !target.isEmpty() && !target.equals("com.android.systemui"))
        expectedPackage = target;
      require(target.isEmpty() ? app : target);
      return;
    }
    if (!screen.optBoolean("appContentAvailable")) {
      if (required) {
        // Inferred owners may have come from the outgoing app while the destination had no root.
        if (assumedAuthentication && !app.isEmpty() && !app.equals("com.android.systemui"))
          expectedPackage = app;
        return;
      }
      String owner = target.isEmpty() ? app : target;
      if (owner.equals("com.android.systemui")) owner = "";
      long now = clock.getAsLong();
      if (!transitionPending || (!owner.isEmpty() && !owner.equals(transitionPackage))) {
        transitionPending = true;
        transitionPackage = owner;
        transitionSince = now;
        changed.run();
      }
      if (now - transitionSince >= INDICATOR_DELAY_MS) {
        long since = transitionSince;
        String destination = transitionPackage;
        require(destination);
        assumedAuthentication = true;
        requiredSince = since; // The three-second grace also satisfies the badge delay.
      }
      return;
    }
    // Status bar, keyboard, background windows and offscreen/password nodes are not app content.
    if (app.isEmpty()
        || app.equals("com.android.systemui")
        || !AccessibilityContent.readable(
            screen.optJSONArray("elements"),
            screen.optInt("appWindowId", -1),
            app,
            screen.optInt("displayWidth"),
            screen.optInt("displayHeight"))) return;
    if (transitionPending) {
      resetTransition();
      changed.run();
    }
    // A confirmed prompt stays tied to its owner. An inferred gap has no such evidence:
    // a readable destination resolves it, even when the outgoing package was remembered.
    if (required
        && (assumedAuthentication || expectedPackage.isEmpty() || expectedPackage.equals(app))) {
      required = false;
      assumedAuthentication = false;
      expectedPackage = app;
      changed.run();
    }
  }

  public JSONObject waitFor(
      int seconds, String owner, Probe probe, LongSupplier clock, Sleeper sleeper)
      throws Exception {
    if (seconds < 1 || seconds > 40)
      throw new IllegalArgumentException("timeoutSeconds must be 1..40");
    if (!owner.isEmpty() && !owner.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))
      throw new IllegalArgumentException("Invalid expectedPackage");
    long deadline = clock.getAsLong() + seconds * 1000L;
    while (true) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Wait stopped");
      JSONObject last = null;
      if (!state().getBoolean("cancelled")) {
        try {
          last = probe.read();
        } catch (InterruptedException stop) {
          throw stop;
        } catch (java.util.concurrent.CancellationException stop) {
          throw stop;
        } catch (Exception unavailable) {
          /* A protected/unavailable UI remains a pause. */
          observeAccessibility(
              new JSONObject().put("appContentAvailable", false).put("targetPackage", owner));
        }
      }
      JSONObject result = state();
      if (!result.getBoolean("keepWaiting")) {
        if (!result.getBoolean("cancelled") && last != null) result.put("screen", last);
        return result;
      }
      if (clock.getAsLong() >= deadline) return result;
      sleeper.sleep(Math.min(1000, deadline - clock.getAsLong()));
    }
  }

  public static String instructions() {
    return "Tell the owner you are waiting for their fingerprint or manual unlock on the phone."
        + " Keep the authentication prompt open. Call phone_wait_for_authentication and"
        + " repeat it while keepWaiting=true; a wait interval ending is not task failure."
        + " Resume the original task when status=ready. Do not call phone_finish_task, go"
        + " Back/Home, relaunch apps, enter credentials, or dismiss the prompt while"
        + " waiting. A screen that remains inaccessible after a"
        + " three-second transition grace is assumed to require fingerprint or manual"
        + " unlock. Keep waiting for readable app content; do not abort or report the task"
        + " failed because the screen is inaccessible. Cancel only if the owner asks.";
  }

  public static String transitionInstructions() {
    return "The screen is changing or briefly unavailable, not yet an authentication prompt. Keep"
        + " the current screen open and call phone_wait_for_authentication to let it settle."
        + " Repeat while keepWaiting=true; do not tap, navigate, finish or abort during the"
        + " transition. If app content stays inaccessible for three seconds, the app will"
        + " request manual unlock.";
  }

  public static String guidance(JSONObject state) {
    return state.optBoolean("transitionPending") ? transitionInstructions() : instructions();
  }

  static boolean looksLikeAuthentication(JSONObject screen) {
    JSONArray elements = screen.optJSONArray("elements");
    if (elements == null) return false;
    for (int i = 0; i < elements.length(); i++) {
      JSONObject e = elements.optJSONObject(i);
      if (e == null) continue;
      String id = e.optString("id").toLowerCase(Locale.ROOT);
      String text =
          Normalizer.normalize(
                  e.optString("text") + " " + e.optString("description"), Normalizer.Form.NFD)
              .replaceAll("\\p{M}", "")
              .toLowerCase(Locale.ROOT);
      if (e.optString("package").equals("com.android.systemui")
          && id.matches(
              ".*(biometric_prompt|biometric_icon|fingerprint_icon|credential_input|pinentry|passwordentry).*"))
        return true;
      if (e.optString("package").equals("com.android.settings")
          || e.optString("package").equals("com.openai.chatgpt")
          || id.matches(".*(message_text|comment_text|message_body).*")) continue;
      if (text.matches(
          ".*(unlock with|use your|scan your|confirm your|verify your|touch the).*"
              + "(fingerprint|fingerprint sensor|biometric).*")) return true;
      if (text.matches(
          ".*(usa tu|usar tu|usa la|usar la|ingresa con|ingresar con|accede con|toca el|confirma"
              + " tu).*(huella|sensor de huellas|biometr).*")) return true;
      if (text.matches(
          ".*(fingerprint required|authentication required|app locked|aplicacion bloqueada|verifica"
              + " tu identidad|verifica que eres tu|confirma tu identidad|valida tu identidad).*"))
        return true;
    }
    return false;
  }
}
