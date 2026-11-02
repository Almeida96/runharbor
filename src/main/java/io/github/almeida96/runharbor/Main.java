package io.github.almeida96.runharbor;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public final class Main {
  public static final String VERSION = "0.1.0";

  private Main() {}

  private static final String HELP =
      """
      RunHarbor 0.1.0 — durable document agents

      serve --workspace DIR --db FILE --token-env ENV [--provider ollama|demo]
            [--endpoint http://127.0.0.1:11434/api/chat] [--model MODEL]
            [--port 8790] [--parallel 2] [--timeout-ms 120000]
      demo --out NEW_DIRECTORY

      serve starts the local dashboard and authenticated API on 127.0.0.1.
      Ollama mode requires --model and an existing tool-capable model.
      demo runs a scripted provider through snapshot, checkpoint, revision, and approval.
      Exit codes: 0 success, 1 invalid configuration or operation failure.
      """;

  public static void main(String[] args) {
    System.setOut(
        new java.io.PrintStream(
            new java.io.FileOutputStream(java.io.FileDescriptor.out), true, UTF_8));
    System.setErr(
        new java.io.PrintStream(
            new java.io.FileOutputStream(java.io.FileDescriptor.err), true, UTF_8));
    int code = 0;
    try {
      execute(args);
    } catch (Json.Invalid e) {
      System.err.println(e.getMessage());
      code = 1;
    } catch (Exception e) {
      System.err.println(
          "Command failed. Check the workspace, database, and provider configuration.");
      code = 1;
    }
    System.exit(code);
  }

  private static void execute(String[] args) throws Exception {
    if (args.length == 0 || java.util.Arrays.equals(args, new String[] {"--help"})) {
      System.out.println(HELP);
      return;
    }
    if (java.util.Arrays.equals(args, new String[] {"--version"})) {
      System.out.println(VERSION);
      return;
    }
    Json.require(
        args.length % 2 == 1 && Set.of("serve", "demo").contains(args[0]),
        "Invalid command or option pairs");
    Set<String> allowed =
        args[0].equals("demo")
            ? Set.of("out")
            : Set.of(
                "workspace",
                "db",
                "token-env",
                "provider",
                "endpoint",
                "model",
                "port",
                "parallel",
                "timeout-ms");
    var values = new HashMap<String, String>();
    for (int i = 1; i < args.length; i += 2) {
      Json.require(
          args[i].startsWith("--") && allowed.contains(args[i].substring(2)), "Unknown option");
      Json.require(
          !args[i + 1].isBlank() && values.put(args[i].substring(2), args[i + 1]) == null,
          "Blank or duplicate option");
    }
    if (args[0].equals("demo")) {
      Demo.run(Path.of(required(values, "out")));
      return;
    }
    Path workspace = Path.of(required(values, "workspace")),
        database = Path.of(required(values, "db"));
    Workspace.capture(workspace);
    String env = required(values, "token-env");
    Json.require(env.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"), "Invalid token environment variable");
    String token = System.getenv(env);
    Api.validateToken(token);
    String mode = values.getOrDefault("provider", "ollama");
    Json.require(Set.of("ollama", "demo").contains(mode), "Invalid provider mode");
    var stop = new CountDownLatch(1);
    var stopped = new CountDownLatch(1);
    Thread hook =
        new Thread(
            () -> {
              stop.countDown();
              try {
                stopped.await(30, java.util.concurrent.TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            "runharbor-shutdown");
    try {
      try (var lock = new ProcessLock(database);
          Provider provider =
              mode.equals("demo")
                  ? new DemoProvider()
                  : new Ollama(
                      URI.create(
                          values.getOrDefault("endpoint", "http://127.0.0.1:11434/api/chat")),
                      required(values, "model"),
                      Integer.parseInt(values.getOrDefault("timeout-ms", "120000")));
          var store = new Store(database);
          var engine =
              new Engine(store, provider, Integer.parseInt(values.getOrDefault("parallel", "2")));
          var api =
              new Api(
                  store,
                  engine,
                  provider,
                  workspace,
                  token,
                  Integer.parseInt(values.getOrDefault("port", "8790")))) {
        lock.verifyHeld();
        System.out.println(
            Json.obj(
                "url",
                "http://127.0.0.1:" + api.port(),
                "provider",
                provider.description(),
                "version",
                VERSION));
        Runtime.getRuntime().addShutdownHook(hook);
        stop.await();
      }
    } finally {
      stopped.countDown();
      try {
        Runtime.getRuntime().removeShutdownHook(hook);
      } catch (IllegalStateException shuttingDown) {
        /* Shutdown already owns the hook. */
      }
    }
  }

  private static String required(java.util.Map<String, String> values, String key) {
    String value = values.get(key);
    Json.require(value != null, "Required option is missing");
    return value;
  }
}
