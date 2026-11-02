package io.github.almeida96.runharbor;

import static io.github.almeida96.runharbor.Model.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class Store implements AutoCloseable {
  private static final int APPLICATION_ID = 0x52484252;
  private final Connection connection;

  @FunctionalInterface
  private interface Work<T> {
    T run() throws SQLException;
  }

  Store(Path path) throws Exception {
    Files.createDirectories(path.toAbsolutePath().getParent());
    connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
    try {
      try (var s = connection.createStatement()) {
        s.execute("PRAGMA busy_timeout=5000");
        s.execute("PRAGMA foreign_keys=ON");
        int version, app, tables;
        try (var r = s.executeQuery("PRAGMA user_version")) {
          r.next();
          version = r.getInt(1);
        }
        try (var r = s.executeQuery("PRAGMA application_id")) {
          r.next();
          app = r.getInt(1);
        }
        try (var r = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table'")) {
          r.next();
          tables = r.getInt(1);
        }
        Json.require(
            (app == APPLICATION_ID && version == 1) || (app == 0 && version == 0 && tables == 0),
            "Not a supported RunHarbor database");
        s.execute("PRAGMA journal_mode=WAL");
      }
      transaction(
          () -> {
            try (var s = connection.createStatement()) {
              s.execute(
                  """
CREATE TABLE IF NOT EXISTS runs (
  id TEXT PRIMARY KEY, client_key TEXT NOT NULL UNIQUE, request_hash TEXT NOT NULL,
  title TEXT NOT NULL, task TEXT NOT NULL, state TEXT NOT NULL, reason TEXT NOT NULL,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, generation INTEGER NOT NULL DEFAULT 0,
  requests INTEGER NOT NULL DEFAULT 0, tools INTEGER NOT NULL DEFAULT 0, tokens INTEGER NOT NULL DEFAULT 0,
  max_requests INTEGER NOT NULL, max_tools INTEGER NOT NULL, max_tokens INTEGER NOT NULL,
  provider_key TEXT NOT NULL, checkpoint_json TEXT NOT NULL, report_json TEXT, skipped_json TEXT NOT NULL)
""");
              s.execute(
                  """
CREATE TABLE IF NOT EXISTS sources (
  run_id TEXT NOT NULL REFERENCES runs(id), path TEXT NOT NULL, hash TEXT NOT NULL,
  content TEXT NOT NULL, lines INTEGER NOT NULL, PRIMARY KEY(run_id,path))
""");
              s.execute(
                  """
CREATE TABLE IF NOT EXISTS events (
  seq INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL REFERENCES runs(id),
  type TEXT NOT NULL, at INTEGER NOT NULL, data_json TEXT NOT NULL)
""");
              s.execute("CREATE INDEX IF NOT EXISTS event_run ON events(run_id,seq)");
              s.execute("CREATE INDEX IF NOT EXISTS run_state ON runs(state,created_at)");
              s.execute("PRAGMA user_version=1");
              s.execute("PRAGMA application_id=" + APPLICATION_ID);
            }
            return null;
          });
    } catch (Exception e) {
      connection.close();
      throw e;
    }
  }

  private <T> T transaction(Work<T> work) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("BEGIN IMMEDIATE");
    }
    try {
      T result = work.run();
      try (var s = connection.createStatement()) {
        s.execute("COMMIT");
      }
      return result;
    } catch (SQLException | RuntimeException e) {
      try (var s = connection.createStatement()) {
        s.execute("ROLLBACK");
      } catch (SQLException rollback) {
        e.addSuppressed(rollback);
      }
      throw e;
    }
  }

  private static String fingerprint(Request request, String providerKey) {
    return Json.hash(
        Json.obj(
                "title",
                request.title(),
                "task",
                request.task(),
                "maxRequests",
                request.maxRequests(),
                "maxTools",
                request.maxTools(),
                "maxTokens",
                request.maxTokens(),
                "provider",
                providerKey)
            .toString());
  }

  synchronized Run existing(String clientKey, Request request, String providerKey)
      throws SQLException {
    Json.require(
        clientKey.matches("[A-Za-z0-9._-]{8,100}"),
        "Idempotency-Key must contain 8–100 safe characters");
    try (var s =
        connection.prepareStatement("SELECT id,request_hash FROM runs WHERE client_key=?")) {
      s.setString(1, clientKey);
      try (var r = s.executeQuery()) {
        if (r.next()) {
          if (!r.getString("request_hash").equals(fingerprint(request, providerKey)))
            throw new Json.Conflict("Idempotency key already used with different settings");
          return get(r.getString("id"));
        }
      }
    }
    return null;
  }

  synchronized Run create(
      String clientKey, Request request, Workspace.Snapshot snapshot, String providerKey)
      throws SQLException {
    return transaction(
        () -> {
          Run previous = existing(clientKey, request, providerKey);
          if (previous != null) return previous;
          String hash = fingerprint(request, providerKey);
          long now = System.currentTimeMillis();
          String id = UUID.randomUUID().toString();
          try (var s =
              connection.prepareStatement(
                  "INSERT INTO runs"
                      + " (id,client_key,request_hash,title,task,state,reason,created_at,updated_at,max_requests,max_tools,max_tokens,provider_key,checkpoint_json,skipped_json)"
                      + " VALUES (?,?,?,?,?,'QUEUED','CREATED',?,?,?,?,?,?,?,?)")) {
            s.setString(1, id);
            s.setString(2, clientKey);
            s.setString(3, hash);
            s.setString(4, request.title());
            s.setString(5, request.task());
            s.setLong(6, now);
            s.setLong(7, now);
            s.setInt(8, request.maxRequests());
            s.setInt(9, request.maxTools());
            s.setInt(10, request.maxTokens());
            s.setString(11, providerKey);
            s.setString(12, initial(request.task()).toString());
            s.setString(13, Json.MAPPER.valueToTree(snapshot.skipped()).toString());
            s.executeUpdate();
          }
          try (var s = connection.prepareStatement("INSERT INTO sources VALUES (?,?,?,?,?)")) {
            for (Workspace.Source source : snapshot.sources()) {
              s.setString(1, id);
              s.setString(2, source.path());
              s.setString(3, source.hash());
              s.setString(4, source.content());
              s.setInt(5, source.lines());
              s.addBatch();
            }
            s.executeBatch();
          }
          event(
              id,
              "RUN_CREATED",
              Json.obj("files", snapshot.sources().size(), "skipped", snapshot.skipped().size()));
          return get(id);
        });
  }

  synchronized Run get(String id) throws SQLException {
    try (var s = connection.prepareStatement("SELECT * FROM runs WHERE id=?")) {
      s.setString(1, id);
      try (var r = s.executeQuery()) {
        return r.next() ? row(r) : null;
      }
    }
  }

  synchronized List<Run> list(String state, int limit) throws SQLException {
    Json.require(limit >= 1 && limit <= 100, "Invalid list limit");
    if (state != null) State.valueOf(state);
    var result = new ArrayList<Run>();
    try (var s =
        connection.prepareStatement(
            "SELECT * FROM runs"
                + (state == null ? "" : " WHERE state=?")
                + " ORDER BY created_at DESC,id LIMIT ?")) {
      int index = 1;
      if (state != null) s.setString(index++, state);
      s.setInt(index, limit);
      try (var r = s.executeQuery()) {
        while (r.next()) result.add(row(r));
      }
    }
    return List.copyOf(result);
  }

  synchronized List<Workspace.Source> sources(String id) throws SQLException {
    var result = new ArrayList<Workspace.Source>();
    try (var s =
        connection.prepareStatement("SELECT * FROM sources WHERE run_id=? ORDER BY path")) {
      s.setString(1, id);
      try (var r = s.executeQuery()) {
        while (r.next())
          result.add(
              new Workspace.Source(
                  r.getString("path"),
                  r.getString("hash"),
                  r.getString("content"),
                  r.getInt("lines")));
      }
    }
    return List.copyOf(result);
  }

  synchronized ArrayNode events(String id, long after) throws SQLException {
    Json.require(after >= 0, "Invalid event cursor");
    var result = Json.array();
    try (var s =
        connection.prepareStatement(
            "SELECT * FROM events WHERE run_id=? AND seq>? ORDER BY seq LIMIT 200")) {
      s.setString(1, id);
      s.setLong(2, after);
      try (var r = s.executeQuery()) {
        while (r.next())
          result.add(
              Json.obj(
                  "seq",
                  r.getLong("seq"),
                  "type",
                  r.getString("type"),
                  "at",
                  r.getLong("at"),
                  "data",
                  Json.parse(r.getString("data_json"))));
      }
    }
    return result;
  }

  synchronized void recover(String provider) throws SQLException {
    transaction(
        () -> {
          var ids = new ArrayList<String>();
          try (var s =
              connection.prepareStatement(
                  "SELECT id FROM runs WHERE state='RUNNING' OR (state='QUEUED' AND"
                      + " provider_key<>?)")) {
            s.setString(1, provider);
            try (var r = s.executeQuery()) {
              while (r.next()) ids.add(r.getString(1));
            }
          }
          for (String id : ids) {
            state(id, State.PAUSED, "PROCESS_INTERRUPTED");
            event(
                id,
                "RECOVERED",
                Json.obj("reason", "Resume explicitly from the last committed checkpoint"));
          }
          return null;
        });
  }

  synchronized Run claim(String provider) throws SQLException {
    return claim(provider, Set.of());
  }

  synchronized Run claim(String provider, Set<String> excluded) throws SQLException {
    return transaction(
        () -> {
          String id = null;
          String exclusion =
              excluded.isEmpty()
                  ? ""
                  : " AND id NOT IN ("
                      + String.join(",", java.util.Collections.nCopies(excluded.size(), "?"))
                      + ")";
          try (var s =
              connection.prepareStatement(
                  "SELECT id FROM runs WHERE state='QUEUED' AND provider_key=?"
                      + exclusion
                      + " ORDER BY created_at,id LIMIT 1")) {
            s.setString(1, provider);
            int index = 2;
            for (String value : excluded) s.setString(index++, value);
            try (var r = s.executeQuery()) {
              if (r.next()) id = r.getString(1);
            }
          }
          if (id == null) return null;
          state(id, State.RUNNING, "RUNNING");
          event(id, "RUN_STARTED", Json.obj());
          return get(id);
        });
  }

  synchronized Run control(String id, String action, String feedback, String provider)
      throws SQLException {
    return transaction(
        () -> {
          Run run = get(id);
          Json.require(run != null, "Run not found");
          switch (action) {
            case "pause" -> {
              checkState(run, Set.of(State.QUEUED, State.RUNNING));
              state(id, State.PAUSED, "OPERATOR_PAUSED");
            }
            case "cancel" -> {
              checkState(
                  run,
                  Set.of(State.QUEUED, State.RUNNING, State.PAUSED, State.REVIEW, State.FAILED));
              state(id, State.CANCELLED, "OPERATOR_CANCELLED");
            }
            case "resume" -> {
              checkState(run, Set.of(State.PAUSED, State.FAILED));
              if (!run.providerKey().equals(provider))
                throw new Json.Conflict("Run belongs to another provider configuration");
              if ((!run.hasPendingTools() && run.requests() >= run.maxRequests())
                  || run.tools() >= run.maxTools()
                  || run.tokens() >= run.maxTokens())
                throw new Json.Conflict(
                    "Execution budget exhausted; create a new run with suitable limits");
              state(id, State.QUEUED, "OPERATOR_RESUMED");
            }
            case "approve" -> {
              checkState(run, Set.of(State.REVIEW));
              state(id, State.COMPLETED, "REPORT_APPROVED");
            }
            case "revise" -> {
              checkState(run, Set.of(State.REVIEW));
              Json.require(
                  feedback != null && !feedback.isBlank() && feedback.length() <= 4000,
                  "Revision requires 1–4000 characters of feedback");
              if (!run.providerKey().equals(provider)
                  || run.requests() >= run.maxRequests()
                  || run.tools() >= run.maxTools()
                  || run.tokens() >= run.maxTokens())
                throw new Json.Conflict(
                    "Revision cannot run with this provider or remaining budget");
              ObjectNode checkpoint = run.checkpoint().deepCopy();
              ((ArrayNode) checkpoint.get("messages"))
                  .add(
                      Json.obj(
                          "role",
                          "user",
                          "content",
                          "Revise the report using this operator feedback: " + feedback));
              checkpoint.set("pending", Json.array());
              checkpoint.put("cursor", 0);
              saveCheckpoint(id, checkpoint, null);
              state(id, State.QUEUED, "REVISION_REQUESTED");
            }
            default -> throw new Json.Invalid("Unknown run action");
          }
          event(
              id,
              "OPERATOR_" + action.toUpperCase(java.util.Locale.ROOT),
              Json.obj("feedback", action.equals("revise") ? feedback : null));
          return get(id);
        });
  }

  synchronized boolean reserve(String id, int generation, boolean model) throws SQLException {
    return transaction(
        () -> {
          Run run = get(id);
          if (!active(run, generation)) return false;
          if ((model && run.requests() >= run.maxRequests())
              || (!model && run.tools() >= run.maxTools())
              || run.tokens() >= run.maxTokens()) {
            state(id, State.FAILED, "BUDGET_EXHAUSTED");
            event(id, "RUN_FAILED", Json.obj("reason", "BUDGET_EXHAUSTED"));
            return false;
          }
          try (var s =
              connection.prepareStatement(
                  "UPDATE runs SET "
                      + (model ? "requests=requests+1" : "tools=tools+1")
                      + ",updated_at=? WHERE id=?")) {
            s.setLong(1, System.currentTimeMillis());
            s.setString(2, id);
            s.executeUpdate();
          }
          event(
              id,
              model ? "MODEL_REQUESTED" : "TOOL_STARTED",
              Json.obj("attempt", model ? run.requests() + 1 : run.tools() + 1));
          return true;
        });
  }

  synchronized boolean checkpoint(
      String id,
      int generation,
      ObjectNode checkpoint,
      JsonNode report,
      long tokens,
      String eventType,
      JsonNode data)
      throws SQLException {
    return transaction(
        () -> {
          Run run = get(id);
          if (!active(run, generation)) return false;
          Json.require(
              checkpoint.toString().length() <= 400000, "Checkpoint exceeds context limit");
          saveCheckpoint(id, checkpoint, report);
          try (var s =
              connection.prepareStatement(
                  "UPDATE runs SET tokens=tokens+?,updated_at=? WHERE id=?")) {
            s.setLong(1, tokens);
            s.setLong(2, System.currentTimeMillis());
            s.setString(3, id);
            s.executeUpdate();
          }
          event(id, eventType, data);
          if (run.tokens() + tokens >= run.maxTokens()) {
            state(id, State.FAILED, "TOKEN_BUDGET_EXHAUSTED");
            event(id, "RUN_FAILED", Json.obj("reason", "TOKEN_BUDGET_EXHAUSTED"));
          } else if (report != null) {
            state(id, State.REVIEW, "REPORT_READY");
            event(id, "REPORT_READY", Json.obj("citations", report.get("citations").size()));
          }
          return true;
        });
  }

  synchronized void fail(String id, int generation, String reason) throws SQLException {
    transaction(
        () -> {
          if (active(get(id), generation)) {
            state(id, State.FAILED, reason);
            event(id, "RUN_FAILED", Json.obj("reason", reason));
          }
          return null;
        });
  }

  private static boolean active(Run run, int generation) {
    return run != null && run.state() == State.RUNNING && run.generation() == generation;
  }

  private static void checkState(Run run, Set<State> states) {
    if (!states.contains(run.state()))
      throw new Json.Conflict("Action is not valid in the current state");
  }

  private void saveCheckpoint(String id, ObjectNode checkpoint, JsonNode report)
      throws SQLException {
    try (var s =
        connection.prepareStatement("UPDATE runs SET checkpoint_json=?,report_json=? WHERE id=?")) {
      s.setString(1, checkpoint.toString());
      s.setString(2, report == null ? null : report.toString());
      s.setString(3, id);
      s.executeUpdate();
    }
  }

  private void state(String id, State value, String reason) throws SQLException {
    try (var s =
        connection.prepareStatement(
            "UPDATE runs SET state=?,reason=?,generation=generation+1,updated_at=? WHERE id=?")) {
      s.setString(1, value.name());
      s.setString(2, reason);
      s.setLong(3, System.currentTimeMillis());
      s.setString(4, id);
      s.executeUpdate();
    }
  }

  private void event(String id, String type, JsonNode data) throws SQLException {
    try (var s =
        connection.prepareStatement(
            "INSERT INTO events (run_id,type,at,data_json) VALUES (?,?,?,?)")) {
      s.setString(1, id);
      s.setString(2, type);
      s.setLong(3, System.currentTimeMillis());
      s.setString(4, data.toString());
      s.executeUpdate();
    }
  }

  private static Run row(ResultSet r) throws SQLException {
    String report = r.getString("report_json");
    return new Run(
        r.getString("id"),
        r.getString("title"),
        r.getString("task"),
        State.valueOf(r.getString("state")),
        r.getString("reason"),
        r.getLong("created_at"),
        r.getLong("updated_at"),
        r.getInt("generation"),
        r.getInt("requests"),
        r.getInt("tools"),
        r.getLong("tokens"),
        r.getInt("max_requests"),
        r.getInt("max_tools"),
        r.getInt("max_tokens"),
        r.getString("provider_key"),
        (ObjectNode) Json.parse(r.getString("checkpoint_json")),
        report == null ? null : Json.parse(report),
        Json.parse(r.getString("skipped_json")));
  }

  @Override
  public synchronized void close() throws SQLException {
    connection.close();
  }
}
