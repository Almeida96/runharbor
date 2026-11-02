package io.github.almeida96.runharbor;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

final class Ollama implements Provider {
  private final URI endpoint;
  private final String model;
  private final int timeoutMs;
  private final HttpClient client;

  Ollama(URI endpoint, String model, int timeoutMs) {
    validate(endpoint);
    Json.require(model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"), "Invalid model name");
    Json.require(timeoutMs >= 100 && timeoutMs <= 300000, "Model timeout must be 100–300000 ms");
    this.endpoint = endpoint;
    this.model = model;
    this.timeoutMs = timeoutMs;
    client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(timeoutMs))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  static void validate(URI uri) {
    Json.require(
        uri.getHost() != null
            && uri.getUserInfo() == null
            && uri.getQuery() == null
            && uri.getFragment() == null,
        "Invalid model endpoint");
    boolean local =
        List.of("localhost", "127.0.0.1", "[::1]", "::1")
            .contains(uri.getHost().toLowerCase(Locale.ROOT));
    Json.require(
        uri.getScheme().equalsIgnoreCase("https")
            || (local && uri.getScheme().equalsIgnoreCase("http")),
        "Use HTTPS or a loopback HTTP model endpoint");
  }

  @Override
  public String key() {
    return Json.hash("ollama-v1\n" + endpoint + "\n" + model);
  }

  @Override
  public JsonNode description() {
    return Json.obj("mode", "ollama", "model", model, "timeoutMs", timeoutMs);
  }

  @Override
  public Reply next(ArrayNode messages) throws IOException, InterruptedException {
    byte[] body =
        Json.obj(
                "model",
                model,
                "messages",
                messages,
                "tools",
                Tools.definitions(),
                "stream",
                false,
                "think",
                false,
                "options",
                Json.obj("temperature", 0, "num_predict", 2048))
            .toString()
            .getBytes(UTF_8);
    if (body.length > 400000) throw new IOException("MODEL_CONTEXT_LIMIT");
    var request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    var future =
        client.sendAsync(
            request,
            info -> new BoundedBody(info.headers().firstValueAsLong("Content-Length").orElse(-1)));
    try {
      var response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
      if (response.statusCode() != 200) throw new IOException("MODEL_HTTP_ERROR");
      String contentType =
          response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip();
      if (!contentType.equalsIgnoreCase("application/json"))
        throw new IOException("MODEL_INVALID_RESPONSE");
      return parse(Json.parse(response.body()));
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw e;
    } catch (java.util.concurrent.TimeoutException e) {
      future.cancel(true);
      throw new IOException("MODEL_TIMEOUT");
    } catch (java.util.concurrent.ExecutionException e) {
      future.cancel(true);
      throw new IOException("MODEL_TRANSPORT_ERROR");
    } catch (Json.Invalid e) {
      throw new IOException("MODEL_INVALID_RESPONSE");
    }
  }

  static Reply parse(JsonNode response) throws IOException {
    try {
      Json.require(
          response.path("done").isBoolean() && response.path("done").booleanValue(),
          "Model response is incomplete");
      JsonNode message = response.get("message");
      Json.require(
          message != null
              && message.isObject()
              && message.path("role").asText().equals("assistant"),
          "Expected assistant message");
      String content = message.has("content") ? Json.text(message.get("content"), 0, 16000) : "";
      JsonNode calls = message.get("tool_calls");
      Json.require(
          calls != null && calls.isArray() && !calls.isEmpty() && calls.size() <= 4,
          "Expected 1–4 tool calls");
      var normalized = Json.array();
      boolean finish = false;
      for (JsonNode call : calls) {
        JsonNode function = call.get("function");
        Json.require(function != null && function.isObject(), "Invalid function call");
        String name = Json.text(function.get("name"), 1, 80);
        JsonNode args = function.get("arguments");
        Json.require(
            args != null && args.isObject() && args.toString().length() <= 30000,
            "Invalid function arguments");
        normalized.add(
            Json.obj("type", "function", "function", Json.obj("name", name, "arguments", args)));
        finish |= name.equals("submit_report");
      }
      Json.require(!finish || normalized.size() == 1, "Submit report must be called alone");
      long tokens =
          (long) Json.integer(response.get("prompt_eval_count"), 0, 10000000)
              + Json.integer(response.get("eval_count"), 0, 10000000);
      return new Reply(
          Json.obj("role", "assistant", "content", content, "tool_calls", normalized), tokens);
    } catch (IllegalArgumentException e) {
      throw new IOException("MODEL_INVALID_RESPONSE");
    }
  }

  @Override
  public void close() {
    client.shutdownNow();
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final long announced;
    private Flow.Subscription subscription;

    BoundedBody(long announced) {
      this.announced = announced;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
      subscription = value;
      if (announced > 1048576) fail();
      else value.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      if (result.isDone()) {
        subscription.cancel();
        return;
      }
      for (var buffer : buffers) {
        if ((long) bytes.size() + buffer.remaining() > 1048576) {
          fail();
          return;
        }
        byte[] part = new byte[buffer.remaining()];
        buffer.get(part);
        bytes.writeBytes(part);
      }
      subscription.request(1);
    }

    private void fail() {
      subscription.cancel();
      result.completeExceptionally(new IOException("MODEL_RESPONSE_LIMIT"));
    }

    @Override
    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
