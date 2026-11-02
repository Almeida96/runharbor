package io.github.almeida96.runharbor;

import static io.github.almeida96.runharbor.Model.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class Engine implements AutoCloseable {
  private final Store store;
  private final Provider provider;
  private final int parallel;
  private final Map<String, Thread> active = new HashMap<>();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private boolean closed;
  private volatile String health = "ok";

  Engine(Store store, Provider provider, int parallel) throws Exception {
    Json.require(parallel >= 1 && parallel <= 4, "Parallel runs must be 1–4");
    this.store = store;
    this.provider = provider;
    this.parallel = parallel;
    store.recover(provider.key());
    scheduler.scheduleWithFixedDelay(this::tick, 0, 200, TimeUnit.MILLISECONDS);
  }

  private synchronized void tick() {
    if (closed) return;
    try {
      while (active.size() < parallel) {
        Run run = store.claim(provider.key(), Set.copyOf(active.keySet()));
        if (run == null) break;
        Thread worker =
            Thread.ofVirtual()
                .name("run-" + run.id())
                .unstarted(
                    () -> {
                      try {
                        work(run);
                      } finally {
                        synchronized (Engine.this) {
                          active.remove(run.id(), Thread.currentThread());
                        }
                      }
                    });
        active.put(run.id(), worker);
        worker.start();
      }
      health = "ok";
    } catch (Exception e) {
      health = "database_unavailable";
    }
  }

  synchronized Run control(String id, String action, String feedback) throws Exception {
    Run result = store.control(id, action, feedback, provider.key());
    if (action.equals("pause") || action.equals("cancel")) {
      Thread thread = active.get(id);
      if (thread != null) thread.interrupt();
    }
    return result;
  }

  synchronized JsonNode status() {
    return Json.obj("status", health, "activeRuns", active.size(), "parallelRuns", parallel);
  }

  private void work(Run claimed) {
    String id = claimed.id();
    int generation = claimed.generation();
    try {
      var sources = store.sources(id);
      while (!Thread.currentThread().isInterrupted()) {
        Run run = store.get(id);
        if (run.state() != State.RUNNING || run.generation() != generation) return;
        ObjectNode checkpoint = run.checkpoint().deepCopy();
        ArrayNode messages = (ArrayNode) checkpoint.get("messages"),
            pending = (ArrayNode) checkpoint.get("pending");
        int cursor = checkpoint.get("cursor").intValue();
        if (cursor < pending.size()) {
          if (!store.reserve(id, generation, false)) return;
          JsonNode function = pending.get(cursor).get("function");
          String name = function.get("name").asText();
          JsonNode arguments = function.get("arguments");
          Tools.Result result;
          try {
            result =
                Tools.execute(name, arguments, sources, (ObjectNode) checkpoint.get("evidence"));
          } catch (Json.Invalid e) {
            result = new Tools.Result(Json.obj("error", e.getMessage()), null);
          }
          messages.add(
              Json.obj("role", "tool", "tool_name", name, "content", result.output().toString()));
          checkpoint.put("cursor", cursor + 1);
          if (!store.checkpoint(
              id,
              generation,
              checkpoint,
              result.report(),
              0,
              "TOOL_COMPLETED",
              Json.obj(
                  "name",
                  name,
                  "arguments",
                  arguments,
                  "output",
                  result.output(),
                  "isError",
                  result.output().has("error")))) return;
          if (result.report() != null) return;
        } else {
          if (messages.toString().length() > 300000) {
            store.fail(id, generation, "CONTEXT_LIMIT");
            return;
          }
          if (!store.reserve(id, generation, true)) return;
          long start = System.nanoTime();
          Provider.Reply reply = provider.next(messages.deepCopy());
          messages.add(reply.message());
          checkpoint.set("pending", reply.message().get("tool_calls"));
          checkpoint.put("cursor", 0);
          if (!store.checkpoint(
              id,
              generation,
              checkpoint,
              null,
              reply.tokens(),
              "MODEL_COMPLETED",
              Json.obj(
                  "tokens",
                  reply.tokens(),
                  "durationMs",
                  TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                  "toolCalls",
                  reply.message().get("tool_calls").size()))) return;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      failQuietly(id, generation, "INTERRUPTED");
    } catch (IOException e) {
      String reason = e.getMessage();
      if (reason == null || !reason.matches("MODEL_[A-Z_]+|DEMO_WORKSPACE_EMPTY"))
        reason = "MODEL_ERROR";
      failQuietly(id, generation, reason);
    } catch (Exception e) {
      failQuietly(id, generation, "EXECUTION_ERROR");
    }
  }

  private void failQuietly(String id, int generation, String reason) {
    try {
      store.fail(id, generation, reason);
    } catch (Exception e) {
      health = "database_unavailable";
    }
  }

  @Override
  public void close() {
    Thread[] running;
    synchronized (this) {
      closed = true;
      scheduler.shutdownNow();
      running = active.values().toArray(Thread[]::new);
      for (Thread thread : running) thread.interrupt();
    }
    for (Thread thread : running)
      try {
        thread.join(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
  }
}
