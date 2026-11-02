package io.github.almeida96.runharbor;

import static io.github.almeida96.runharbor.Model.*;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;

final class Demo {
  private Demo() {}

  static void run(Path output) throws Exception {
    Json.require(
        !Files.exists(output, LinkOption.NOFOLLOW_LINKS), "Demo output must be a new directory");
    Files.createDirectories(output);
    Path workspace = Files.createDirectory(output.resolve("handbook"));
    Files.writeString(
        workspace.resolve("release.md"),
        "# Release procedure\n\n"
            + "Verify the database backup before deployment.\n"
            + "Roll back when the error rate exceeds 2% for five minutes.\n"
            + "The incident commander owns the rollback decision.\n");
    Workspace.Snapshot snapshot = Workspace.capture(workspace);
    Path database = output.resolve("runharbor.db");
    String id;
    try (var store = new Store(database)) {
      Run run =
          store.create(
              "demo-request-001",
              new Request(
                  "Review release readiness",
                  "Review the release procedure and cite the rollback rule.",
                  10,
                  30,
                  40000),
              snapshot,
              "demo-v1");
      id = run.id();
      Run claimed = store.claim("demo-v1");
      Json.require(claimed != null, "Demo claim failed");
      var checkpoint = claimed.checkpoint().deepCopy();
      var reply =
          new DemoProvider()
              .next((com.fasterxml.jackson.databind.node.ArrayNode) checkpoint.get("messages"));
      store.reserve(id, claimed.generation(), true);
      ((com.fasterxml.jackson.databind.node.ArrayNode) checkpoint.get("messages"))
          .add(reply.message());
      checkpoint.set("pending", reply.message().get("tool_calls"));
      store.checkpoint(
          id,
          claimed.generation(),
          checkpoint,
          null,
          reply.tokens(),
          "MODEL_COMPLETED",
          Json.obj("demo", true));
    }
    Files.writeString(workspace.resolve("release.md"), "This changed after the run snapshot.\n");
    try (var provider = new DemoProvider();
        var store = new Store(database);
        var engine = new Engine(store, provider, 1)) {
      Json.require(
          store.get(id).state() == State.PAUSED, "Interrupted run was not paused on recovery");
      engine.control(id, "resume", null);
      Run draft = await(store, id, State.REVIEW);
      Json.require(
          draft.requests() == 3 && draft.report().toString().contains("2%"),
          "Checkpoint or snapshot was not reused");
      engine.control(id, "revise", "Make the next action explicit.");
      Run revised = await(store, id, State.REVIEW);
      Json.require(revised.requests() == 4, "Revision did not reuse the conversation");
      Run completed = engine.control(id, "approve", null);
      Files.writeString(output.resolve("report.md"), Api.export(completed));
      Files.writeString(output.resolve("run.json"), completed.detail().toPrettyString());
      Files.writeString(output.resolve("events.json"), store.events(id, 0).toPrettyString());
      System.out.println(
          Json.obj(
              "provider",
              "scripted-demo",
              "state",
              completed.state().name(),
              "requests",
              completed.requests(),
              "tools",
              completed.tools(),
              "snapshotReused",
              true,
              "checkpointResumed",
              true,
              "reviewed",
              true));
    }
  }

  static Run await(Store store, String id, State expected) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      Run run = store.get(id);
      if (run.state() == expected) return run;
      if (run.state() == State.FAILED)
        throw new IllegalStateException("Demo failed: " + run.reason());
      Thread.sleep(20);
    }
    throw new IllegalStateException("Demo did not finish");
  }
}
