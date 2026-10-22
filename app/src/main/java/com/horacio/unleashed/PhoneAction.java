package com.horacio.unleashed;

import org.json.JSONObject;

/** Validate the structured input before any screen interaction. */
final class PhoneAction {
  static void validate(JSONObject args) throws org.json.JSONException {
    switch (args.getString("action")) {
      case "tap":
        coordinate(args, "x");
        coordinate(args, "y");
        break;
      case "swipe":
        coordinate(args, "x");
        coordinate(args, "y");
        coordinate(args, "endX");
        coordinate(args, "endY");
        int duration = args.optInt("durationMs", 400);
        if (duration < 1 || duration > 5000)
          throw new IllegalArgumentException("durationMs must be 1..5000");
        break;
      case "type":
        args.getString("text");
        break;
      case "key":
        if (!args.getString("key").matches("HOME|BACK|APP_SWITCH|ENTER|DEL|WAKEUP"))
          throw new IllegalArgumentException("Unsupported key");
        break;
      case "launch":
        if (!args.getString("package").matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))
          throw new IllegalArgumentException("Invalid Android package");
        break;
      default:
        throw new IllegalArgumentException("Unsupported action");
    }
  }

  private static void coordinate(JSONObject args, String key) throws org.json.JSONException {
    int n = args.getInt(key);
    if (n < 0 || n > 10000) throw new IllegalArgumentException("Coordinates must be 0..10000");
  }
}
