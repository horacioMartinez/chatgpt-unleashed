package com.horacio.unleashed;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class AccessibilityBackendTest {
  @Test
  public void hintIsNeverInsertedIntoEditableText() {
    assertEquals("", AccessibilityEditing.fieldText("Search settings", true));
    assertEquals("typed", AccessibilityEditing.fieldText("typed", false));
  }

  @Test
  public void unicodeInputInsertsAndReplacesSelection() {
    assertEquals("hola mamá 😊", AccessibilityEditing.replace("hola ", 5, 5, "mamá 😊", false));
    assertEquals(
        "good morning!", AccessibilityEditing.replace("good night!", 5, 10, "morning", false));
    assertEquals(12, AccessibilityEditing.cursor("good night!", 10, 5, "morning", false));
  }

  @Test
  public void deleteRespectsSelectionAndUnicodeCodePoints() {
    assertEquals("hi", AccessibilityEditing.replace("hi😊", 4, 4, "", true));
    assertEquals(2, AccessibilityEditing.cursor("hi😊", 4, 4, "", true));
    assertEquals("one ", AccessibilityEditing.replace("one two", 4, 7, "", true));
    assertEquals("", AccessibilityEditing.replace("", 0, 0, "", true));
  }

  static class Accessible extends McpRouterTest.Fake {}

  private JSONObject call(McpRouter router, String tool, JSONObject args) throws Exception {
    return router
        .handle(
            McpRouterTest.request("tools/call")
                .put("params", new JSONObject().put("name", tool).put("arguments", args)))
        .getJSONObject("result");
  }

  @Test
  public void toolCatalogHasExactlyTheSixPhoneOperations() throws Exception {
    JSONArray tools = McpRouter.tools();
    java.util.Set<String> names = new java.util.HashSet<>();
    for (int i = 0; i < tools.length(); i++) names.add(tools.getJSONObject(i).getString("name"));
    assertEquals(
        java.util.Set.of(
            "phone_status",
            "phone_action",
            "phone_read_screen",
            "phone_screenshot",
            "phone_finish_task",
            "phone_wait_for_authentication"),
        names);
  }

  @Test
  public void structuredActionsReachBackendDirectlyAndReadFreshScreen() throws Exception {
    Accessible backend = new Accessible();
    McpRouter router = new McpRouter(backend);
    assertFalse(
        call(router, "phone_action", new JSONObject().put("action", "type").put("text", "mamá 😊"))
            .getBoolean("isError"));
    assertEquals(1, backend.actions);
    assertEquals(1, backend.observations);
  }

  @Test
  public void authenticationAndCancellationStillBlockAccessibilityActions() throws Exception {
    Accessible backend = new Accessible();
    McpRouter router = new McpRouter(backend);
    JSONObject tap = new JSONObject().put("action", "tap").put("x", 20).put("y", 20);
    backend.authenticationRequired = true;
    call(router, "phone_action", tap);
    assertEquals(0, backend.actions);
    backend.authenticationRequired = false;
    backend.stopped = true;
    call(router, "phone_action", tap);
    assertEquals(0, backend.actions);
    call(router, "phone_finish_task", new JSONObject());
    call(router, "phone_action", tap);
    assertEquals(1, backend.actions);
  }

  @Test
  public void badStructuredActionCannotReachBackend() throws Exception {
    Accessible backend = new Accessible();
    McpRouter router = new McpRouter(backend);
    assertTrue(
        call(
                router,
                "phone_action",
                new JSONObject().put("action", "launch").put("package", "bad;payload"))
            .getBoolean("isError"));
    assertEquals(0, backend.actions);
  }
}
