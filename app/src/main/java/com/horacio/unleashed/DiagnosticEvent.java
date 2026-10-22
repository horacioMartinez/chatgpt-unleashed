package com.horacio.unleashed;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import org.json.*;

/** Allowlisted metadata only. No API accepts arbitrary messages, arguments, or exception text. */
public final class DiagnosticEvent {
  public enum Area {
    APP,
    TOOL,
    TUNNEL,
    BACKEND,
    DIAGNOSTICS
  }

  public enum Operation {
    START,
    CONNECT,
    DISCONNECT,
    RESTORE_CONNECTION,
    POLL,
    DELIVER_RESPONSE,
    PROCESS_REQUEST,
    EXPORT,
    STATUS,
    READ_SCREEN,
    SCREENSHOT,
    ACTION,
    FINISH_TASK,
    WAIT_AUTHENTICATION,
    UNKNOWN_TOOL
  }

  public enum Outcome {
    STARTED,
    SUCCESS,
    ERROR,
    WAITING,
    CANCELLED,
    UNSUPPORTED
  }

  public enum Code {
    NONE("Completed successfully."),
    STARTED("Operation started; a missing completion can indicate interruption."),
    INVALID_ARGUMENTS("The tool request had invalid or unsupported arguments."),
    PERMISSION_DENIED("Android or the phone backend denied the operation."),
    TIMEOUT("The operation timed out."),
    IO_ERROR("A device, network, or storage operation failed."),
    UI_INSPECTION_FAILED("The phone screen could not be inspected."),
    RESTORE_FAILED("The existing ChatGPT task could not be restored."),
    TOOL_FAILED("The phone tool could not complete."),
    PROTOCOL_ERROR("A request or response had an unsupported or invalid format."),
    UNSUPPORTED_CAPABILITY(
        "The client requested a protocol capability this server does not implement."),
    HTTP_ERROR("The tunnel service returned an unsuccessful HTTP status."),
    RESPONSE_EXPIRED("The tunnel request or result expired before delivery."),
    CONNECTION_UNAVAILABLE("The required phone backend or tunnel connection was unavailable."),
    CONNECTION_LOST("The phone backend connection was lost."),
    AUTHENTICATION_REQUIRED("Waiting for the owner to unlock; this is not a failure."),
    CANCELLED("The operation was cancelled."),
    STORAGE_ERROR("The diagnostics history could not be read or written."),
    EXPORT_FAILED("The diagnostics report could not be saved."),
    UNCAUGHT_EXCEPTION("An unhandled exception terminated the app process.");
    public final String explanation;

    Code(String explanation) {
      this.explanation = explanation;
    }
  }

  public interface Sink {
    void record(DiagnosticEvent event);
  }

  public static final Sink NONE = event -> {};

  public final Area area;
  public final Operation operation;
  public final Outcome outcome;
  public final Code code;
  private final String time = java.time.Instant.now().toString();
  private final String requestId, taskId;
  private final long durationMs;
  private final Integer exitCode, httpStatus;
  private Integer rpcErrorCode;
  private String rpcMethod;

  private DiagnosticEvent(
      Area area,
      Operation operation,
      Outcome outcome,
      Code code,
      String requestId,
      String taskId,
      long durationMs,
      Integer exitCode,
      Integer httpStatus) {
    this.area = area;
    this.operation = operation;
    this.outcome = outcome;
    this.code = code;
    this.requestId = safeId(requestId);
    this.taskId = safeId(taskId);
    this.durationMs = Math.max(0, durationMs);
    this.exitCode = exitCode;
    this.httpStatus = httpStatus;
  }

  private static String safeId(String id) {
    return id != null && id.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
        ? id
        : "";
  }

  public static String newId() {
    return UUID.randomUUID().toString();
  }

  public static Operation tool(String name) {
    if (name == null) return Operation.UNKNOWN_TOOL;
    switch (name) {
      case "phone_status":
        return Operation.STATUS;
      case "phone_read_screen":
        return Operation.READ_SCREEN;
      case "phone_screenshot":
        return Operation.SCREENSHOT;
      case "phone_action":
        return Operation.ACTION;
      case "phone_finish_task":
        return Operation.FINISH_TASK;
      case "phone_wait_for_authentication":
        return Operation.WAIT_AUTHENTICATION;
      default:
        return Operation.UNKNOWN_TOOL;
    }
  }

  public static DiagnosticEvent event(Area area, Operation operation, Outcome outcome, Code code) {
    return new DiagnosticEvent(area, operation, outcome, code, "", "", 0, null, null);
  }

  public static DiagnosticEvent started(
      Area area, Operation operation, String request, String task) {
    return new DiagnosticEvent(
        area, operation, Outcome.STARTED, Code.STARTED, request, task, 0, null, null);
  }

  public static DiagnosticEvent problem(
      Area area, Operation operation, Exception error, String request) {
    return new DiagnosticEvent(
        area,
        operation,
        error instanceof InterruptedException || error instanceof CancellationException
            ? Outcome.CANCELLED
            : Outcome.ERROR,
        exceptionCode(operation, error),
        request,
        "",
        0,
        null,
        null);
  }

  public static DiagnosticEvent http(Operation operation, int status, String request) {
    return new DiagnosticEvent(
        Area.TUNNEL,
        operation,
        Outcome.ERROR,
        status == 404 && operation == Operation.DELIVER_RESPONSE
            ? Code.RESPONSE_EXPIRED
            : Code.HTTP_ERROR,
        request,
        "",
        0,
        null,
        status);
  }

