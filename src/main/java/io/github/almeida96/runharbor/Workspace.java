package io.github.almeida96.runharbor;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class Workspace {
  static final int MAX_FILES = 100, MAX_FILE_BYTES = 128 * 1024, MAX_TOTAL_BYTES = 2 * 1024 * 1024;
  private static final Set<String> EXTENSIONS =
      Set.of("md", "txt", "json", "yaml", "yml", "java", "kt", "properties");
  private static final Set<String> SKIP =
      Set.of("node_modules", "build", "dist", "target", "vendor");

  record Source(String path, String hash, String content, int lines) {
    JsonNode metadata() {
      return Json.obj("path", path, "hash", hash, "lines", lines);
    }

    List<String> rows() {
      return content.lines().toList();
    }
  }

  record Snapshot(List<Source> sources, List<String> skipped) {}

  record Hit(String sourceId, String path, int startLine, int endLine, String text) {
    JsonNode json() {
      return Json.obj(
          "sourceId",
          sourceId,
          "path",
          path,
          "startLine",
          startLine,
          "endLine",
          endLine,
          "text",
          text);
    }
  }

  private Workspace() {}

  static Snapshot capture(Path directory) throws IOException {
    Json.require(
        Files.isDirectory(directory) && !Files.isSymbolicLink(directory),
        "Workspace must be a directory, not a symlink");
    Path root = directory.toRealPath();
    var sources = new ArrayList<Source>();
    var skipped = new ArrayList<String>();
    int total = 0;
    var paths = new ArrayList<Path>();
    Files.walkFileTree(
        root,
        Set.of(),
        9,
        new java.nio.file.SimpleFileVisitor<>() {
          private int entries;

          private void count() {
            Json.require(
                ++entries <= 10000, "Workspace has too many entries; select a smaller directory");
          }

          @Override
          public java.nio.file.FileVisitResult preVisitDirectory(
              Path dir, java.nio.file.attribute.BasicFileAttributes attributes) {
            count();
            String name = dir.getFileName().toString();
            return !dir.equals(root) && (name.startsWith(".") || SKIP.contains(name))
                ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                : java.nio.file.FileVisitResult.CONTINUE;
          }

          @Override
          public java.nio.file.FileVisitResult visitFile(
              Path file, java.nio.file.attribute.BasicFileAttributes attributes) {
            count();
            if (attributes.isDirectory())
              skipped.add(root.relativize(file).toString().replace('\\', '/') + " (depth limit)");
            else paths.add(file);
            return java.nio.file.FileVisitResult.CONTINUE;
          }
        });
    paths.sort(Comparator.naturalOrder());
    for (Path path : paths) {
      Path relative = root.relativize(path);
      String name = relative.toString().replace('\\', '/');
      boolean excluded = false;
      for (Path part : relative)
        if (part.toString().startsWith(".") || SKIP.contains(part.toString())) excluded = true;
      if (excluded || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
      if (Files.isSymbolicLink(path)) {
        skipped.add(name + " (symlink)");
        continue;
      }
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
      String file = path.getFileName().toString();
      String extension = file.substring(file.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
      if (!EXTENSIONS.contains(extension)) continue;
      Json.require(
          name.length() <= 240 && name.chars().noneMatch(Character::isISOControl),
          "Workspace paths must be at most 240 characters without control characters");
      byte[] bytes;
      try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
        bytes = input.readNBytes(MAX_FILE_BYTES + 1);
      }
      if (bytes.length > MAX_FILE_BYTES) {
        skipped.add(name + " (over 128 KiB)");
        continue;
      }
      String content;
      try {
        content =
            UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
      } catch (java.nio.charset.CharacterCodingException e) {
        skipped.add(name + " (invalid UTF-8)");
        continue;
      }
      if (content.indexOf('\0') >= 0) {
        skipped.add(name + " (binary content)");
        continue;
      }
      if (content.isBlank()) continue;
      total += bytes.length;
      Json.require(
          sources.size() < MAX_FILES && total <= MAX_TOTAL_BYTES,
          "Workspace exceeds snapshot limits; select a smaller directory");
      sources.add(new Source(name, Json.hash(content), content, (int) content.lines().count()));
    }
    Json.require(!sources.isEmpty(), "Workspace contains no supported nonempty text files");
    return new Snapshot(List.copyOf(sources), List.copyOf(skipped));
  }

  static Hit read(List<Source> sources, String path, int start, int count) {
    Source source =
        sources.stream()
            .filter(s -> s.path().equals(path))
            .findFirst()
            .orElseThrow(() -> new Json.Invalid("Source does not exist in this run"));
    Json.require(
        start >= 1 && start <= source.lines() && count >= 1 && count <= 40, "Invalid line range");
    List<String> lines = source.rows();
    int end = Math.min(lines.size(), start + count - 1);
    String text = String.join("\n", lines.subList(start - 1, end));
    Json.require(
        text.length() <= 12000, "Selected lines exceed the tool output limit; request fewer lines");
    String id = Json.hash(source.path() + "\n" + source.hash() + "\n" + start + ":" + end);
    return new Hit(id, path, start, end, text);
  }

  static List<Hit> search(List<Source> sources, String query, int limit) {
    Json.require(
        !query.isBlank() && query.length() <= 200 && limit >= 1 && limit <= 8,
        "Invalid search arguments");
    List<String> terms =
        java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+"))
            .filter(s -> !s.isBlank())
            .distinct()
            .limit(8)
            .toList();
    record Match(Source source, int line, int score) {}
    var matches = new ArrayList<Match>();
    for (Source source : sources) {
      List<String> lines = source.rows();
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i).toLowerCase(Locale.ROOT);
        int score = (int) terms.stream().filter(line::contains).count();
        if (score > 0 && lines.get(i).length() <= 12000)
          matches.add(new Match(source, i + 1, score));
      }
    }
    matches.sort(
        Comparator.comparingInt(Match::score)
            .reversed()
            .thenComparing(m -> m.source().path())
            .thenComparingInt(Match::line));
    return matches.stream()
        .limit(limit)
        .map(m -> read(sources, m.source().path(), m.line(), 1))
        .toList();
  }

  static ArrayNode catalog(List<Source> sources) {
    var node = Json.array();
    sources.forEach(s -> node.add(s.metadata()));
    return node;
  }
}
