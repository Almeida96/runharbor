# Architecture and execution semantics

RunHarbor has one process, one SQLite database, one configured provider, and one source directory. The HTTP server, scheduler, and workers share a synchronized store. Model calls execute outside database transactions. Virtual threads handle workers and HTTP requests; the scheduler enforces a separate limit of one to four active runs.

## Components

| Component | Responsibility |
| --- | --- |
| `Main`, `ProcessLock` | Configuration, process lifetime, exclusive access to the database. |
| `Api` | Loopback HTTP, bearer authentication, request validation, static dashboard assets. |
| `Workspace` | Capture files, hash content, retrieve bounded excerpts. |
| `Store` | Transactions, queue claims, checkpoints, counters, source snapshots, events. |
| `Engine` | Execute the agent loop and fence obsolete workers. |
| `Provider`, `Ollama` | Model boundary and bounded HTTP transport. |
| `Tools` | Validate tool calls and report citations. |
| `DemoProvider`, `Demo` | Repeatable workflow exercise without model inference. |

There is no web framework or agent framework. The dashboard uses browser APIs and ships inside the application JAR; it has no CDN or frontend build dependency.

The report view formats headings, lists, paragraphs, block quotes, and fenced code using DOM text nodes. It does not interpret raw HTML, images, links, or inline Markdown. Export preserves the original Markdown.

## Persistent data

`runs` holds the task, state, limits, counters, provider identity, conversation checkpoint, and current report. `sources` holds complete captured file contents keyed by run and relative path. `events` is an ordered record of committed actions; sequence numbers are global to the database and can have gaps when filtering by run.

SQLite uses WAL, foreign keys, a five-second busy timeout, and `BEGIN IMMEDIATE` for mutations. The database has an application ID and schema version. Startup rejects unrelated databases and unsupported versions. The command-line service holds an OS file lock beside the database; it does not support multiple server processes sharing one database.

## State transitions

| Current state | Action or outcome | Next state |
| --- | --- | --- |
| `QUEUED` | Worker claims the run | `RUNNING` |
| `QUEUED`, `RUNNING` | Operator pauses | `PAUSED` |
| `RUNNING` | Report submitted with valid citations | `REVIEW` |
| `RUNNING` | Provider error, resource limit, or exhausted budget | `FAILED` |
| `PAUSED`, `FAILED` | Resume with matching provider and remaining budget | `QUEUED` |
| `REVIEW` | Operator requests revision | `QUEUED` |
| `REVIEW` | Operator approves | `COMPLETED` |
| `QUEUED`, `RUNNING`, `PAUSED`, `FAILED`, `REVIEW` | Operator cancels | `CANCELLED` |

`COMPLETED` and `CANCELLED` are terminal. Invalid transitions return HTTP 409. Revision appends operator feedback to the conversation, clears the current report, and retains the original snapshot and cumulative budgets. Previous submitted reports remain in tool-result events.

On startup, `RUNNING` runs become `PAUSED`. Queued runs whose provider identity differs from the configured provider are also paused. Queued runs with a matching provider can start normally. Resume is explicit for interrupted work.

## Checkpoint boundaries

1. Claim a queued run and increment its generation in one transaction.
2. Reserve a model-request attempt before making the external call.
3. Save the returned assistant message, pending tool calls, usage, and completion event atomically.
4. Reserve a tool-call attempt before executing a tool.
5. Save its result, updated evidence map, cursor, and completion event atomically.
6. Repeat until a report is ready for review or the run stops.

The generation changes on every state transition. A worker can commit only while its run is `RUNNING` and its generation still matches. Pausing or cancelling therefore rejects late responses. A paused worker remains in the active-worker set until it exits, so resuming cannot overlap it with a second worker for the same run.

| Interruption point | Behavior after resume |
| --- | --- |
| Before request reservation | The next request can be attempted normally. |
| After reservation, before response commit | The attempt remains counted. The request can be repeated. |
| After response commit, before tool execution | Pending tool calls execute without repeating the saved model request. |
| After tool execution, before its commit | The read-only tool can run again. Its reserved attempt remains counted. |
| After tool-result commit | The cursor advances; that completed call is not repeated. |

The runtime does not promise exactly-once external calls. There are no automatic provider retries. Resume can retry a failed step when budget remains. A model response arriving after a pause is discarded, including its usage count.

## Budgets and bounds

Request and tool counters count reserved attempts. They never reset on revision or resume. Pending tools may still execute after the last allowed model request, provided tool and token budgets remain.

Reported tokens are the sum of `prompt_eval_count` and `eval_count` for committed responses. Reaching or exceeding the threshold stops execution before another tool or model call. One response can overshoot the threshold. Uncommitted late responses are not included, so this metric is unsuitable as a billing guarantee.

| Resource | Bound |
| --- | --- |
| Runs executing at once | 1–4, default 2 |
| Model requests per run | 1–30, default 10 |
| Tool attempts per run | 1–80, default 30 |
| Reported tokens per run | 100–200,000, default 40,000 |
| Model request timeout | 100–300,000 ms, default 120,000 |
| Tool calls in one model response | 1–4; `submit_report` must be called alone |
| Model request / response bytes | 400,000 / 1,048,576 |
| Conversation / checkpoint characters | 300,000 / 400,000 |
| Unique retrieved excerpts | 100 per run |
| HTTP JSON request body | 65,536 bytes and a five-second read deadline |
| Concurrent HTTP handlers | 16; additional requests receive 503 |
| Listed runs / event page | 100 / 200 |

There is no database-wide quota, automatic retention policy, or run-deletion endpoint. Operators must monitor storage and archive stopped databases as needed.

## Source identity and retrieval

Capture visits at most 10,000 filesystem entries and does not descend past nine levels. It skips hidden paths and `node_modules`, `build`, `dist`, `target`, and `vendor`. Eligible files are ordered by path. The captured data is frozen per file; reading multiple live files is not an atomic filesystem snapshot.

Each source ID is SHA-256 over the relative path, content hash, and selected line range. `workspace_read` returns up to 40 lines and 12,000 characters. `workspace_search` matches the first eight distinct whitespace-separated terms case-insensitively, ranks lines by the number of matched terms, and returns up to eight single-line excerpts. It does not use embeddings, regular expressions, stemming, or semantic ranking.

Reports must include one to twenty distinct source IDs already retrieved in the same run. The backend fills citation paths, line numbers, and text from saved evidence, rather than trusting model-supplied metadata. Citation membership is checked; semantic support is not.

## Trust boundary

Document text is untrusted input to the model. The system prompt tells it to treat embedded instructions as data, but prompt injection can still influence a report. The four tool implementations constrain what it can do: they expose only the captured sources and report submission. Tool names, arguments, and bounds are validated independently of the model.

This is not a sandbox for arbitrary plugins. Adding write tools or external integrations would require a different approval and recovery design. The current human decision approves a report, not permission to perform an external action.

The configured workspace, database, model endpoint, and local OS account are trusted. Symlink entries are excluded, but capture does not defend against a malicious local process swapping parent directories during traversal. The provider identity binds its adapter version, endpoint, and model name; it does not fingerprint model weights that may change under the same name.
