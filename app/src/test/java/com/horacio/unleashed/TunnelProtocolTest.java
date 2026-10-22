package com.horacio.unleashed;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class TunnelProtocolTest {
  private JSONObject command(String method, boolean notification) throws Exception {
    JSONObject rpc = new JSONObject().put("jsonrpc", "2.0").put("method", method);
    if (!notification) rpc.put("id", "rpc-test");
    return new JSONObject()
        .put("request_id", "request-test")
        .put("shard_token", "private-routing-token")
        .put("command_type", "jsonrpc")
        .put("channel", "main")
        .put("jsonrpc", rpc);
  }

  @Test
  public void responseEchoesRequestWithoutLeakingShard() throws Exception {
    JSONObject result =
        TunnelProtocol.response(
            command("tools/list", false), new McpRouter(new McpRouterTest.Fake()));
    assertEquals("request-test", result.getString("request_id"));
    assertEquals("jsonrpc_response", result.getString("resp_type"));
    assertEquals("rpc-test", result.getJSONObject("resp_json").getString("id"));
    assertFalse(result.toString().contains("private-routing-token"));
  }

  @Test
  public void notificationIsAcknowledgedWithoutRpcBody() throws Exception {
    JSONObject result =
        TunnelProtocol.response(
            command("notifications/initialized", true), new McpRouter(new McpRouterTest.Fake()));
    assertEquals("notify_ack", result.getString("resp_type"));
    assertFalse(result.has("resp_json"));
  }

  @Test
  public void unsupportedChannelDoesNotExecute() throws Exception {
    McpRouterTest.Fake fake = new McpRouterTest.Fake();
    JSONObject cmd = command("tools/call", false).put("channel", "other");
    JSONObject result = TunnelProtocol.response(cmd, new McpRouter(fake));
    assertTrue(result.getJSONObject("resp_json").has("error"));
    assertEquals(0, fake.actions);
  }

  @Test(expected = IllegalArgumentException.class)
  public void missingRoutingTokenIsRejected() throws Exception {
    TunnelProtocol.response(
        command("tools/list", false).put("shard_token", ""),
        new McpRouter(new McpRouterTest.Fake()));
  }
}
