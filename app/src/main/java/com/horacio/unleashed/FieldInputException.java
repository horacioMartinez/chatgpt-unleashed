package com.horacio.unleashed;

import android.view.accessibility.AccessibilityNodeInfo;

/** Report the type of focus failure without including text, hints, or typed values. */
final class FieldInputException extends IllegalStateException {
  final DiagnosticEvent.Code code;

  private FieldInputException(
      DiagnosticEvent.Code code, String message, AccessibilityNodeInfo node) {
    super(
        message
            + (node == null
                ? " (no node)"
                : " (package="
                    + node.getPackageName()
                    + ", class="
                    + node.getClassName()
                    + ", inputFocused="
                    + node.isFocused()
                    + ", editable="
                    + node.isEditable()
                    + ", enabled="
                    + node.isEnabled()
                    + ")"));
    this.code = code;
  }

  static FieldInputException stale() {
    return new FieldInputException(
        DiagnosticEvent.Code.STALE_ELEMENT,
        "Read the screen for a current element reference",
        null);
  }

  static FieldInputException unsupportedElement(String message, AccessibilityNodeInfo node) {
    return new FieldInputException(DiagnosticEvent.Code.ELEMENT_ACTION_UNSUPPORTED, message, node);
  }

  static FieldInputException missing(String message, AccessibilityNodeInfo node) {
    return new FieldInputException(DiagnosticEvent.Code.INPUT_FOCUS_MISSING, message, node);
  }

  static FieldInputException unsupported(String message, AccessibilityNodeInfo node) {
    return new FieldInputException(DiagnosticEvent.Code.INPUT_FIELD_UNSUPPORTED, message, node);
  }
}