  public static DiagnosticEvent protocol(String trace, JSONObject request, JSONObject error) {
    boolean unsupported = error.optInt("code") == -32601;
    DiagnosticEvent event =
        new DiagnosticEvent(
            Area.TOOL,
            Operation.PROCESS_REQUEST,
            unsupported ? Outcome.UNSUPPORTED : Outcome.ERROR,
            unsupported ? Code.UNSUPPORTED_CAPABILITY : Code.PROTOCOL_ERROR,
            trace,
            "",
            0,
            null,
            null);
    event.rpcErrorCode = error.optInt("code");
    String method = request.optString("method");
    event.rpcMethod =
        method.matches(
                "initialize|ping|tools/list|tools/call|tools/search|resources/list|resources/templates/list|resources/read|resources/subscribe|resources/unsubscribe|prompts/list|prompts/get|logging/setLevel|completion/complete|notifications/initialized|notifications/cancelled|roots/list|sampling/createMessage|elicitation/create|tasks/get|tasks/list|tasks/result|tasks/cancel")
            ? method
            : "OTHER";
    return event;
  }

  private static Code exceptionCode(Operation operation, Exception error) {
    if (error instanceof InterruptedException || error instanceof CancellationException)
      return Code.CANCELLED;
    if (error instanceof InterruptedIOException || error instanceof TimeoutException)
      return Code.TIMEOUT;
    if (error instanceof SecurityException) return Code.PERMISSION_DENIED;
    if (error instanceof IllegalArgumentException || error instanceof JSONException)
      return Code.INVALID_ARGUMENTS;
    if (operation == Operation.READ_SCREEN) return Code.UI_INSPECTION_FAILED;
    if (operation == Operation.FINISH_TASK) return Code.RESTORE_FAILED;
    if (error instanceof IOException) return Code.IO_ERROR;
    return Code.TOOL_FAILED;
  }

  public static DiagnosticEvent completed(
      Area area,
      Operation operation,
      String request,
      String task,
      long durationMs,
      JSONObject result,
      Exception error) {
    if (error != null)
      return new DiagnosticEvent(
          area,
          operation,
          error instanceof InterruptedException || error instanceof CancellationException
              ? Outcome.CANCELLED
              : Outcome.ERROR,
          exceptionCode(operation, error),
          request,
          task,
          durationMs,
          null,
          null);
    boolean failed = result != null && (result.optBoolean("isError") || result.has("error"));
    JSONObject data = result;
    if (result != null && result.has("content")) {
      data = null;
      JSONArray content = result.optJSONArray("content");
      if (content != null)
        for (int i = 0; i < content.length(); i++) {
          JSONObject item = content.optJSONObject(i);
          if (item == null || !item.optString("type").equals("text")) continue;
          try {
            data = new JSONObject(item.optString("text"));
            break;
          } catch (JSONException notStructured) {
            /* Do not log arbitrary content. */
          }
        }
    }
    Integer exit = null;
    if (data != null) {
      JSONObject command = data.optJSONObject("action");
      if (command == null) command = data;
      if (command.has("exitCode")) {
        exit = command.optInt("exitCode", -1);
        failed |= exit != 0;
      }
      JSONObject auth = data.optJSONObject("authentication");
      if (auth == null && data.optJSONObject("screen") != null)
        auth = data.optJSONObject("screen").optJSONObject("authentication");
      if (!failed
          && (data.optBoolean("keepWaiting") || (auth != null && auth.optBoolean("required"))))
        return new DiagnosticEvent(
            area,
            operation,
            Outcome.WAITING,
            Code.AUTHENTICATION_REQUIRED,
            request,
            task,
            durationMs,
            exit,
            null);
      if (!failed
          && (data.optBoolean("cancelled")
              || data.optString("status").equals("cancelled")
              || (auth != null && auth.optBoolean("cancelled"))))
        return new DiagnosticEvent(
            area,
            operation,
            Outcome.CANCELLED,
            Code.CANCELLED,
            request,
            task,
            durationMs,
            exit,
            null);
    }
    Code code =
        !failed
            ? Code.NONE
            : operation == Operation.FINISH_TASK ? Code.RESTORE_FAILED : Code.TOOL_FAILED;
    return new DiagnosticEvent(
        area,
        operation,
        failed ? Outcome.ERROR : Outcome.SUCCESS,
        code,
        request,
        task,
        durationMs,
        exit,
        null);
  }

  public JSONObject json() throws JSONException {
    JSONObject entry =
        new JSONObject()
            .put("time", time)
            .put("area", area.name())
            .put("operation", operation.name())
            .put("outcome", outcome.name())
            .put("code", code.name())
            .put("explanation", code.explanation)
            .put("durationMs", durationMs);
    if (!requestId.isEmpty()) entry.put("requestId", requestId);
    if (!taskId.isEmpty()) entry.put("taskId", taskId);
    if (exitCode != null) entry.put("exitCode", exitCode);
    if (httpStatus != null) entry.put("httpStatus", httpStatus);
    if (rpcErrorCode != null) entry.put("rpcErrorCode", rpcErrorCode);
    if (rpcMethod != null) entry.put("rpcMethod", rpcMethod);
    return entry;
  }
}
