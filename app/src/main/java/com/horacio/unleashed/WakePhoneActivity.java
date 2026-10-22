package com.horacio.unleashed;

/** Requests a visible screen; never dismisses a secure keyguard or supplies credentials. */
public final class WakePhoneActivity extends android.app.Activity {
  @Override
  public void onCreate(android.os.Bundle state) {
    super.onCreate(state);
    setShowWhenLocked(true);
    setTurnScreenOn(true);
    new android.os.Handler(getMainLooper()).postDelayed(this::finish, 700);
  }
}
