package com.horacio.unleashed;

import org.json.*;

/** Wire shape follows openai/tunnel-client pkg/controlplane/wiretypes/wire.go. */
public final class TunnelProtocol {
  public static JSONObject response(JSONObject command, McpRouter router)
      throws org.json.JSONException {
    return response(command, router, DiagnosticEvent.newId());
  }

  public static JSONObject response(JSONObject command, McpRouter router, String trace)
      throws org.json.JSONException {
    String request = command.getString("request_id");
    if (request.isEmpty() || command.optString("shard_token").isEmpty())
      throw new IllegalArgumentException("Missing tunnel routing information");
    String channel = command.optString("channel", "main");
    String type = command.getString("command_type");
    JSONObject envelope = new JSONObject().put("request_id", request).put("channel", channel);
    if (type.equals("jsonrpc")) {
      JSONObject body = command.getJSONObject("jsonrpc");
      JSONObject reply;
      if (!channel.equals("main"))
        reply =
            new JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", body.opt("id"))
                .put(
                    "error",
                    new JSONObject().put("code", -32600).put("message", "Unsupported channel"));
      else reply = router.handle(body, trace);
      if (reply == null) return envelope.put("resp_code", 202).put("resp_type", "notify_ack");
      return envelope
          .put("resp_code", 200)
          .put("resp_type", "jsonrpc_response")
          .put(
              "resp_headers",
              new JSONObject().put("Content-Type", new JSONArray().put("application/json")))
          .put("resp_json", reply);
    }
    if (type.equals("session_termination"))
      return envelope.put("resp_code", 200).put("resp_type", "session_termination_response");
    if (type.equals("oauth_discovery"))
      return envelope.put("resp_code", 404).put("resp_type", "oauth_discovery_response");
    throw new IllegalArgumentException("Unsupported tunnel command type");
  }
}
