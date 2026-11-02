package io.github.almeida96.runharbor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

final class Tools {
  record Result(JsonNode output, JsonNode report) {}

  private Tools() {}

  static ArrayNode definitions() {
    var tools = Json.array();
    tools.add(
        definition("workspace_list", "List files captured for this task.", Json.obj(), List.of()));
    tools.add(
        definition(
            "workspace_search",
            "Find literal terms in the captured files and receive citable source IDs.",
            Json.obj(
                "query",
                Json.obj("type", "string"),
                "limit",
                Json.obj("type", "integer", "minimum", 1, "maximum", 8)),
            List.of("query", "limit")));
    tools.add(
        definition(
            "workspace_read",
            "Read up to 40 lines from a captured file and receive a citable source ID.",
            Json.obj(
                "path",
                Json.obj("type", "string"),
                "startLine",
                Json.obj("type", "integer", "minimum", 1),
                "lineCount",
                Json.obj("type", "integer", "minimum", 1, "maximum", 40)),
            List.of("path", "startLine", "lineCount")));
    tools.add(
        definition(
            "submit_report",
            "Submit a draft report for human review. Cite source IDs returned by search/read. Call"
                + " this tool by itself.",
            Json.obj(
                "title",
                Json.obj("type", "string"),
                "markdown",
                Json.obj("type", "string"),
                "citations",
                Json.obj(
                    "type",
                    "array",
                    "items",
                    Json.obj("type", "string"),
                    "minItems",
                    1,
                    "maxItems",
                    20)),
            List.of("title", "markdown", "citations")));
    return tools;
  }

  private static JsonNode definition(
      String name, String description, JsonNode properties, List<String> required) {
    return Json.obj(
        "type",
        "function",
        "function",
        Json.obj(
            "name",
            name,
            "description",
            description,
            "parameters",
            Json.obj(
                "type",
                "object",
                "properties",
                properties,
                "required",
                required,
                "additionalProperties",
                false)));
  }

  static Result execute(
      String name, JsonNode args, List<Workspace.Source> sources, ObjectNode evidence) {
    switch (name) {
      case "workspace_list" -> {
        Json.fields(args, Set.of(), Set.of());
        return new Result(Json.obj("files", Workspace.catalog(sources)), null);
      }
      case "workspace_search" -> {
        Json.fields(args, Set.of("query", "limit"), Set.of());
        var hits =
            Workspace.search(
                sources,
                Json.text(args.get("query"), 1, 200),
                Json.integer(args.get("limit"), 1, 8));
        var output = Json.array();
        for (var hit : hits) {
          addEvidence(evidence, hit);
          output.add(hit.json());
        }
        return new Result(Json.obj("matches", output), null);
      }
      case "workspace_read" -> {
        Json.fields(args, Set.of("path", "startLine", "lineCount"), Set.of());
        var hit =
            Workspace.read(
                sources,
                Json.text(args.get("path"), 1, 240),
                Json.integer(args.get("startLine"), 1, 1000000),
                Json.integer(args.get("lineCount"), 1, 40));
        addEvidence(evidence, hit);
        return new Result(hit.json(), null);
      }
      case "submit_report" -> {
        Json.fields(args, Set.of("title", "markdown", "citations"), Set.of());
        String title = Json.text(args.get("title"), 1, 160).strip(),
            markdown = Json.text(args.get("markdown"), 1, 20000).strip();
        Json.require(
            !title.isEmpty() && !markdown.isEmpty(), "Report title and text cannot be blank");
        JsonNode references = args.get("citations");
        Json.require(
            references.isArray() && !references.isEmpty() && references.size() <= 20,
            "Report requires 1–20 source IDs");
        var used = new java.util.HashSet<String>();
        var citations = Json.array();
        for (JsonNode reference : references) {
          String id = Json.text(reference, 64, 64);
          Json.require(
              used.add(id) && evidence.has(id),
              "Citation must be unique and refer to evidence retrieved in this run");
          citations.add(evidence.get(id));
        }
        JsonNode report = Json.obj("title", title, "markdown", markdown, "citations", citations);
        return new Result(Json.obj("submitted", true, "reviewRequired", true), report);
      }
      default -> throw new Json.Invalid("Tool is not available");
    }
  }

  private static void addEvidence(ObjectNode evidence, Workspace.Hit hit) {
    Json.require(evidence.has(hit.sourceId()) || evidence.size() < 100, "Evidence limit reached");
    evidence.set(hit.sourceId(), hit.json());
  }
}
