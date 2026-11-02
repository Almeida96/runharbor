package io.github.almeida96.runharbor;

import static io.github.almeida96.runharbor.Model.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class Fixtures {
  private Fixtures() {}

  static Workspace.Snapshot snapshot() {
    String content =
        "# Release\nVerify backups.\nRollback when errors exceed 2%.\nOwner: incident commander.\n";
    return new Workspace.Snapshot(
        List.of(new Workspace.Source("release.md", Json.hash(content), content, 4)), List.of());
  }

  static Request request() {
    return new Request(
        "Review release", "Review rollback requirements with citations.", 10, 30, 40000);
  }

  static Run create(Store store) throws Exception {
    return store.create(java.util.UUID.randomUUID().toString(), request(), snapshot(), "demo-v1");
  }

  static JsonNode call(String name, JsonNode arguments) {
    return Json.obj("type", "function", "function", Json.obj("name", name, "arguments", arguments));
  }

  static Provider.Reply reply(String name, JsonNode arguments) {
    return new Provider.Reply(
        Json.obj(
            "role",
            "assistant",
            "content",
            "",
            "tool_calls",
            Json.array().add(call(name, arguments))),
        50);
  }

  static Path workspace(Path parent) throws Exception {
    Path path = Files.createDirectory(parent.resolve("docs"));
    Files.writeString(path.resolve("release.md"), snapshot().sources().get(0).content());
    return path;
  }

  abstract static class TestProvider implements Provider {
    @Override
    public String key() {
      return "demo-v1";
    }

    @Override
    public JsonNode description() {
      return Json.obj("mode", "test", "model", "fixture");
    }

    @Override
    public abstract Reply next(ArrayNode messages) throws java.io.IOException, InterruptedException;
  }
}
