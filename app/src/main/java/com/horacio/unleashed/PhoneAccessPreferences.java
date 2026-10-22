package com.horacio.unleashed;

import android.content.Context;
import android.content.SharedPreferences;

/** Owner's durable choice, distinct from whether the service happens to be alive. */
final class PhoneAccessPreferences {
  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences("phone-access", Context.MODE_PRIVATE);
  }

  static boolean enabled(Context context) {
    return prefs(context).getBoolean("enabled", false);
  }

  static void setEnabled(Context context, boolean enabled) {
    // Persist before starting/stopping the service so a reboot cannot restore an old choice.
    if (!prefs(context).edit().putBoolean("enabled", enabled).commit())
      throw new IllegalStateException("Could not save phone access preference");
  }
}
