# HTTP API

The default base URL is `http://127.0.0.1:8790`. Every `/api/` endpoint requires `Authorization: Bearer <token>`. The token comes from the environment variable named by `--token-env`. No API token is accepted in query strings or cookies.

Send JSON with `Content-Type: application/json`. Unknown fields, duplicate JSON keys, trailing values, and invalid numeric types are rejected. Errors use a JSON `error` code, with a `message` for controlled input or state errors. Unexpected failures do not expose stack traces or provider response bodies.

## Endpoints

| Method | Path | Result |
| --- | --- | --- |
| GET | `/api/meta` | Version, provider mode/model, workspace basename, worker status, tool schemas. |
| GET | `/api/runs?state=REVIEW&limit=100` | Recent run summaries; both filters are optional. |
| POST | `/api/runs` | Capture documents and queue a run. Requires `Idempotency-Key`. |
| GET | `/api/runs/{id}` | Run details, task, current report, skipped-source notices. |
| GET | `/api/runs/{id}/events?after=0` | Up to 200 events with `seq` greater than the cursor. |
| GET | `/api/runs/{id}/sources` | Captured source paths, content hashes, and line counts. |
| GET | `/api/runs/{id}/evidence/{sourceId}` | An excerpt already retrieved by this run. |
| GET | `/api/runs/{id}/export` | Markdown attachment with status and citation references. |
| POST | `/api/runs/{id}/pause` | Pause queued or running work. Body: `{}`. |
| POST | `/api/runs/{id}/resume` | Requeue paused or failed work when allowed. Body: `{}`. |
| POST | `/api/runs/{id}/cancel` | Cancel a nonterminal run. Body: `{}`. |
| POST | `/api/runs/{id}/approve` | Complete a run awaiting review. Body: `{}`. |
| POST | `/api/runs/{id}/revise` | Append feedback and requeue a draft. Body: `{"feedback":"..."}`. |

IDs are UUID strings. Timestamps are epoch milliseconds. Run summaries include `id`, `title`, `state`, `reason`, `createdAt`, `updatedAt`, `requests`, `tools`, `tokens`, and the three corresponding maximum values. Details add `task`, `report`, and `skipped`. Internal conversation checkpoints and provider keys are not returned through the run-detail endpoint. Tool events do contain arguments and outputs.

## Create a run

```sh
curl --fail-with-body http://127.0.0.1:8790/api/runs \
  -H "Authorization: Bearer $RUNHARBOR_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: release-review-001' \
  --data-binary @examples/request.json
```

PowerShell:

```powershell
$headers = @{
  Authorization = "Bearer $env:RUNHARBOR_TOKEN"
  'Idempotency-Key' = 'release-review-001'
}
$run = Invoke-RestMethod -Method Post `
  -Uri http://127.0.0.1:8790/api/runs `
  -Headers $headers -ContentType application/json `
  -Body (Get-Content examples/request.json -Raw)
$run.id
```

Request body:

```json
{
  "title": "Review release readiness",
  "task": "Identify deployment prerequisites, rollback conditions, and decision owners. Cite the sources and flag missing information.",
  "maxRequests": 10,
  "maxTools": 30,
  "maxTokens": 40000
}
```

`title` and `task` are required, with limits of 120 and 8,000 characters. Budget fields are optional. See [the budget table](architecture.md#budgets-and-bounds) for allowed ranges. Creation returns HTTP 201 and run details. A worker may claim the run before the client receives the response.

An idempotency key contains 8–100 ASCII letters, digits, dots, underscores, or hyphens. Reusing a key with the same task, title, budget settings, and provider returns the original run. Changed settings return 409. A retry reuses the original snapshot even if the source files have changed or disappeared. Concurrent duplicate submissions are checked again inside the creation transaction. Use a new key to create a new snapshot and investigation.

## Events and evidence

```json
{
  "events": [
    {
      "seq": 12,
      "type": "TOOL_COMPLETED",
      "at": 1793640000000,
      "data": {
        "name": "workspace_list",
        "arguments": {},
        "output": {"sources": []},
        "isError": false
      }
    }
  ]
}
```

The shape above is illustrative. Event data varies by type. Poll again with the last received `seq`; keep reading until the page is empty. Sequence numbers are global, so gaps are expected. Event types are `RUN_CREATED`, `RUN_STARTED`, `MODEL_REQUESTED`, `MODEL_COMPLETED`, `TOOL_STARTED`, `TOOL_COMPLETED`, `REPORT_READY`, `RUN_FAILED`, `RECOVERED`, and `OPERATOR_<ACTION>`.

A report contains `title`, `markdown`, and `citations`. Each citation contains `sourceId`, `path`, `startLine`, `endLine`, and `text`. The evidence endpoint returns that same excerpt shape. It does not provide arbitrary file reads. Exports are available for drafts as well as approved reports, and explicitly include the current state.

## Response codes

| Status | Meaning |
| --- | --- |
| 200 / 201 | Successful read/action or creation. |
| 400 | Invalid input, query, or Host header. |
| 401 | Missing or invalid bearer token. |
| 403 | Browser Origin does not match the local Host. |
| 404 | Unknown route, run, or retrieved evidence. |
| 405 | Unsupported method; see the `Allow` header. |
| 408 | JSON body did not arrive within five seconds. |
| 409 | Invalid state transition, exhausted resume budget, provider mismatch, or idempotency conflict. |
| 413 / 415 | Body too large or wrong content type. |
| 500 / 503 | Internal failure or HTTP-handler capacity exhausted. |

There is no CORS support. Browser clients must use the served dashboard's origin. CLI clients can omit `Origin`, but `Host` must be `localhost:<port>` or `127.0.0.1:<port>`. The API is versioned with the application; v0.1 is not a stable long-term compatibility promise.
