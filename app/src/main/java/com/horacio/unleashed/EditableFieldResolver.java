package com.horacio.unleashed;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

/** Reads keyboard focus, including virtual editors whose provider returns their host. */
final class EditableFieldResolver {
  private final AccessibilityService service;
  private final Runnable guard;

  EditableFieldResolver(AccessibilityService service, Runnable guard) {
    this.service = service;
    this.guard = guard;
  }

  AccessibilityNodeInfo focusedEditor() throws InterruptedException {
    for (int attempt = 0; ; attempt++) {
      guard.run();
      AccessibilityNodeInfo focus = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
      AccessibilityNodeInfo root = service.getRootInActiveWindow();
      try {
        if (root != null && (focus == null || root.getWindowId() == focus.getWindowId())) {
          if (focus != null && focus.refresh() && focusedField(focus)) return checkedCopy(focus);
          AccessibilityNodeInfo editor =
              findFocusedChild(root, 0, SystemClock.elapsedRealtime() + 400);
          if (editor != null) return editor;
        }
        if (attempt == 2)
          throw FieldInputException.missing("No editable field has keyboard focus", focus);
      } finally {
        if (focus != null) focus.recycle();
        if (root != null) root.recycle();
      }
      Thread.sleep(80);
    }
  }

  private static boolean focusedField(AccessibilityNodeInfo node) {
    return node.isFocused()
        && node.isVisibleToUser()
        && (node.isEditable()
            || node.isPassword()
            || "android.widget.EditText".equals(String.valueOf(node.getClassName())));
  }

  static void requireWritable(AccessibilityNodeInfo node) {
    if (node.isPassword())
      throw new SecurityException("Password fields cannot receive automated text input");
    if (!node.isEditable() || !node.isEnabled())
      throw FieldInputException.unsupported("The focused field is read-only or disabled", node);
  }

  private static AccessibilityNodeInfo checkedCopy(AccessibilityNodeInfo node) {
    requireWritable(node);
    return AccessibilityNodeInfo.obtain(node);
  }

  private AccessibilityNodeInfo findFocusedChild(
      AccessibilityNodeInfo node, int depth, long deadline) {
    guard.run();
    if (depth > 128 || SystemClock.elapsedRealtime() >= deadline) return null;
    if (focusedField(node) && node.refresh() && focusedField(node)) return checkedCopy(node);
    for (int i = 0; i < node.getChildCount() && SystemClock.elapsedRealtime() < deadline; i++) {
      AccessibilityNodeInfo child = node.getChild(i);
      if (child == null) continue;
      try {
        AccessibilityNodeInfo found = findFocusedChild(child, depth + 1, deadline);
        if (found != null) return found;
      } finally {
        child.recycle();
      }
    }
    return null;
  }
}
