package io.github.almeida96.runharbor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {
  @TempDir Path dir;

  record Result(int code, String output) {}

  Result run(String... args) throws Exception {
    var command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("runharbor.classpath"),
                Main.class.getName()));
    command.addAll(List.of(args));
    Path log = dir.resolve(java.util.UUID.randomUUID() + ".log");
    var process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    if (!process.waitFor(20, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      fail("CLI timed out");
    }
    return new Result(process.exitValue(), Files.readString(log));
  }

  @Test
  void helpAndVersionWorkWithoutProviderConfiguration() throws Exception {
    assertEquals("0.1.0", run("--version").output().strip());
    var help = run("--help");
    assertEquals(0, help.code());
    assertTrue(help.output().contains("--token-env"));
  }

  @Test
  void invalidArgumentsFailWithoutEchoingTheirValues() throws Exception {
    var unknown = run("serve", "--secret", "not-for-output");
    assertEquals(1, unknown.code());
    assertFalse(unknown.output().contains("not-for-output"));
    assertEquals(1, run("demo", "--out", "one", "--out", "two").code());
    assertEquals(1, run("unknown").code());
  }

  @Test
  void demoRecoversFrozenEvidenceAndProducesReviewedArtifacts() throws Exception {
    Path output = dir.resolve("revisão");
    var result = run("demo", "--out", output.toString());
    assertEquals(0, result.code(), result.output());
    var summary = Json.parse(result.output().strip());
    assertTrue(summary.path("checkpointResumed").asBoolean());
    assertTrue(summary.path("snapshotReused").asBoolean());
    assertEquals(
        "COMPLETED",
        Json.parse(Files.readString(output.resolve("run.json"))).path("state").asText());
    assertTrue(Files.readString(output.resolve("report.md")).contains("2%"));
    assertFalse(Files.readString(output.resolve("handbook/release.md")).contains("2%"));
    var events = Files.readString(output.resolve("events.json"));
    assertTrue(events.contains("RECOVERED"));
    assertTrue(events.contains("OPERATOR_REVISE"));
    assertTrue(events.contains("OPERATOR_APPROVE"));
    assertEquals(1, run("demo", "--out", output.toString()).code());
  }
}
