package io.github.almeida96.runharbor;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

final class Api implements AutoCloseable {
  private final Store store;
  private final Engine engine;
  private final Provider provider;
  private final Path workspace;
  private final byte[] token;
  private final HttpServer server;
  private final java.util.concurrent.ExecutorService workers =
      Executors.newVirtualThreadPerTaskExecutor();
  private final java.util.concurrent.ExecutorService readers =
      Executors.newVirtualThreadPerTaskExecutor();
  private final Semaphore capacity = new Semaphore(16);
  private static final String ID = "[a-f0-9-]{36}";

  Api(Store store, Engine engine, Provider provider, Path workspace, String token, int port)
      throws IOException {
    validateToken(token);
    Json.require(port >= 0 && port <= 65535, "Invalid port");
    this.store = store;
    this.engine = engine;
    this.provider = provider;
    this.workspace = workspace;
    this.token = token.getBytes(UTF_8);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 32);
    server.setExecutor(workers);
    server.createContext("/", this::handle);
    server.start();
  }

  int port() {
    return server.getAddress().getPort();
  }

  static void validateToken(String value) {
    Json.require(
        value != null
            && value.length() >= 16
            && value.length() <= 8192
            && value.chars().allMatch(c -> c >= 33 && c <= 126),
        "Token environment variable must contain 16–8192 printable non-space characters");
  }

  private void handle(HttpExchange exchange) {
    try (exchange) {
      if (!capacity.tryAcquire()) {
        send(exchange, 503, Json.obj("error", "BUSY"));
        return;
      }
      try {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (!Set.of("127.0.0.1:" + port(), "localhost:" + port())
            .contains(host == null ? "" : host)) {
          send(exchange, 400, Json.obj("error", "INVALID_HOST"));
          return;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.equals("http://" + host)) {
          send(exchange, 403, Json.obj("error", "ORIGIN_REJECTED"));
          return;
        }
        String path = exchange.getRequestURI().getRawPath();
        if (!path.startsWith("/api/")) {
          asset(exchange, path);
          return;
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null
            || !authorization.startsWith("Bearer ")
            || !MessageDigest.isEqual(token, authorization.substring(7).getBytes(UTF_8))) {
          send(exchange, 401, Json.obj("error", "UNAUTHORIZED"));
          return;
        }
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        switch (path) {
          case "/api/meta" -> {
            method(exchange, "GET");
            Json.require(query.isEmpty(), "Unexpected query");
            send(
                exchange,
                200,
                Json.obj(
                    "version",
                    Main.VERSION,
                    "provider",
                    provider.description(),
                    "workspace",
                    workspace.getFileName().toString(),
                    "engine",
                    engine.status(),
                    "tools",
                    Tools.definitions()));
          }
          case "/api/runs" -> {
            if (exchange.getRequestMethod().equals("GET")) {
              Json.require(
                  query.keySet().stream()
                      .allMatch(key -> key.equals("state") || key.equals("limit")),
                  "Unexpected query");
              int limit = Integer.parseInt(query.getOrDefault("limit", "100"));
              send(
                  exchange,
                  200,
                  Json.obj(
                      "runs",
                      store.list(query.get("state"), limit).stream()
                          .map(Model.Run::summary)
                          .toList(),
                      "limit",
                      limit));
            } else {
              method(exchange, "POST");
              Json.require(query.isEmpty(), "Unexpected query");
              String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
              Json.require(key != null, "Idempotency-Key is required");
              Model.Request request = Model.Request.parse(body(exchange));
              Model.Run run = store.existing(key, request, provider.key());
              if (run == null)
                run = store.create(key, request, Workspace.capture(workspace), provider.key());
              send(exchange, 201, run.detail());
            }
          }
          default -> runRoute(exchange, path, query);
        }
      } catch (ResponseSent e) {
        /* The body timeout response was flushed before cancelling its reader. */
      } catch (MethodError e) {
        exchange.getResponseHeaders().set("Allow", e.allowed);
        send(exchange, 405, Json.obj("error", "METHOD_NOT_ALLOWED"));
      } catch (BodyError e) {
        send(exchange, e.status, Json.obj("error", e.code));
      } catch (Json.Conflict e) {
        send(exchange, 409, Json.obj("error", "STATE_CONFLICT", "message", e.getMessage()));
      } catch (Json.Invalid e) {
        send(exchange, 400, Json.obj("error", "INVALID_INPUT", "message", e.getMessage()));
      } catch (IllegalArgumentException e) {
        send(exchange, 400, Json.obj("error", "INVALID_INPUT"));
      } catch (Exception e) {
        try {
          send(exchange, 500, Json.obj("error", "INTERNAL_ERROR"));
        } catch (IOException disconnected) {
          /* Response cannot be delivered after disconnect. */
        }
      } finally {
        capacity.release();
      }
    } catch (IOException e) {
      /* Requests may disconnect while responses are being written. */
    }
  }

  private void runRoute(HttpExchange exchange, String path, Map<String, String> query)
      throws Exception {
    String[] parts = path.split("/");
    if (parts.length < 4 || !parts[2].equals("runs") || !parts[3].matches(ID)) {
      send(exchange, 404, Json.obj("error", "NOT_FOUND"));
      return;
    }
    String id = parts[3];
    Model.Run run = store.get(id);
    if (run == null) {
      send(exchange, 404, Json.obj("error", "NOT_FOUND"));
      return;
    }
    if (parts.length == 4) {
      method(exchange, "GET");
      Json.require(query.isEmpty(), "Unexpected query");
      send(exchange, 200, run.detail());
      return;
    }
    if (parts.length == 5 && parts[4].equals("events")) {
      method(exchange, "GET");
      Json.require(query.keySet().stream().allMatch("after"::equals), "Unexpected query");
      var events = store.events(id, Long.parseLong(query.getOrDefault("after", "0")));
      send(exchange, 200, Json.obj("events", events));
      return;
    }
    Json.require(query.isEmpty(), "Unexpected query");
    if (parts.length == 5 && parts[4].equals("sources")) {
      method(exchange, "GET");
      send(exchange, 200, Json.obj("sources", Workspace.catalog(store.sources(id))));
      return;
    }
    if (parts.length == 6 && parts[4].equals("evidence") && parts[5].matches("[a-f0-9]{64}")) {
      method(exchange, "GET");
      JsonNode evidence = run.checkpoint().get("evidence").get(parts[5]);
      if (evidence == null) send(exchange, 404, Json.obj("error", "NOT_FOUND"));
      else send(exchange, 200, evidence);
      return;
    }
    if (parts.length == 5 && parts[4].equals("export")) {
      method(exchange, "GET");
      if (run.report() == null) throw new Json.Conflict("No report is available yet");
      exchange
          .getResponseHeaders()
          .set("Content-Disposition", "attachment; filename=run-" + id + ".md");
      sendBytes(exchange, 200, "text/markdown; charset=utf-8", export(run).getBytes(UTF_8));
      return;
    }
    if (parts.length == 5
        && Set.of("pause", "resume", "cancel", "approve", "revise").contains(parts[4])) {
      method(exchange, "POST");
      JsonNode body = body(exchange);
      Json.fields(body, Set.of(), parts[4].equals("revise") ? Set.of("feedback") : Set.of());
      String feedback = body.has("feedback") ? Json.text(body.get("feedback"), 1, 4000) : null;
      send(exchange, 200, engine.control(id, parts[4], feedback).detail());
      return;
    }
    send(exchange, 404, Json.obj("error", "NOT_FOUND"));
  }

  static String export(Model.Run run) {
    JsonNode report = run.report();
    var text =
        new StringBuilder("# ")
            .append(report.path("title").asText().replace('\n', ' '))
            .append("\n\n")
            .append("Status: ")
            .append(run.state())
            .append(" · Run: ")
            .append(run.id())
            .append("\n\n")
            .append(report.path("markdown").asText())
            .append("\n\n## Sources\n\n");
    for (JsonNode citation : report.path("citations"))
      text.append("- ")
          .append(citation.path("path").asText())
          .append(":L")
          .append(citation.path("startLine").asInt())
          .append("–L")
          .append(citation.path("endLine").asInt())
          .append(" (`")
          .append(citation.path("sourceId").asText())
          .append("`)\n");
    return text.toString();
  }

  private JsonNode body(HttpExchange exchange) throws Exception {
    String type = exchange.getRequestHeaders().getFirst("Content-Type");
    if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json"))
      throw new BodyError(415, "EXPECTED_JSON");
    String length = exchange.getRequestHeaders().getFirst("Content-Length");
    if (length != null && Long.parseLong(length) > 65536)
      throw new BodyError(413, "REQUEST_TOO_LARGE");
    var future = readers.submit(() -> exchange.getRequestBody().readNBytes(65537));
    try {
      byte[] bytes = future.get(5, TimeUnit.SECONDS);
      if (bytes.length > 65536) throw new BodyError(413, "REQUEST_TOO_LARGE");
      try {
        return Json.parse(bytes);
      } catch (IOException e) {
        throw new Json.Invalid("Invalid JSON document");
      }
    } catch (java.util.concurrent.TimeoutException e) {
      // Flush before interrupting the body reader, which can close the underlying connection.
      send(exchange, 408, Json.obj("error", "BODY_TIMEOUT"));
      throw new ResponseSent();
    } finally {
      if (!future.isDone()) future.cancel(true);
    }
  }

  private static Map<String, String> query(String raw) {
    var values = new HashMap<String, String>();
    if (raw == null) return values;
    Json.require(raw.length() <= 200, "Query too long");
    for (String pair : raw.split("&", -1)) {
      String[] parts = pair.split("=", -1);
      Json.require(
          parts.length == 2
              && parts[0].matches("[a-z]+")
              && parts[1].matches("[A-Za-z0-9_-]+")
              && values.put(parts[0], parts[1]) == null,
          "Invalid query string");
    }
    return values;
  }

  private void asset(HttpExchange exchange, String path) throws Exception {
    method(exchange, "GET");
    Map<String, String> files =
        Map.of("/", "index.html", "/app.js", "app.js", "/styles.css", "styles.css");
    String file = files.get(path);
    if (file == null) {
      send(exchange, 404, Json.obj("error", "NOT_FOUND"));
      return;
    }
    try (var resource = Api.class.getResourceAsStream("/web/" + file)) {
      if (resource == null) throw new IOException("Missing web resource");
      sendBytes(
          exchange,
          200,
          file.endsWith("html")
              ? "text/html; charset=utf-8"
              : file.endsWith("css") ? "text/css; charset=utf-8" : "text/javascript; charset=utf-8",
          resource.readAllBytes());
    }
  }

  private static void method(HttpExchange exchange, String method) {
    if (!exchange.getRequestMethod().equals(method)) throw new MethodError(method);
  }

  private static void send(HttpExchange exchange, int code, JsonNode value) throws IOException {
    sendBytes(exchange, code, "application/json; charset=utf-8", value.toString().getBytes(UTF_8));
  }

  private static void sendBytes(HttpExchange exchange, int code, String contentType, byte[] bytes)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    exchange
        .getResponseHeaders()
        .set(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
                + " connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action"
                + " 'self'");
    exchange.sendResponseHeaders(code, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.getResponseBody().flush();
  }

  private static final class MethodError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final String allowed;

    MethodError(String allowed) {
      this.allowed = allowed;
    }
  }

  private static final class ResponseSent extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  private static final class BodyError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final int status;
    final String code;

    BodyError(int status, String code) {
      this.status = status;
      this.code = code;
    }
  }

  @Override
  public void close() {
    server.stop(0);
    workers.shutdownNow();
    readers.shutdownNow();
  }
}
