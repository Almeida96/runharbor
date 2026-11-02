package io.github.almeida96.runharbor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;

final class DemoProvider implements Provider {
  @Override
  public String key() {
    return "demo-v1";
  }

  @Override
  public JsonNode description() {
    return Json.obj("mode", "demo", "model", "Scripted demonstration", "timeoutMs", 0);
  }

  @Override
  public Reply next(ArrayNode messages) throws IOException {
    JsonNode catalog = null, evidence = null;
    for (JsonNode message : messages)
      if (message.path("role").asText().equals("tool")) {
        JsonNode result = Json.parse(message.path("content").asText());
        if (message.path("tool_name").asText().equals("workspace_list") && result.has("files"))
          catalog = result.get("files");
        if (message.path("tool_name").asText().equals("workspace_read") && result.has("sourceId"))
          evidence = result;
      }
    if (catalog == null) return call("workspace_list", Json.obj());
    if (evidence == null) {
      if (catalog.isEmpty()) throw new IOException("DEMO_WORKSPACE_EMPTY");
      return call(
          "workspace_read",
          Json.obj(
              "path",
              catalog.get(0).get("path"),
              "startLine",
              1,
              "lineCount",
              Math.min(30, catalog.get(0).path("lines").intValue())));
    }
    String excerpt = evidence.path("text").asText();
    if (excerpt.length() > 2500) excerpt = excerpt.substring(0, 2500);
    boolean revision = messages.get(messages.size() - 1).path("role").asText().equals("user");
    return call(
        "submit_report",
        Json.obj(
            "title",
            revision ? "Revised workspace review" : "Workspace review",
            "markdown",
            "## Demonstration report\n\n"
                + "This report was assembled by the scripted demo provider, not a language"
                + " model.\n\n"
                + "## Captured evidence\n\n"
                + excerpt
                + "\n\n"
                + "## Next action\n\n"
                + "Review the cited document and confirm the operational checks before proceeding."
                + (revision
                    ? "\n\n"
                          + "The review feedback triggered a new draft. In Ollama mode, the model"
                          + " receives the full operator feedback."
                    : ""),
            "citations",
            java.util.List.of(evidence.path("sourceId").asText())));
  }

  private static Reply call(String name, JsonNode args) {
    return new Reply(
        Json.obj(
            "role",
            "assistant",
            "content",
            "",
            "tool_calls",
            Json.array()
                .add(
                    Json.obj(
                        "type",
                        "function",
                        "function",
                        Json.obj("name", name, "arguments", args)))),
        100);
  }
}
