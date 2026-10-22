package com.horacio.unleashed;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only visible content in the relevant window counts; background/chrome is not an unlock. */
final class AccessibilityContent {
  private static final Pattern BOUNDS =
      Pattern.compile("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");

  static boolean readable(JSONArray elements, int window, String owner, int width, int height) {
    if (elements == null || window < 0 || owner.isEmpty() || width <= 0 || height <= 0)
      return false;
    for (int i = 0; i < elements.length(); i++) {
      JSONObject node = elements.optJSONObject(i);
      if (node == null
          || node.optInt("windowId", -1) != window
          || !owner.equals(node.optString("package"))
          || node.optBoolean("password")) continue;
      if (node.optString("text").isEmpty()
          && node.optString("description").isEmpty()
          && !node.optBoolean("editable")) continue;
      Matcher bounds = BOUNDS.matcher(node.optString("bounds"));
      if (!bounds.matches()) continue;
      try {
        int left = Integer.parseInt(bounds.group(1)), top = Integer.parseInt(bounds.group(2));
        int right = Integer.parseInt(bounds.group(3)), bottom = Integer.parseInt(bounds.group(4));
        if (Math.max(0, left) < Math.min(width, right)
            && Math.max(0, top) < Math.min(height, bottom)) return true;
      } catch (NumberFormatException invalid) {
        /* Invalid geometry is not usable app content. */
      }
    }
    return false;
  }
}
