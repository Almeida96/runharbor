package io.github.almeida96.runharbor;

import static io.github.almeida96.runharbor.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreTest {
  @TempDir Path dir;

  @Test
  void idempotentCreationReusesRunAndRejectsChangedInput() throws Exception {
    try (var store = new Store(dir.resolve("idempotency.db"))) {
      Run a = store.create("request-0001", Fixtures.request(), Fixtures.snapshot(), "demo-v1");
      assertEquals(
          a.id(),
          store.create("request-0001", Fixtures.request(), Fixtures.snapshot(), "demo-v1").id());
      assertThrows(
          Json.Conflict.class,
          () ->
              store.create(
                  "request-0001",
                  new Request("Other", "Different", 10, 30, 40000),
                  Fixtures.snapshot(),
                  "demo-v1"));
      assertEquals(1, store.events(a.id(), 0).size());
    }
  }

  @Test
  void concurrentConnectionsCannotClaimTheSameRun() throws Exception {
    Path db = dir.resolve("claim.db");
    try (var store = new Store(db)) {
      Fixtures.create(store);
    }
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                try (var store = new Store(db)) {
                  return store.claim("demo-v1");
                }
              });
      var b =
          pool.submit(
              () -> {
                try (var store = new Store(db)) {
                  return store.claim("demo-v1");
                }
              });
      assertEquals(1, (a.get() == null ? 0 : 1) + (b.get() == null ? 0 : 1));
    }
  }

  @Test
  void lateCheckpointsCannotOverwriteAnOperatorPauseOrCancellation() throws Exception {
    try (var store = new Store(dir.resolve("fence.db"))) {
      Run run = Fixtures.create(store);
      Run claimed = store.claim("demo-v1");
      store.reserve(run.id(), claimed.generation(), true);
      store.control(run.id(), "pause", null, "demo-v1");
      assertFalse(
          store.checkpoint(
              run.id(),
              claimed.generation(),
              claimed.checkpoint(),
              null,
              123,
              "MODEL_COMPLETED",
              Json.obj()));
      assertEquals(0, store.get(run.id()).tokens());
      assertEquals(State.PAUSED, store.get(run.id()).state());
      store.control(run.id(), "cancel", null, "demo-v1");
      store.fail(run.id(), claimed.generation(), "LATE_ERROR");
      assertEquals(State.CANCELLED, store.get(run.id()).state());
    }
  }

  @Test
  void budgetsAreReservedBeforeExternalWork() throws Exception {
    try (var store = new Store(dir.resolve("budget.db"))) {
      Run run =
          store.create(
              "budget-key",
              new Request("Budget", "Task", 1, 1, 500),
              Fixtures.snapshot(),
              "demo-v1");
      Run claimed = store.claim("demo-v1");
      assertTrue(store.reserve(run.id(), claimed.generation(), true));
      assertFalse(store.reserve(run.id(), claimed.generation(), true));
      assertEquals(1, store.get(run.id()).requests());
      assertEquals(State.FAILED, store.get(run.id()).state());
      assertThrows(Json.Conflict.class, () -> store.control(run.id(), "resume", null, "demo-v1"));
    }
  }

  @Test
  void pendingToolsCanResumeAfterLastPermittedModelRequest() throws Exception {
    try (var store = new Store(dir.resolve("pending.db"))) {
      Run run =
          store.create(
              "pending-key",
              new Request("Pending", "Task", 1, 5, 500),
              Fixtures.snapshot(),
              "demo-v1");
      Run claimed = store.claim("demo-v1");
      store.reserve(run.id(), claimed.generation(), true);
      var checkpoint = claimed.checkpoint();
      checkpoint.set("pending", Json.array().add(Fixtures.call("workspace_list", Json.obj())));
      store.checkpoint(
          run.id(), claimed.generation(), checkpoint, null, 10, "MODEL_COMPLETED", Json.obj());
      store.control(run.id(), "pause", null, "demo-v1");
      assertEquals(State.QUEUED, store.control(run.id(), "resume", null, "demo-v1").state());
    }
  }

  @Test
  void recoveryPausesAllInterruptedRunsIncludingOlderThanListWindow() throws Exception {
    try (var store = new Store(dir.resolve("recovery.db"))) {
      Run oldest = Fixtures.create(store);
      store.claim("demo-v1");
      for (int i = 0; i < 105; i++) Fixtures.create(store);
      store.recover("demo-v1");
      assertEquals(State.PAUSED, store.get(oldest.id()).state());
    }
  }

  @Test
  void providerChangesDoNotSilentlyResumeOldWork() throws Exception {
    try (var store = new Store(dir.resolve("provider.db"))) {
      Run run = Fixtures.create(store);
      store.recover("different-provider");
      assertEquals(State.PAUSED, store.get(run.id()).state());
      assertNull(store.claim("different-provider"));
      assertThrows(
          Json.Conflict.class, () -> store.control(run.id(), "resume", null, "different-provider"));
    }
  }

  @Test
  void eventCursorDoesNotRepeatEvents() throws Exception {
    try (var store = new Store(dir.resolve("events.db"))) {
      Run run = Fixtures.create(store);
      long first = store.events(run.id(), 0).get(0).path("seq").asLong();
      store.control(run.id(), "pause", null, "demo-v1");
      var events = store.events(run.id(), first);
      assertEquals(1, events.size());
      assertTrue(events.get(0).path("seq").asLong() > first);
    }
  }

  @Test
  void activeRunExclusionsPreventOverlappingResumes() throws Exception {
    try (var store = new Store(dir.resolve("exclude.db"))) {
      Run run = Fixtures.create(store);
      assertNull(store.claim("demo-v1", Set.of(run.id())));
      assertNotNull(store.claim("demo-v1"));
    }
  }

  @Test
  void unrelatedDatabaseIsNotModified() throws Exception {
    Path db = dir.resolve("unrelated.db");
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + db);
        var s = c.createStatement()) {
      s.execute("CREATE TABLE other (id INTEGER)");
    }
    assertThrows(Json.Invalid.class, () -> new Store(db));
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + db);
        var s = c.createStatement();
        var r = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table'")) {
      r.next();
      assertEquals(1, r.getInt(1));
    }
  }

  @Test
  void processLockRejectsAnotherServerAndCanBeReacquired() throws Exception {
    Path db = dir.resolve("locked.db");
    try (var lock = new ProcessLock(db)) {
      lock.verifyHeld();
      assertThrows(Exception.class, () -> new ProcessLock(db));
    }
    try (var lock = new ProcessLock(db)) {
      lock.verifyHeld();
    }
  }
}
