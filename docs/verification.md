# Verification notes for 0.1.0

The automated suite runs without external credentials, model downloads, or an Ollama process. It contains 64 cases across seven test classes. CI repeats the suite and the packaged recovery demo on Linux and Windows. Workflow artifacts contain the test and coverage reports.

| Area | Evidence |
| --- | --- |
| Workspace boundaries | Frozen content after edits; Unicode paths; excluded directories, symlinks, binary text, oversized files; bounded retrieval. |
| Tool contract | Unknown tools, unexpected arguments, invalid citations, duplicate citations, and blank reports are rejected. |
| Persistence | Concurrent claims through separate connections; transactionally reserved attempts; stale checkpoint fencing; recovery beyond the latest-100 display window. |
| Agent lifecycle | Review/revision/approval; pause while a provider returns late; cancellation; parallel capacity; failure without automatic retries; budget boundaries. |
| Ollama HTTP | Actual loopback requests, native tool schema, strict response validation, body deadlines, oversized chunked responses, and rejected redirects. |
| API | Authentication, same-origin checks, Host validation, idempotency after source removal, export, event cursors, malformed input, and a stalled request body. |
| CLI | Separate Java processes for help, version, invalid arguments, and interruption/revision/approval artifacts. |

Local browser checks used the packaged application with the scripted provider and the fictional handbook. They covered sign-in, task creation from a template, source inspection, revision, approval, Markdown download, keyboard tab navigation, a 390×844 viewport, and literal rendering of HTML-like task text. The dashboard screenshot in the README comes from that session.

The token-budget and request-budget checks exercise stopping behavior, not external billing. The restart demonstration reopens a database containing a committed model response and verifies that pending tools consume it without repeating that request. It is a controlled interruption test, not a claim about hardware failure or power-loss durability.

Live Ollama inference has not been validated for this release. No accuracy, throughput, latency, or model-quality claim is based on the scripted provider. Test the exact model you intend to use and review its reports against the cited excerpts.
