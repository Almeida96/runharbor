package io.github.almeida96.runharbor;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OllamaTest {
  static ObjectNode response() {
    return Json.obj(
        "done",
        true,
        "message",
        Fixtures.reply("workspace_list", Json.obj()).message(),
        "prompt_eval_count",
        30,
        "eval_count",
        10);
  }

  @Test
  void sendsNativeToolContractAndRemovesThinkingFromStoredMessage() throws Exception {
    var received = new AtomicReference<String>();
    try (var fixture =
            new Server(
                exchange -> {
                  received.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
                  var response = response();
                  ((ObjectNode) response.get("message")).put("thinking", "not retained");
                  byte[] bytes = response.toString().getBytes(UTF_8);
                  exchange.getResponseHeaders().set("Content-Type", "application/json");
                  exchange.sendResponseHeaders(200, bytes.length);
                  exchange.getResponseBody().write(bytes);
                  exchange.close();
                });
        var provider = new Ollama(fixture.uri(), "test-model:tools", 3000)) {
      Provider.Reply reply =
          provider.next(Json.array().add(Json.obj("role", "user", "content", "Review")));
      var sent = Json.parse(received.get());
      assertFalse(sent.path("stream").asBoolean());
      assertFalse(sent.path("think").asBoolean());
      assertEquals(4, sent.path("tools").size());
      assertEquals("test-model:tools", sent.path("model").asText());
      assertEquals(40, reply.tokens());
      assertFalse(reply.message().has("thinking"));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missingUsage",
        "fractionalUsage",
        "incomplete",
        "plainAnswer",
        "stringArguments",
        "finishBatch",
        "wrongRole"
      })
  void rejectsResponsesThatCannotBeCheckpointed(String kind) {
    var response = response();
    var message = (ObjectNode) response.get("message");
    switch (kind) {
      case "missingUsage" -> response.remove("eval_count");
      case "fractionalUsage" -> response.put("eval_count", 1.5);
      case "incomplete" -> response.put("done", false);
      case "plainAnswer" -> message.remove("tool_calls");
      case "stringArguments" ->
          ((ObjectNode) message.get("tool_calls").get(0).get("function")).put("arguments", "{}");
      case "finishBatch" ->
          message.set(
              "tool_calls",
              Json.array()
                  .add(Fixtures.call("submit_report", Json.obj()))
                  .add(Fixtures.call("workspace_list", Json.obj())));
      case "wrongRole" -> message.put("role", "user");
      default -> fail("Unknown case");
    }
    assertEquals(
        "MODEL_INVALID_RESPONSE",
        assertThrows(IOException.class, () -> Ollama.parse(response)).getMessage());
  }

  @Test
  void deadlineCoversAResponseWhoseHeadersArriveBeforeItsBody() throws Exception {
    try (var fixture =
            new Server(
                exchange -> {
                  exchange.getRequestBody().readAllBytes();
                  exchange.getResponseHeaders().set("Content-Type", "application/json");
                  exchange.sendResponseHeaders(200, 0);
                  exchange.getResponseBody().flush();
                  try {
                    Thread.sleep(1500);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  } finally {
                    exchange.close();
                  }
                });
        var provider = new Ollama(fixture.uri(), "fixture", 250)) {
      long start = System.nanoTime();
      assertThrows(IOException.class, () -> provider.next(Json.array()));
      assertTrue(System.nanoTime() - start < 1_400_000_000L);
    }
  }

  @Test
  void chunkedResponseCannotGrowPastTheResponseLimit() throws Exception {
    try (var fixture =
            new Server(
                exchange -> {
                  exchange.getRequestBody().readAllBytes();
                  exchange.getResponseHeaders().set("Content-Type", "application/json");
                  exchange.sendResponseHeaders(200, 0);
                  try {
                    exchange.getResponseBody().write(new byte[1_100_000]);
                  } finally {
                    exchange.close();
                  }
                });
        var provider = new Ollama(fixture.uri(), "fixture", 3000)) {
      assertThrows(IOException.class, () -> provider.next(Json.array()));
    }
  }

  @Test
  void redirectsAreRejectedWithoutFollowingTheLocation() throws Exception {
    var targetCalls = new java.util.concurrent.atomic.AtomicInteger();
    try (var target =
            new Server(
                exchange -> {
                  targetCalls.incrementAndGet();
                  exchange.sendResponseHeaders(204, -1);
                  exchange.close();
                });
        var redirect =
            new Server(
                exchange -> {
                  exchange.getRequestBody().readAllBytes();
                  exchange.getResponseHeaders().set("Location", target.uri().toString());
                  exchange.sendResponseHeaders(307, -1);
                  exchange.close();
                });
        var provider = new Ollama(redirect.uri(), "fixture", 3000)) {
      assertEquals(
          "MODEL_HTTP_ERROR",
          assertThrows(IOException.class, () -> provider.next(Json.array())).getMessage());
      assertEquals(0, targetCalls.get());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://example.com/api/chat",
        "https://user:pass@example.com/api/chat",
        "http://localhost/api/chat?key=secret",
        "file:///etc/passwd"
      })
  void rejectsUnsafeEndpointConfiguration(String endpoint) {
    assertThrows(Json.Invalid.class, () -> Ollama.validate(URI.create(endpoint)));
  }

  @Test
  void providerIdentityIncludesModelAndEndpoint() {
    try (var a = new Ollama(URI.create("http://localhost:11434/api/chat"), "one", 1000);
        var b = new Ollama(URI.create("http://localhost:11434/api/chat"), "two", 1000)) {
      assertNotEquals(a.key(), b.key());
    }
  }

  static final class Server implements AutoCloseable {
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor =
        Executors.newVirtualThreadPerTaskExecutor();

    Server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/api/chat", handler);
      server.setExecutor(executor);
      server.start();
    }

    URI uri() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat");
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
