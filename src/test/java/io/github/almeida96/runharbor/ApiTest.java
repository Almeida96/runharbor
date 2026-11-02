package io.github.almeida96.runharbor;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiTest {
  @TempDir Path dir;
  static final String TOKEN = "fixture-access-token-123456";
  Store store;
  DemoProvider provider;
  Engine engine;
  Api api;
  HttpClient client;

  @BeforeEach
  void start() throws Exception {
    store = new Store(dir.resolve("api.db"));
    provider = new DemoProvider();
    engine = new Engine(store, provider, 2);
    api = new Api(store, engine, provider, Fixtures.workspace(dir), TOKEN, 0);
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stop() throws Exception {
    if (client != null) client.shutdownNow();
    if (api != null) api.close();
    if (engine != null) engine.close();
    if (store != null) store.close();
  }

  HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path))
        .timeout(Duration.ofSeconds(8));
  }

  HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString(UTF_8));
  }

  HttpRequest.Builder authorized(String path) {
    return request(path).header("Authorization", "Bearer " + TOKEN);
  }

  HttpResponse<String> post(String path, String body) throws Exception {
    return send(
        authorized(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
  }

  String create() throws Exception {
    var response =
        send(
            authorized("/api/runs")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "api-request-001")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"title\":\"Release review\",\"task\":\"Inspect rollback"
                            + " requirements.\"}")));
    assertEquals(201, response.statusCode());
    return Json.parse(response.body()).path("id").asText();
  }

  @Test
  void assetsArePublicButAllDataRequiresABearerToken() throws Exception {
    var page = send(request("/"));
    assertEquals(200, page.statusCode());
    assertTrue(page.body().contains("RunHarbor"));
    assertFalse(page.body().contains(TOKEN));
    assertTrue(
        page.headers()
            .firstValue("Content-Security-Policy")
            .orElseThrow()
            .contains("frame-ancestors 'none'"));
    assertEquals("no-store", page.headers().firstValue("Cache-Control").orElseThrow());
    assertEquals(401, send(request("/api/meta")).statusCode());
    assertEquals(
        401, send(request("/api/meta").header("Authorization", "Bearer wrong")).statusCode());
    assertEquals(200, send(authorized("/api/meta")).statusCode());
    assertEquals(404, send(request("/../gradle.properties")).statusCode());
  }

  @Test
  void rejectsCrossOriginRequestsEvenWithAValidToken() throws Exception {
    assertEquals(
        403,
        send(authorized("/api/meta").header("Origin", "https://unrelated.example")).statusCode());
    assertEquals(
        200,
        send(authorized("/api/meta").header("Origin", "http://127.0.0.1:" + api.port()))
            .statusCode());
  }

  @Test
  void retryReusesTheRunEvenWhenTheOriginalSourceIsGone() throws Exception {
    String id = create();
    java.nio.file.Files.delete(dir.resolve("docs/release.md"));
    assertEquals(id, create());
    assertEquals(1, store.sources(id).size());
  }

  @Test
  void completeReviewFlowExposesOnlyTheIntendedPublicFields() throws Exception {
    String id = create();
    EngineTest.await(store, id, Model.State.REVIEW);
    var detail = Json.parse(send(authorized("/api/runs/" + id)).body());
    assertFalse(detail.has("checkpoint"));
    assertFalse(detail.has("providerKey"));
    assertEquals(id, create());
    assertEquals(1, Json.parse(send(authorized("/api/runs")).body()).path("runs").size());
    var events = Json.parse(send(authorized("/api/runs/" + id + "/events")).body()).path("events");
    long cursor = events.get(events.size() - 1).path("seq").asLong();
    assertTrue(
        Json.parse(send(authorized("/api/runs/" + id + "/events?after=" + cursor)).body())
            .path("events")
            .isEmpty());
    assertEquals(
        1,
        Json.parse(send(authorized("/api/runs/" + id + "/sources")).body()).path("sources").size());
    String citation = detail.path("report").path("citations").get(0).path("sourceId").asText();
    assertTrue(send(authorized("/api/runs/" + id + "/evidence/" + citation)).body().contains("2%"));
    assertTrue(send(authorized("/api/runs/" + id + "/export")).body().contains("Status: REVIEW"));
    assertEquals(
        200,
        post("/api/runs/" + id + "/revise", "{\"feedback\":\"Clarify ownership.\"}").statusCode());
    EngineTest.await(store, id, Model.State.REVIEW);
    assertEquals(200, post("/api/runs/" + id + "/approve", "{}").statusCode());
    var exported = send(authorized("/api/runs/" + id + "/export"));
    assertTrue(exported.body().contains("Status: COMPLETED"));
    assertTrue(exported.headers().firstValue("Content-Disposition").orElseThrow().contains(".md"));
    assertEquals(409, post("/api/runs/" + id + "/cancel", "{}").statusCode());
  }

  @Test
  void rejectsInvalidInputAndWrongMethodsWithoutEchoingPayloads() throws Exception {
    assertEquals(
        415,
        send(authorized("/api/runs")
                .header("Idempotency-Key", "no-content-type")
                .POST(HttpRequest.BodyPublishers.ofString("{}")))
            .statusCode());
    assertEquals(400, post("/api/runs", "{}").statusCode());
    var malformed =
        send(
            authorized("/api/runs")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "malformed-1")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"task\":\"private-value\",\"task\":\"duplicate\"}")));
    assertEquals(400, malformed.statusCode());
    assertFalse(malformed.body().contains("private-value"));
    assertEquals(400, send(authorized("/api/runs?limit=101")).statusCode());
    assertEquals(400, send(authorized("/api/runs?state=UNKNOWN")).statusCode());
    assertEquals(400, send(authorized("/api/runs?limit=1&limit=2")).statusCode());
    assertEquals(405, post("/api/meta", "{}").statusCode());
    assertEquals(
        404, send(authorized("/api/runs/00000000-0000-0000-0000-000000000000")).statusCode());
  }

  @Test
  void rejectsOversizedRequestsBeforeParsing() throws Exception {
    var response =
        send(
            authorized("/api/runs")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "oversized-1")
                .POST(HttpRequest.BodyPublishers.ofString("x".repeat(65537))));
    assertEquals(413, response.statusCode());
  }

  @Test
  void stalledBodyGetsADeadlineAndDoesNotBlockSubsequentRequests() throws Exception {
    try (var socket = new Socket("127.0.0.1", api.port())) {
      socket.setSoTimeout(7500);
      String headers =
          "POST /api/runs HTTP/1.1\r\nHost: 127.0.0.1:"
              + api.port()
              + "\r\nAuthorization: Bearer "
              + TOKEN
              + "\r\n"
              + "Idempotency-Key: slow-client-001\r\n"
              + "Content-Type: application/json\r\n"
              + "Content-Length: 99\r\n"
              + "Connection: close\r\n\r\n"
              + "{";
      socket.getOutputStream().write(headers.getBytes(UTF_8));
      socket.getOutputStream().flush();
      String status =
          new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), UTF_8))
              .readLine();
      assertNotNull(status);
      assertTrue(status.contains("408"), status);
    }
    assertEquals(200, send(authorized("/api/meta")).statusCode());
  }

  @Test
  void hostHeaderCannotRebindTheLocalServer() throws Exception {
    try (var socket = new Socket("127.0.0.1", api.port())) {
      socket.setSoTimeout(3000);
      socket
          .getOutputStream()
          .write(
              "GET / HTTP/1.1\r\nHost: attacker.example\r\nConnection: close\r\n\r\n"
                  .getBytes(UTF_8));
      String status =
          new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), UTF_8))
              .readLine();
      assertTrue(status.contains("400"));
    }
  }
}
