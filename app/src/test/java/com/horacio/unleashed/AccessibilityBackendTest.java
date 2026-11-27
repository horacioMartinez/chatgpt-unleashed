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
  public void thoroughReadIsAdvertisedAndAppliesOnlyToTheRequestedRead() throws Exception {
    JSONArray catalog = McpRouter.tools();
    for (int i = 0; i < catalog.length(); i++) {
      JSONObject tool = catalog.getJSONObject(i);
      if (!"phone_read_screen".equals(tool.getString("name"))) continue;
      JSONObject option =
          tool.getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("thorough");
      assertEquals("boolean", option.getString("type"));
      assertFalse(option.getBoolean("default"));
    }
    java.util.List<Boolean> modes = new java.util.ArrayList<>();
    Accessible backend =
        new Accessible() {
          @Override
          public JSONObject readScreen(boolean thorough) throws Exception {
            modes.add(thorough);
            return super.readScreen(thorough);
          }
        };
    McpRouter router = new McpRouter(backend);
    assertFalse(call(router, "phone_read_screen", new JSONObject()).getBoolean("isError"));
    assertFalse(
        call(router, "phone_read_screen", new JSONObject().put("thorough", true))
            .getBoolean("isError"));
    assertFalse(call(router, "phone_action", swipe()).getBoolean("isError"));
    assertEquals(java.util.List.of(false, true, false), modes);
    for (Object invalid : new Object[] {"true", 1, JSONObject.NULL}) {
      JSONObject result =
          call(router, "phone_read_screen", new JSONObject().put("thorough", invalid));
      assertTrue(result.getBoolean("isError"));
      assertEquals("INVALID_ARGUMENTS", payload(result).getString("code"));
    }
    assertEquals(3, modes.size());
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

  private JSONObject swipe() throws Exception {
    return new JSONObject()
        .put("action", "swipe")
        .put("x", 530)
        .put("y", 2100)
        .put("endX", 530)
        .put("endY", 850)
        .put("durationMs", 450);
  }

  private JSONObject payload(JSONObject result) throws Exception {
    return new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
  }

  @Test
  public void snapshotTimeoutPreservesCompletedSwipeAndNextReadRecovers() throws Exception {
    Accessible backend =
        new Accessible() {
          @Override
          public JSONObject readScreen(boolean thorough) throws Exception {
            if (observations == 0) {
              observations++;
              throw new java.util.concurrent.TimeoutException();
            }
            return super.readScreen(thorough);
          }
        };
    McpRouter router = new McpRouter(backend);
    JSONObject result = call(router, "phone_action", swipe());
    JSONObject data = payload(result);
    assertFalse(result.getBoolean("isError"));
    assertTrue(data.getJSONObject("action").getBoolean("actionPerformed"));
    assertTrue(data.getBoolean("screenUnavailable"));
    JSONObject error = data.getJSONObject("screenError");
    assertEquals("TIMEOUT", error.getString("code"));
    assertEquals("TimeoutException", error.getString("exception"));
    assertTrue(data.getString("nextAction").contains("phone_read_screen"));
    assertTrue(payload(call(router, "phone_read_screen", new JSONObject())).has("elements"));
    assertEquals(1, backend.actions);
    assertEquals(0, backend.finishes);
  }

  @Test
  public void rejectedGestureAndInvalidDurationHaveDistinctErrors() throws Exception {
    Accessible backend =
        new Accessible() {
          @Override
          public JSONObject action(JSONObject args) {
            actions++;
            throw new IllegalArgumentException("Android rejected the gesture");
          }
        };
    McpRouter router = new McpRouter(backend);
    JSONObject failed = call(router, "phone_action", swipe());
    assertTrue(failed.getBoolean("isError"));
    JSONObject error = payload(failed);
    assertEquals("TOOL_FAILED", error.getString("code"));
    assertEquals("Android rejected the gesture", error.getString("message"));
    assertTrue(payload(call(router, "phone_read_screen", new JSONObject())).has("elements"));
    JSONObject invalid = call(router, "phone_action", swipe().put("durationMs", "slow"));
    assertTrue(invalid.getBoolean("isError"));
    assertEquals("INVALID_ARGUMENTS", payload(invalid).getString("code"));
    assertEquals(1, backend.actions);
    assertEquals(0, backend.finishes);
  }

  @Test
  public void typeRejectsCoordinatesAndFocusRequiresAnElement() throws Exception {
    Accessible backend = new Accessible();
    McpRouter router = new McpRouter(backend);
    JSONObject type = new JSONObject().put("action", "type").put("text", "hola");
    for (JSONObject invalid :
        new JSONObject[] {
          new JSONObject(type.toString()).put("x", 100),
          new JSONObject(type.toString()).put("y", 50),
          new JSONObject(type.toString()).put("x", 100).put("y", 50),
          new JSONObject(type.toString()).put("element", "abc"),
          new JSONObject().put("action", "focus").put("x", 50).put("y", 100),
          new JSONObject().put("action", "focus"),
          new JSONObject().put("action", "scroll").put("element", "abc").put("direction", "left")
        }) {
      assertEquals(
          "INVALID_ARGUMENTS", payload(call(router, "phone_action", invalid)).getString("code"));
    }
    assertEquals(0, backend.actions);
    assertFalse(call(router, "phone_action", type).getBoolean("isError"));
    assertFalse(
        call(router, "phone_action", new JSONObject().put("action", "focus").put("element", "abc"))
            .getBoolean("isError"));
    assertEquals(2, backend.actions);
  }

  @Test
  public void typeIntoRequiresExactlyAnElementAndText() throws Exception {
    Accessible backend = new Accessible();
    McpRouter router = new McpRouter(backend);
    JSONObject args = new JSONObject().put("action", "type_into");
    for (JSONObject invalid :
        new JSONObject[] {
          new JSONObject(args.toString()).put("text", "A"),
          new JSONObject(args.toString()).put("element", "ref"),
          new JSONObject(args.toString()).put("element", "").put("text", "A"),
          new JSONObject(args.toString()).put("element", "ref").put("text", 123),
          new JSONObject(args.toString()).put("element", "ref").put("text", "A").put("x", 10)
        }) {
      assertEquals(
          "INVALID_ARGUMENTS", payload(call(router, "phone_action", invalid)).getString("code"));
    }
    assertEquals(0, backend.actions);
    assertFalse(
        call(router, "phone_action", args.put("element", "ref").put("text", "A"))
            .getBoolean("isError"));
    assertEquals(1, backend.actions);
  }

  @Test
  public void staleElementIsReportedWithoutReplayingTheAction() throws Exception {
    Accessible backend =
        new Accessible() {
          @Override
          public JSONObject action(JSONObject args) {
            actions++;
            throw FieldInputException.stale();
          }
        };
    McpRouter router = new McpRouter(backend);
    JSONObject result =
        call(
            router,
            "phone_action",
            new JSONObject().put("action", "focus").put("element", "expired"));
    assertTrue(result.getBoolean("isError"));
    assertEquals("STALE_ELEMENT", payload(result).getString("code"));
    assertEquals(1, backend.actions);
    assertTrue(payload(call(router, "phone_read_screen", new JSONObject())).has("elements"));
  }

  @Test
  public void advertisedTypingAndFocusingHaveSeparateArguments() throws Exception {
    JSONArray tools = McpRouter.tools();
    for (int i = 0; i < tools.length(); i++) {
      JSONObject tool = tools.getJSONObject(i);
      if (!tool.getString("name").equals("phone_action")) continue;
      JSONArray conditions = tool.getJSONObject("inputSchema").getJSONArray("allOf");
      java.util.Set<String> checked = new java.util.HashSet<>();
      for (int j = 0; j < conditions.length(); j++) {
        JSONObject rule = conditions.getJSONObject(j);
        String action =
            rule.getJSONObject("if")
                .getJSONObject("properties")
                .getJSONObject("action")
                .getString("const");
        if (!java.util.Set.of("focus", "type", "type_into").contains(action)) continue;
        JSONObject then = rule.getJSONObject("then");
        assertEquals(
            action.equals("type") ? "text" : "element", then.getJSONArray("required").getString(0));
        if (action.equals("type_into"))
          assertEquals("text", then.getJSONArray("required").getString(1));
        assertFalse(then.getJSONObject("properties").getBoolean("x"));
        assertFalse(then.getJSONObject("properties").getBoolean("y"));
        checked.add(action);
      }
      assertEquals(java.util.Set.of("focus", "type", "type_into"), checked);
      return;
    }
    fail("phone_action missing from catalog");
  }

  @Test
  public void fieldFailuresDistinguishMissingFocusFromRejectedInput() throws Exception {
    Accessible backend =
        new Accessible() {
          @Override
          public JSONObject action(JSONObject args) {
            if (actions++ == 0) throw FieldInputException.missing("Missing editor", null);
            throw FieldInputException.unsupported("Rejected ACTION_SET_TEXT", null);
          }
        };
    McpRouter router = new McpRouter(backend);
    JSONObject type = new JSONObject().put("action", "type").put("text", "hola");
    JSONObject missing = payload(call(router, "phone_action", type));
    assertEquals("INPUT_FOCUS_MISSING", missing.getString("code"));
    assertTrue(missing.getString("nextAction").contains("focus"));
    assertEquals(
        "INPUT_FIELD_UNSUPPORTED", payload(call(router, "phone_action", type)).getString("code"));
    assertTrue(payload(call(router, "phone_read_screen", new JSONObject())).has("elements"));
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
