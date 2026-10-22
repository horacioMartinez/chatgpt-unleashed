package com.horacio.unleashed;

import android.app.*;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import org.json.*;

/** Local technical history. Reports are exported only to the owner's chosen document. */
public class DiagnosticsActivity extends Activity {
  private static final int EXPORT_REPORT = 71;
  private TextView summary;
  private LinearLayout entries;
  private CheckBox errorsOnly;
  private Button export;
  private final ExecutorService files = Executors.newSingleThreadExecutor();
  private int refreshGeneration;

  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private TextView text(String value, int size, int color) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setPadding(0, dp(8), 0, dp(8));
    return view;
  }

  private Button button(int label, Runnable action) {
    Button view = new Button(this);
    view.setText(label);
    view.setAllCaps(false);
    view.setOnClickListener(v -> action.run());
    return view;
  }

  private void row(LinearLayout parent, Button... buttons) {
    LinearLayout row = new LinearLayout(this);
    for (Button button : buttons) row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    parent.addView(row);
  }

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    Diagnostics.init(this);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(18), dp(8), dp(18), dp(24));
    body.setBackgroundColor(MainActivity.BG);
    body.addView(text(getString(R.string.diagnostics_title), 26, MainActivity.INK));
    body.addView(text(getString(R.string.diagnostics_help), 13, MainActivity.INK));
    summary = text("", 13, MainActivity.MINT);
    body.addView(summary);
    errorsOnly = new CheckBox(this);
    errorsOnly.setText(R.string.diagnostics_errors_only);
    errorsOnly.setTextColor(MainActivity.INK);
    errorsOnly.setChecked(saved == null || saved.getBoolean("errorsOnly", true));
    errorsOnly.setOnCheckedChangeListener((button, checked) -> refresh());
    body.addView(errorsOnly);
    export = button(R.string.diagnostics_export, this::chooseExport);
    row(body, button(R.string.diagnostics_refresh, this::refresh), export);
    row(
        body,
        button(
            R.string.diagnostics_clear,
            () ->
                new AlertDialog.Builder(this)
                    .setTitle(R.string.diagnostics_clear)
                    .setMessage(R.string.diagnostics_clear_confirmation)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(
                        R.string.diagnostics_clear,
                        (dialog, which) ->
                            Diagnostics.clear()
                                .whenComplete(
                                    (ignored, error) ->
                                        runOnUiThread(
                                            () -> {
                                              if (isDestroyed()) return;
                                              if (error == null) refresh();
                                              else
                                                summary.setText(R.string.diagnostics_unavailable);
                                            })))
                    .show()),
        button(R.string.diagnostics_back, this::finish));
    entries = new LinearLayout(this);
    entries.setOrientation(LinearLayout.VERTICAL);
    ScrollView history = new ScrollView(this);
    history.addView(entries);
    body.addView(history, new LinearLayout.LayoutParams(-1, 0, 1));
    body.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
          view.setPadding(
              dp(18) + bars.left, dp(8) + bars.top, dp(18) + bars.right, dp(16) + bars.bottom);
          return insets;
        });
    setContentView(body);
  }

  @Override
  protected void onResume() {
    super.onResume();
    refresh();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putBoolean("errorsOnly", errorsOnly.isChecked());
    super.onSaveInstanceState(state);
  }

  private void refresh() {
    int generation = ++refreshGeneration;
    Diagnostics.recent(errorsOnly.isChecked())
        .whenComplete(
            (events, error) ->
                runOnUiThread(
                    () -> {
                      if (isDestroyed() || generation != refreshGeneration) return;
                      entries.removeAllViews();
                      if (error != null) {
                        summary.setText(R.string.diagnostics_unavailable);
                        return;
                      }
                      String summaryText =
                          getString(R.string.diagnostics_summary, events.length())
                              + (!Diagnostics.available() || Diagnostics.droppedEvents() > 0
                                  ? "\n"
                                      + getString(
                                          R.string.diagnostics_dropped, Diagnostics.droppedEvents())
                                  : "");
                      summary.setText(summaryText);
                      if (events.length() == 0)
                        entries.addView(
                            text(getString(R.string.diagnostics_empty), 15, MainActivity.INK));
                      for (int i = events.length() - 1; i >= 0; i--) {
                        JSONObject event = events.optJSONObject(i);
                        if (event == null) continue;
                        String time = event.optString("time");
                        try {
                          time =
                              DateTimeFormatter.ofPattern("MMM d HH:mm:ss")
                                  .format(Instant.parse(time).atZone(ZoneId.systemDefault()));
                        } catch (RuntimeException invalidTime) {
                          /* Use stored UTC timestamp. */
                        }
                        String value =
                            time
                                + "  "
                                + event.optString("outcome")
                                + "\n"
                                + event.optString("area")
                                + " · "
                                + event.optString("operation")
                                + "\n"
                                + event.optString("code")
                                + " · "
                                + event.optLong("durationMs")
                                + " ms\n"
                                + event.optString("explanation");
                        if (event.has("exitCode"))
                          value += "\nExit code: " + event.optInt("exitCode");
                        if (event.has("httpStatus"))
                          value += "\nHTTP status: " + event.optInt("httpStatus");
                        if (event.has("rpcErrorCode"))
                          value +=
                              "\nRPC: "
                                  + event.optString("rpcMethod")
                                  + " · "
                                  + event.optInt("rpcErrorCode");
                        if (event.has("requestId")) {
                          String request = event.optString("requestId");
                          value +=
                              "\nRequest: " + request.substring(0, Math.min(8, request.length()));
                        }
                        TextView item =
                            text(
                                value,
                                12,
                                event.optString("outcome").equals("ERROR")
                                    ? android.graphics.Color.rgb(255, 210, 117)
                                    : MainActivity.INK);
                        item.setTypeface(Typeface.MONOSPACE);
                        item.setTextIsSelectable(true);
                        entries.addView(item);
                      }
                    }));
  }

  private void chooseExport() {
    Intent intent =
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(
                Intent.EXTRA_TITLE, "unleashed-diagnostics-" + java.time.LocalDate.now() + ".json");
    try {
      startActivityForResult(intent, EXPORT_REPORT);
    } catch (RuntimeException unavailable) {
      exportFailed();
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != EXPORT_REPORT || result != RESULT_OK || data == null || data.getData() == null)
      return;
    Uri destination = data.getData();
    export.setEnabled(false);
    Diagnostics.report()
        .thenAcceptAsync(
            report -> {
              try (OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
                if (out == null) throw new java.io.IOException("Export destination unavailable");
                out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
              } catch (Exception error) {
                throw new CompletionException(error);
              }
            },
            files)
        .whenComplete(
            (ignored, error) -> {
              if (error == null)
                Diagnostics.record(
                    DiagnosticEvent.event(
                        DiagnosticEvent.Area.DIAGNOSTICS,
                        DiagnosticEvent.Operation.EXPORT,
                        DiagnosticEvent.Outcome.SUCCESS,
                        DiagnosticEvent.Code.NONE));
              runOnUiThread(
                  () -> {
                    if (isDestroyed()) return;
                    export.setEnabled(true);
                    if (error == null) {
                      Toast.makeText(this, R.string.diagnostics_exported, Toast.LENGTH_LONG).show();
                      refresh();
                    } else exportFailed();
                  });
            });
  }

  private void exportFailed() {
    Diagnostics.record(
        DiagnosticEvent.event(
            DiagnosticEvent.Area.DIAGNOSTICS,
            DiagnosticEvent.Operation.EXPORT,
            DiagnosticEvent.Outcome.ERROR,
            DiagnosticEvent.Code.EXPORT_FAILED));
    summary.setText(R.string.diagnostics_export_failed);
  }

  @Override
  protected void onDestroy() {
    files.shutdown();
    super.onDestroy();
  }
}
