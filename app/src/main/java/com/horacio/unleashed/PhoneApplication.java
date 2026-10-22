package com.horacio.unleashed;

/** Initializes app-private state without starting phone access or opening a connection. */
public class PhoneApplication extends android.app.Application {
  @Override
  public void onCreate() {
    super.onCreate();
    Diagnostics.init(this);
    BackendController.init(this);
  }
}
