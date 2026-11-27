package com.horacio.unleashed;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Validate the structured input before any screen interaction. */
final class PhoneAction {
  // One set of allowed fields drives the MCP schema and runtime rejection of mixed operations.
  private static final Map<String, List<String>> FIELDS =
      Map.of(
          "tap",
          List.of("x", "y"),
          "swipe",
          List.of("x", "y", "endX", "endY", "durationMs"),
          "type",
          List.of("text"),
          "type_into",
          List.of("element", "text"),
          "focus",
          List.of("element"),
          "activate",
          List.of("element"),
          "scroll",
          List.of("element", "direction"),
          "key",
          List.of("key"),
          "launch",
          List.of("package"));

  static JSONArray constraints(JSONObject properties) throws JSONException {
    JSONArray constraints = new JSONArray();
    for (String action : FIELDS.keySet()) {
      JSONObject when =
          new JSONObject()
              .put(
                  "properties",
                  new JSONObject().put("action", new JSONObject().put("const", action)));
      JSONObject allowed = new JSONObject();
      Iterator<String> names = properties.keys();
      while (names.hasNext()) {
        String name = names.next();
        if (!name.equals("action") && !FIELDS.get(action).contains(name)) allowed.put(name, false);
      }
      JSONArray required = new JSONArray();
      for (String name : FIELDS.get(action)) if (!name.equals("durationMs")) required.put(name);
      constraints.put(
          new JSONObject()
              .put("if", when)
              .put("then", new JSONObject().put("properties", allowed).put("required", required)));
    }
    return constraints;
  }

  static void validate(JSONObject args) {
    try {
      validateFields(args);
    } catch (JSONException invalid) {
      throw new InvalidToolArgumentsException(invalid.getMessage());
    }
  }

  private static void validateFields(JSONObject args) throws JSONException {
    String action = args.getString("action");
    List<String> fields = FIELDS.get(action);
    if (fields == null) throw new InvalidToolArgumentsException("Unsupported action");
    Iterator<String> names = args.keys();
    while (names.hasNext()) {
      String name = names.next();
      if (!name.equals("action") && !fields.contains(name))
        throw new InvalidToolArgumentsException(name + " is not an argument of " + action);
    }
    if (fields.contains("text") && !(args.get("text") instanceof String))
      throw new InvalidToolArgumentsException("text must be a string");
    if (fields.contains("element")
        && (!(args.get("element") instanceof String) || args.getString("element").isEmpty()))
      throw new InvalidToolArgumentsException("element must be a reference from the screen");
    switch (action) {
      case "tap":
        coordinate(args, "x");
        coordinate(args, "y");
        break;
      case "swipe":
        coordinate(args, "x");
        coordinate(args, "y");
        coordinate(args, "endX");
        coordinate(args, "endY");
        int duration = args.has("durationMs") ? integer(args, "durationMs") : 400;
        if (duration < 1 || duration > 5000)
          throw new InvalidToolArgumentsException("durationMs must be 1..5000");
        break;
      case "type":
      case "type_into":
      case "focus":
      case "activate":
        break;
      case "scroll":
        if (!args.getString("direction").matches("forward|backward"))
          throw new InvalidToolArgumentsException("direction must be forward or backward");
        break;
      case "key":
        if (!args.getString("key").matches("HOME|BACK|APP_SWITCH|ENTER|DEL|WAKEUP"))
          throw new InvalidToolArgumentsException("Unsupported key");
        break;
      case "launch":
        if (!args.getString("package").matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))
          throw new InvalidToolArgumentsException("Invalid Android package");
        break;
      default:
        throw new InvalidToolArgumentsException("Unsupported action");
    }
  }

  private static void coordinate(JSONObject args, String key) throws JSONException {
    int n = integer(args, key);
    if (n < 0 || n > 10000) throw new InvalidToolArgumentsException("Coordinates must be 0..10000");
  }

  private static int integer(JSONObject args, String key) throws JSONException {
    Object value = args.get(key);
    if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue())
      throw new InvalidToolArgumentsException(key + " must be an integer");
    return ((Number) value).intValue();
  }
}
