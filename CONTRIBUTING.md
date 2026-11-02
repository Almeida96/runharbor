# Contributing

Use JDK 21. Build and run the tests with the checked-in wrapper:

```sh
./gradlew test installDist distZip
```

On Windows, use `gradlew.bat`. Compiler warnings fail the build. Dependency versions are pinned and Gradle resolution is locked in `gradle.lockfile`; update the lock intentionally when changing a dependency.

Keep changes focused on a concrete workflow or failure case. A persistence change should explain what happens if the process stops before and after the new transaction. A new provider must preserve bounded responses, deadlines, cancellation, and sanitized errors. Tool changes must enforce argument validation and document their effects.

Tests use temporary directories, SQLite files, loopback HTTP servers, and scripted providers. They must not require a model download, external credentials, or a running Ollama instance. Add a regression test for a behavior change; do not replace integration cases with assertions about private implementation details.

For dashboard changes, verify creation, review, revision, export, keyboard navigation, and a narrow viewport. Render document content as text rather than HTML. Keep credentials out of URLs, persistent browser storage, logs, and screenshots.

Describe the problem, resulting behavior, and validation in a pull request. Call out API, database, or recovery compatibility changes. Include fictional fixtures rather than copied private documents.

To reproduce the interruption exercise manually:

```sh
build/install/runharbor/bin/runharbor demo --out build/manual-demo
```

Use a new output path for each invocation. For a live-provider check, follow the README's Ollama setup, record the exact model and hardware, and inspect citation support yourself. Protocol fixtures alone are not evidence of answer quality.
