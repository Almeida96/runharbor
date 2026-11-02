package io.github.almeida96.runharbor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ToolsTest {
  @Test
  void catalogDoesNotMakeUnretrievedFilesCitable() {
    var evidence = Json.obj();
    Tools.execute("workspace_list", Json.obj(), Fixtures.snapshot().sources(), evidence);
    assertTrue(evidence.isEmpty());
    assertThrows(
        Json.Invalid.class,
        () ->
            Tools.execute(
                "submit_report",
                Json.obj(
                    "title", "Report", "markdown", "Claim", "citations", List.of("a".repeat(64))),
                Fixtures.snapshot().sources(),
                evidence));
  }

  @Test
  void reportsCanOnlyCiteRetrievedUniqueEvidence() {
    var evidence = Json.obj();
    var result =
        Tools.execute(
            "workspace_read",
            Json.obj("path", "release.md", "startLine", 3, "lineCount", 1),
            Fixtures.snapshot().sources(),
            evidence);
    String id = result.output().path("sourceId").asText();
    var report =
        Tools.execute(
                "submit_report",
                Json.obj(
                    "title",
                    "Report",
                    "markdown",
                    "Rollback threshold is documented.",
                    "citations",
                    List.of(id)),
                Fixtures.snapshot().sources(),
                evidence)
            .report();
    assertEquals(
        "Rollback when errors exceed 2%.", report.path("citations").get(0).path("text").asText());
    assertThrows(
        Json.Invalid.class,
        () ->
            Tools.execute(
                "submit_report",
                Json.obj("title", "Report", "markdown", "Claim", "citations", List.of(id, id)),
                Fixtures.snapshot().sources(),
                evidence));
  }

  @Test
  void searchAddsExactSourceRangesToEvidence() {
    var evidence = Json.obj();
    var result =
        Tools.execute(
            "workspace_search",
            Json.obj("query", "backup", "limit", 3),
            Fixtures.snapshot().sources(),
            evidence);
    assertEquals(1, result.output().path("matches").size());
    assertEquals(1, evidence.size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"shell_exec", "http_get", "workspace_write", "approve_report"})
  void unavailableToolsAreRejected(String name) {
    assertThrows(
        Json.Invalid.class,
        () -> Tools.execute(name, Json.obj(), Fixtures.snapshot().sources(), Json.obj()));
  }

  @Test
  void unknownArgumentsAndMissingCitationsAreRejected() {
    assertThrows(
        Json.Invalid.class,
        () ->
            Tools.execute(
                "workspace_list",
                Json.obj("path", "secret"),
                Fixtures.snapshot().sources(),
                Json.obj()));
    assertThrows(
        Json.Invalid.class,
        () ->
            Tools.execute(
                "submit_report",
                Json.obj("title", "Title", "markdown", "Text", "citations", List.of()),
                Fixtures.snapshot().sources(),
                Json.obj()));
  }

  @Test
  void oversizedOrBlankReportsAreRejected() {
    assertThrows(
        Json.Invalid.class,
        () ->
            Tools.execute(
                "submit_report",
                Json.obj("title", " ", "markdown", "Text", "citations", List.of("a".repeat(64))),
                Fixtures.snapshot().sources(),
                Json.obj()));
    assertThrows(
        Json.Invalid.class,
        () ->
            Tools.execute(
                "submit_report",
                Json.obj(
                    "title",
                    "Title",
                    "markdown",
                    "x".repeat(20001),
                    "citations",
                    List.of("a".repeat(64))),
                Fixtures.snapshot().sources(),
                Json.obj()));
  }
}
