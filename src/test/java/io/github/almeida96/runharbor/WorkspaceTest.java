package io.github.almeida96.runharbor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTest {
  @TempDir Path dir;

  @Test
  void capturesUnicodeTextAndStableSourceIdentifiers() throws Exception {
    Files.writeString(dir.resolve("revisão.md"), "# Revisão\nAmanhã: verificar rollback.\n");
    var snapshot = Workspace.capture(dir);
    var hit = Workspace.read(snapshot.sources(), "revisão.md", 2, 1);
    assertEquals("Amanhã: verificar rollback.", hit.text());
    assertEquals(64, hit.sourceId().length());
    assertEquals(
        hit.sourceId(),
        Workspace.read(Workspace.capture(dir).sources(), "revisão.md", 2, 1).sourceId());
  }

  @Test
  void snapshotsRemainUnchangedAfterOriginalFileEdits() throws Exception {
    Files.writeString(dir.resolve("a.txt"), "original\n");
    var before = Workspace.capture(dir);
    Files.writeString(dir.resolve("a.txt"), "modified\n");
    assertEquals("original", Workspace.read(before.sources(), "a.txt", 1, 1).text());
    assertNotEquals(before.sources().get(0).hash(), Workspace.capture(dir).sources().get(0).hash());
  }

  @Test
  void excludesHiddenAndBuildDirectoriesAndBinaryFiles() throws Exception {
    Files.writeString(dir.resolve("keep.md"), "keep");
    Files.writeString(dir.resolve(".env"), "PRIVATE_TOKEN");
    Files.createDirectory(dir.resolve("build"));
    Files.writeString(dir.resolve("build/hidden.md"), "PRIVATE_BUILD");
    Files.createDirectory(dir.resolve(".git"));
    Files.writeString(dir.resolve(".git/config"), "PRIVATE_GIT");
    Files.write(dir.resolve("binary.txt"), new byte[] {0, 1, 2});
    Files.write(dir.resolve("invalid.txt"), new byte[] {(byte) 0xc3, 0x28});
    var snapshot = Workspace.capture(dir);
    assertEquals(1, snapshot.sources().size());
    assertEquals(2, snapshot.skipped().size());
    assertFalse(snapshot.toString().contains("PRIVATE_TOKEN"));
  }

  @Test
  void symlinksCannotImportOutsideContent() throws Exception {
    Files.writeString(dir.resolve("keep.md"), "keep");
    Path outside = Files.createTempFile("runharbor-outside-", ".md");
    try {
      Files.writeString(outside, "OUTSIDE_DATA");
      try {
        Files.createSymbolicLink(dir.resolve("link.md"), outside);
      } catch (java.io.IOException | UnsupportedOperationException | SecurityException e) {
        org.junit.jupiter.api.Assumptions.abort("Symlink creation is unavailable on this platform");
      }
      var snapshot = Workspace.capture(dir);
      assertEquals(1, snapshot.sources().size());
      assertTrue(snapshot.skipped().get(0).contains("symlink"));
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void oversizedFilesAreReportedAndFileCountIsBounded() throws Exception {
    Files.writeString(dir.resolve("large.md"), "x".repeat(Workspace.MAX_FILE_BYTES + 1));
    Files.writeString(dir.resolve("keep.md"), "keep");
    assertEquals(1, Workspace.capture(dir).skipped().size());
    for (int i = 0; i < 100; i++) Files.writeString(dir.resolve("file-" + i + ".txt"), "content");
    assertThrows(Json.Invalid.class, () -> Workspace.capture(dir));
  }

  @Test
  void searchRanksLiteralMatchesAndDoesNotInterpretRegularExpressions() {
    var sources = Fixtures.snapshot().sources();
    assertEquals(3, Workspace.search(sources, "rollback errors", 2).get(0).startLine());
    assertTrue(Workspace.search(sources, ".*", 2).isEmpty());
    assertThrows(Json.Invalid.class, () -> Workspace.search(sources, " ", 2));
  }

  @Test
  void invalidPathsAndLineRangesCannotReadTheFilesystem() {
    var sources = Fixtures.snapshot().sources();
    assertThrows(Json.Invalid.class, () -> Workspace.read(sources, "../secret", 1, 1));
    assertThrows(Json.Invalid.class, () -> Workspace.read(sources, "release.md", 0, 1));
    assertThrows(Json.Invalid.class, () -> Workspace.read(sources, "release.md", 1, 41));
    assertThrows(Json.Invalid.class, () -> Workspace.read(sources, "release.md", 100, 1));
  }

  @Test
  void excessiveLineLengthCannotBypassToolOutputLimits() throws Exception {
    Files.writeString(dir.resolve("long.txt"), "x".repeat(12001));
    assertThrows(
        Json.Invalid.class,
        () -> Workspace.read(Workspace.capture(dir).sources(), "long.txt", 1, 1));
  }

  @Test
  void emptyWorkspaceIsRejected() {
    assertThrows(Json.Invalid.class, () -> Workspace.capture(dir));
  }
}
