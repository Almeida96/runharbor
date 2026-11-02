package io.github.almeida96.runharbor;

import static io.github.almeida96.runharbor.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineTest {
  @TempDir Path dir;

  @Test
  void reportsRequireReviewAndRevisionKeepsTheConversation() throws Exception {
    try (var store = new Store(dir.resolve("review.db"));
        var provider = new DemoProvider();
        var engine = new Engine(store, provider, 2)) {
      Run run = Fixtures.create(store);
      Run draft = await(store, run.id(), State.REVIEW);
      assertEquals(3, draft.requests());
      assertEquals(3, draft.tools());
      assertTrue(draft.report().toString().contains("2%"));
      engine.control(run.id(), "revise", "Explain the rollback owner.");
      Run revised = await(store, run.id(), State.REVIEW);
      assertEquals(4, revised.requests());
      assertTrue(revised.checkpoint().toString().contains("Explain the rollback owner."));
      assertEquals(State.COMPLETED, engine.control(run.id(), "approve", null).state());
      assertThrows(Json.Conflict.class, () -> engine.control(run.id(), "resume", null));
    }
  }

  @Test
  void unknownToolsReturnAnErrorThatTheProviderCanCorrect() throws Exception {
    var calls = new AtomicInteger();
    var demo = new DemoProvider();
    var provider =
        new Fixtures.TestProvider() {
          @Override
          public Reply next(ArrayNode messages) throws IOException {
            if (calls.getAndIncrement() == 0)
              return Fixtures.reply("shell", Json.obj("command", "anything"));
            if (calls.get() == 2)
              assertTrue(
                  messages.get(messages.size() - 1).path("content").asText().contains("error"));
            return demo.next(messages);
          }
        };
    try (var store = new Store(dir.resolve("tools.db"));
        var engine = new Engine(store, provider, 1)) {
      Run run = Fixtures.create(store);
      Run draft = await(store, run.id(), State.REVIEW);
      assertEquals(4, draft.tools());
      assertTrue(store.events(run.id(), 0).toString().contains("\"isError\":true"));
      awaitIdle(engine);
    }
  }

  @Test
  void pauseDiscardsLateOutputAndResumeWaitsForTheOldWorker() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var demo = new DemoProvider();
    var provider =
        new Fixtures.TestProvider() {
          @Override
          public Reply next(ArrayNode messages) throws IOException {
            if (calls.incrementAndGet() == 1) {
              entered.countDown();
              boolean done = false;
              while (!done)
                try {
                  done = release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                  /* Deliberately late provider. */
                }
            }
            return demo.next(messages);
          }
        };
    try (var store = new Store(dir.resolve("pause.db"));
        var engine = new Engine(store, provider, 2)) {
      Run run = Fixtures.create(store);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      engine.control(run.id(), "pause", null);
      engine.control(run.id(), "resume", null);
      assertEquals(1, engine.status().path("activeRuns").asInt());
      assertEquals(1, calls.get());
      release.countDown();
      Run draft = await(store, run.id(), State.REVIEW);
      assertEquals(4, draft.requests());
      assertEquals(300, draft.tokens());
      assertEquals(
          3,
          store.events(run.id(), 0).findValues("type").stream()
              .filter(n -> n.asText().equals("MODEL_COMPLETED"))
              .count());
    } finally {
      release.countDown();
    }
  }

  @Test
  void cancellingAnInFlightModelCannotReviveTheRun() throws Exception {
    var entered = new CountDownLatch(1);
    var provider =
        new Fixtures.TestProvider() {
          @Override
          public Reply next(ArrayNode messages) throws InterruptedException {
            entered.countDown();
            new CountDownLatch(1).await();
            return Fixtures.reply("workspace_list", Json.obj());
          }
        };
    try (var store = new Store(dir.resolve("cancel.db"));
        var engine = new Engine(store, provider, 1)) {
      Run run = Fixtures.create(store);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      engine.control(run.id(), "cancel", null);
      awaitIdle(engine);
      assertEquals(State.CANCELLED, store.get(run.id()).state());
      assertEquals(0, store.get(run.id()).tokens());
      assertThrows(Json.Conflict.class, () -> engine.control(run.id(), "resume", null));
    }
  }

  @Test
  void transportFailureIsNotAutomaticallyRetried() throws Exception {
    var calls = new AtomicInteger();
    var provider =
        new Fixtures.TestProvider() {
          @Override
          public Reply next(ArrayNode messages) throws IOException {
            calls.incrementAndGet();
            throw new IOException("private endpoint and prompt content");
          }
        };
    try (var store = new Store(dir.resolve("failure.db"));
        var engine = new Engine(store, provider, 1)) {
      Run run = Fixtures.create(store);
      Run failed = await(store, run.id(), State.FAILED);
      awaitIdle(engine);
      assertEquals(1, calls.get());
      assertEquals("MODEL_ERROR", failed.reason());
      assertFalse(store.events(run.id(), 0).toString().contains("private endpoint"));
      engine.control(run.id(), "resume", null);
      assertEquals(2, await(store, run.id(), State.FAILED).requests());
    }
  }

  @Test
  void reportedTokenThresholdStopsBeforeExecutingPendingTools() throws Exception {
    try (var store = new Store(dir.resolve("tokens.db"));
        var provider = new DemoProvider();
        var engine = new Engine(store, provider, 1)) {
      Run run =
          store.create(
              "token-budget",
              new Request("Budget", "Review", 10, 30, 100),
              Fixtures.snapshot(),
              provider.key());
      Run failed = await(store, run.id(), State.FAILED);
      assertEquals(100, failed.tokens());
      assertEquals(0, failed.tools());
      assertEquals("TOKEN_BUDGET_EXHAUSTED", failed.reason());
      awaitIdle(engine);
    }
  }

  @Test
  void requestBudgetDoesNotPreventToolsFromTheLastResponse() throws Exception {
    try (var store = new Store(dir.resolve("requests.db"));
        var provider = new DemoProvider();
        var engine = new Engine(store, provider, 1)) {
      Run run =
          store.create(
              "request-budget",
              new Request("Budget", "Review", 1, 30, 1000),
              Fixtures.snapshot(),
              provider.key());
      Run failed = await(store, run.id(), State.FAILED);
      assertEquals(1, failed.requests());
      assertEquals(1, failed.tools());
      assertEquals("BUDGET_EXHAUSTED", failed.reason());
      awaitIdle(engine);
    }
  }

  @Test
  void parallelLimitLeavesAdditionalRunsQueued() throws Exception {
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var provider =
        new Fixtures.TestProvider() {
          @Override
          public Reply next(ArrayNode messages) throws InterruptedException {
            calls.incrementAndGet();
            entered.countDown();
            release.await();
            return Fixtures.reply("workspace_list", Json.obj());
          }
        };
    try (var store = new Store(dir.resolve("parallel.db"));
        var engine = new Engine(store, provider, 2)) {
      Fixtures.create(store);
      Fixtures.create(store);
      Fixtures.create(store);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals(2, calls.get());
      assertEquals(1, store.list("QUEUED", 100).size());
      for (Run run : store.list(null, 100)) engine.control(run.id(), "cancel", null);
      release.countDown();
      awaitIdle(engine);
    } finally {
      release.countDown();
    }
  }

  static Run await(Store store, String id, State state) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      Run run = store.get(id);
      if (run.state() == state) return run;
      Thread.sleep(15);
    }
    fail("Expected " + state + ", got " + store.get(id).state() + ": " + store.get(id).reason());
    return null;
  }

  static void awaitIdle(Engine engine) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (engine.status().path("activeRuns").asInt() != 0 && System.nanoTime() < deadline)
      Thread.sleep(15);
    assertEquals(0, engine.status().path("activeRuns").asInt());
  }
}
