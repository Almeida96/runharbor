# Changelog

## 0.1.0 — 2026-11-02

- Added a local dashboard for queueing document investigations, inspecting activity and sources, and reviewing reports.
- Added a Java agent loop with SQLite checkpoints, bounded concurrency, pause/resume/cancel, and generation fencing for late responses.
- Added immutable per-run document snapshots, literal search, line-range retrieval, and validation of cited source IDs.
- Added native Ollama tool calling with request deadlines, response-size bounds, strict response validation, and usage accounting.
- Added revision feedback, explicit report approval, Markdown exports, and an authenticated HTTP API.
- Added a scripted interruption/recovery demo, example handbook, cross-platform tests, and packaged launch scripts.

This release targets local use by one operator. It does not include external write tools, embeddings, hosted multi-user access, or model-quality benchmarks.
