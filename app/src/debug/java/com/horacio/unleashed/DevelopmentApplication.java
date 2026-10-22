package com.horacio.unleashed;

/** Local verification transport, compiled only into development APKs. */
public final class DevelopmentApplication extends PhoneApplication {
  @Override
  public void onCreate() {
    super.onCreate();
    DevBridge.start();
  }
}
