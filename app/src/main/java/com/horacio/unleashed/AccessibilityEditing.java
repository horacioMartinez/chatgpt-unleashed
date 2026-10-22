package com.horacio.unleashed;

/** Input text inserts at the selection; DEL removes a full Unicode code point. */
final class AccessibilityEditing {
  static String fieldText(String value, boolean showingHint) {
    return showingHint ? "" : value;
  }

  static int cursor(String text, int start, int end, String inserted, boolean delete) {
    int left = Math.max(0, Math.min(Math.min(start, end), text.length()));
    int right = Math.max(left, Math.min(Math.max(start, end), text.length()));
    if (delete && left == right && left > 0) left = text.offsetByCodePoints(left, -1);
    return left + (delete ? 0 : inserted.length());
  }

  static String replace(String text, int start, int end, String inserted, boolean delete) {
    int left = Math.max(0, Math.min(Math.min(start, end), text.length()));
    int right = Math.max(left, Math.min(Math.max(start, end), text.length()));
    if (delete && left == right && left > 0) left = text.offsetByCodePoints(left, -1);
    return text.substring(0, left) + (delete ? "" : inserted) + text.substring(right);
  }
}
