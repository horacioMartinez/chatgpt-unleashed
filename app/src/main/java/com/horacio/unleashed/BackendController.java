package com.horacio.unleashed;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Owner-controlled lifecycle for the phone backend. */
final class BackendController {
  private static Context app;

  static void init(Context context) {
    if (app == null) app = context.getApplicationContext();
  }

  private static android.content.SharedPreferences prefs() {
    return app.getSharedPreferences("backend", Context.MODE_PRIVATE);
  }

  static boolean allowed() {
    return prefs().getBoolean("running", true);
  }

  static ConnectionState.Backend state() {
    return ConnectionState.backend(
        PhoneAccessibilityService.enabled(app), allowed(), PhoneAccessibilityService.connected());
  }

  static String status() {
    switch (state()) {
      case RUNNING:
        return "Accessibility is enabled. Phone control is ready.";
      case PAUSED:
        return "Accessibility is enabled, but phone control is paused.";
      case WAITING:
        return "Waiting for Android to connect the accessibility service.";
      default:
        return "Enable unleashed in Android Accessibility settings.";
    }
  }

  static void start(Context context) {
    new AppMcpBackend().acknowledgeTaskCancellation();
    prefs().edit().putBoolean("running", true).apply();
    if (!PhoneAccessibilityService.enabled(app)) openSettings(context);
  }

  static void stop() {
    prefs().edit().putBoolean("running", false).apply();
    AppMcpBackend.stopPhoneTask();
    PhoneAccessibilityService.cancelPending();
  }

  static void resumeForAccount() {
    new AppMcpBackend().acknowledgeTaskCancellation();
    prefs().edit().putBoolean("running", true).apply();
  }

  static void openSettings(Context context) {
    context.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
  }

  static PhoneAccessibilityService service() {
    if (!allowed())
      throw new IllegalStateException("The backend is paused. Resume it in unleashed.");
    PhoneAccessibilityService service = PhoneAccessibilityService.current();
    if (service == null)
      throw new IllegalStateException("Enable unleashed in Android Accessibility settings.");
    return service;
  }
}
