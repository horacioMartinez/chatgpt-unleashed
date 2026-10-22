package com.horacio.unleashed;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.*;

/** Two bounded JSONL files in private storage. Safe across partial writes and app restarts. */
public final class DiagnosticStore {
  public static final long FILE_LIMIT = 1024 * 1024;
  private final File current, previous;
  private final long limit;

  public DiagnosticStore(File directory) throws IOException {
    this(directory, FILE_LIMIT);
  }

  DiagnosticStore(File directory, long limit) throws IOException {
    if (limit < 1024) throw new IllegalArgumentException("Log limit is too small");
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IOException("Diagnostics storage unavailable");
    current = new File(directory, "events.jsonl");
    previous = new File(directory, "events.previous.jsonl");
    this.limit = limit;
  }

  public synchronized void append(DiagnosticEvent event) throws IOException {
    final byte[] line;
    try {
      line = (event.json().toString() + "\n").getBytes(StandardCharsets.UTF_8);
    } catch (JSONException invalid) {
      throw new IOException("Invalid diagnostic event");
    }
    if (line.length > limit) throw new IOException("Diagnostic event exceeds limit");
    repairPartialTail();
    if (current.length() + line.length > limit)
      Files.move(current.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING);
    try (FileOutputStream out = new FileOutputStream(current, true)) {
      out.write(line);
      if (event.outcome == DiagnosticEvent.Outcome.ERROR) out.getFD().sync();
    }
  }

  private void repairPartialTail() throws IOException {
    if (!current.exists() || current.length() == 0) return;
    try (RandomAccessFile file = new RandomAccessFile(current, "rw")) {
      long at = file.length() - 1;
      file.seek(at);
      if (file.read() == '\n') return;
      while (at >= 0) {
        file.seek(at);
        if (file.read() == '\n') break;
        at--;
      }
      file.setLength(at + 1);
    }
  }

  public synchronized JSONArray read(boolean errorsOnly, int maximum) throws IOException {
    java.util.ArrayDeque<JSONObject> entries = new java.util.ArrayDeque<>();
    for (File file : new File[] {previous, current}) {
      if (!file.exists()) continue;
      try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
        String line;
        while ((line = reader.readLine()) != null) {
          try {
            JSONObject entry = new JSONObject(line);
            DiagnosticEvent.Area.valueOf(entry.getString("area"));
            DiagnosticEvent.Outcome.valueOf(entry.getString("outcome"));
            entry.put(
                "explanation", DiagnosticEvent.Code.valueOf(entry.getString("code")).explanation);
            if (errorsOnly && !entry.optString("outcome").equals("ERROR")) continue;
            entries.addLast(entry);
            if (maximum > 0 && entries.size() > maximum) entries.removeFirst();
          } catch (JSONException | IllegalArgumentException partialOrDamaged) {
            /* Retain the rest of the history. */
          }
        }
      }
    }
    JSONArray result = new JSONArray();
    for (JSONObject entry : entries) result.put(entry);
    return result;
  }

  public synchronized void clear() throws IOException {
    Files.deleteIfExists(current.toPath());
    Files.deleteIfExists(previous.toPath());
  }
}
