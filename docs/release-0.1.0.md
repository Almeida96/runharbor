RunHarbor runs document agents with durable checkpoints and a local review dashboard.

This first release includes a SQLite queue, frozen source snapshots, an Ollama tool-calling connector, pause/resume/cancel controls, citation-backed drafts, revision feedback, and explicit report approval. The HTTP API supports scripts; Markdown exports include the report state and source references.

Download the ZIP and verify it with `SHA256SUMS`. Java 21 is required. The package includes launch scripts for Windows and Unix, runtime dependencies, a fictional handbook, and installation/API documentation.

To try the recovery workflow without a model, extract the ZIP and run:

```sh
bin/runharbor demo --out demo-output
```

On Windows, use `bin\runharbor.bat`. The output directory must be new. The scripted demo tests interruption, snapshot reuse, revision, and approval; it does not perform language-model inference.

For live tasks, start Ollama with an installed tool-capable model and follow the README. Automated checks exercise the connector against an HTTP fixture, not a live model. The service is designed for one operator on a local machine; see the documented recovery and data-handling limits before integration.
