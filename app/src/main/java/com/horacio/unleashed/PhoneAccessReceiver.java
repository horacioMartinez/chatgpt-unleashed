package com.horacio.unleashed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;

/** Restores an enabled connection after first unlock or an APK update; never opens UI. */
public final class PhoneAccessReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null
        || !(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))) return;
    // Credentials remain in credential-encrypted storage. No direct-boot copy of secrets.
    if (!context.getSystemService(UserManager.class).isUserUnlocked()) return;
    Diagnostics.init(context);
    try {
      if (!PhoneAccessPreferences.enabled(context) || !new TunnelVault(context).configured())
        return;
      context.startForegroundService(new Intent(context, TunnelService.class).setAction("restore"));
      Diagnostics.record(
          DiagnosticEvent.event(
              DiagnosticEvent.Area.APP,
              DiagnosticEvent.Operation.RESTORE_CONNECTION,
              DiagnosticEvent.Outcome.SUCCESS,
              DiagnosticEvent.Code.NONE));
    } catch (RuntimeException error) {
      TunnelService.state = "Automatic connection could not start. Open Connect ChatGPT to retry.";
      Diagnostics.record(
          DiagnosticEvent.problem(
              DiagnosticEvent.Area.APP,
              DiagnosticEvent.Operation.RESTORE_CONNECTION,
              error,
              DiagnosticEvent.newId()));
    }
  }
}
