package com.horacio.unleashed;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Debug-only local MCP transport. The OS restricts it to the Android debug user. */
final class DevBridge {
  private static final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16));

  static void start() {
    new Thread(
            () -> {
              try (LocalServerSocket server = new LocalServerSocket("unleashed-dev")) {
                while (true) {
                  LocalSocket socket = server.accept();
                  try {
                    workers.execute(() -> serve(socket));
                  } catch (RejectedExecutionException full) {
                    socket.close();
                  }
                }
              } catch (IOException unavailable) {
                Diagnostics.record(
                    DiagnosticEvent.problem(
                        DiagnosticEvent.Area.APP,
                        DiagnosticEvent.Operation.START,
                        unavailable,
                        DiagnosticEvent.newId()));
              }
            },
            "unleashed-dev-bridge")
        .start();
  }

  private static void serve(LocalSocket socket) {
    try (LocalSocket client = socket) {
      if (client.getPeerCredentials().getUid() != 2000) return;
      client.setSoTimeout(5000);
      DataInputStream in = new DataInputStream(client.getInputStream());
      int size = in.readInt();
      if (size < 1 || size > 65536) return;
      byte[] data = new byte[size];
      in.readFully(data);
      JSONObject request = new JSONObject(new String(data, StandardCharsets.UTF_8));
      JSONObject result =
          new McpRouter(new AppMcpBackend(), Diagnostics::record)
              .handle(request.getJSONObject("jsonrpc"));
      byte[] response =
          new JSONObject()
              .put("requestId", request.getString("requestId"))
              .put("jsonrpc", result == null ? JSONObject.NULL : result)
              .toString()
              .getBytes(StandardCharsets.UTF_8);
      DataOutputStream out = new DataOutputStream(client.getOutputStream());
      out.writeInt(response.length);
      out.write(response);
      out.flush();
    } catch (Exception error) {
      Diagnostics.record(
          DiagnosticEvent.problem(
              DiagnosticEvent.Area.APP,
              DiagnosticEvent.Operation.PROCESS_REQUEST,
              error,
              DiagnosticEvent.newId()));
    }
  }
}
