package com.horacio.unleashed;

import android.os.Build;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** References keep Android's node identity, never a coordinate, label lookup, or tree index. */
final class ElementReferences {
  static final class Capture implements AutoCloseable {
    final ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<>();

    void add(AccessibilityNodeInfo node) {
      nodes.add(node.isPassword() ? null : AccessibilityNodeInfo.obtain(node));
    }

    public void close() {
      for (AccessibilityNodeInfo n : nodes) if (n != null) n.recycle();
      nodes.clear();
    }
  }

  private final String session = UUID.randomUUID().toString().substring(0, 12);
  private long sequence;
  private Map<String, AccessibilityNodeInfo> byRef = new HashMap<>();
  private Map<AccessibilityNodeInfo, String> byNode = new HashMap<>();

  synchronized void publish(JSONObject screen, Capture capture) throws JSONException {
    JSONArray elements = screen.getJSONArray("elements");
    int window = screen.optInt("appWindowId", -1);
    Map<String, AccessibilityNodeInfo> next = new HashMap<>();
    Map<AccessibilityNodeInfo, String> reverse = new HashMap<>();
    if (screen.optBoolean("appContentAvailable")) {
      for (int i = 0; i < capture.nodes.size(); i++) {
        AccessibilityNodeInfo n = capture.nodes.get(i);
        if (n == null || n.getWindowId() != window) continue;
        String duplicate = reverse.get(n);
        if (duplicate != null) {
          elements.getJSONObject(i).put("ref", duplicate);
          continue;
        }
        String ref = byNode.get(n);
        if (ref == null || !sameElement(byRef.get(ref), n)) ref = session + "-" + (++sequence);
        JSONObject element = elements.getJSONObject(i).put("ref", ref);
        describeActions(element, n);
        next.put(ref, n);
        reverse.put(n, ref);
      }
    }
    // Transfer the selected copies out of the temporary capture.
    for (int i = 0; i < capture.nodes.size(); i++) {
      AccessibilityNodeInfo n = capture.nodes.get(i);
      if (n != null && n == next.get(reverse.get(n))) capture.nodes.set(i, null);
    }
    clear();
    byRef = next;
    byNode = reverse;
  }

  synchronized AccessibilityNodeInfo resolve(String ref, AccessibilityNodeInfo activeRoot) {
    AccessibilityNodeInfo previous = byRef.get(ref);
    if (previous == null) throw FieldInputException.stale();
    AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(previous);
    if (activeRoot != null
        && activeRoot.getWindowId() == previous.getWindowId()
        && current.refresh()
        && current.isVisibleToUser()
        && !current.isPassword()
        && sameElement(previous, current)) return current;
    current.recycle();
    byRef.remove(ref);
    byNode.remove(previous);
    previous.recycle();
    throw FieldInputException.stale();
  }

  private static void describeActions(JSONObject element, AccessibilityNodeInfo node)
      throws JSONException {
    JSONArray actions = new JSONArray();
    element.put("actions", actions);
    if (!node.isEnabled()) return;
    java.util.List<AccessibilityNodeInfo.AccessibilityAction> supported = node.getActionList();
    if (node.isFocused()
        || supported.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS))
      actions.put("focus");
    if (node.isEditable()
        && (node.isFocused()
            || supported.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS))
        && supported.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT))
      actions.put("type_into");
    if (supported.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK))
      actions.put("activate");
    JSONArray directions = new JSONArray();
    if (supported.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD))
      directions.put("forward");
    if (supported.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD))
      directions.put("backward");
    if (directions.length() > 0) {
      actions.put("scroll");
      element.put("scrollDirections", directions);
    }
  }

  private static boolean sameElement(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
    return a.equals(b)
        && TextUtils.equals(a.getPackageName(), b.getPackageName())
        && TextUtils.equals(a.getClassName(), b.getClassName())
        && Objects.equals(a.getViewIdResourceName(), b.getViewIdResourceName())
        && (Build.VERSION.SDK_INT < 33 || Objects.equals(a.getUniqueId(), b.getUniqueId()))
        && a.isEditable() == b.isEditable()
        && TextUtils.equals(a.getContentDescription(), b.getContentDescription())
        && (a.isEditable() || TextUtils.equals(a.getText(), b.getText()));
  }

  synchronized void clear() {
    byNode.clear();
    for (AccessibilityNodeInfo n : byRef.values()) n.recycle();
    byRef.clear();
  }
}
