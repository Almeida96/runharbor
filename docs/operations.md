# Installation and operations

## Distribution

Download `runharbor-0.1.0.zip` and `SHA256SUMS` from the [release page](https://github.com/Almeida96/runharbor/releases/tag/v0.1.0). Check the SHA-256 before extracting:

```sh
sha256sum --check SHA256SUMS
unzip runharbor-0.1.0.zip
cd runharbor-0.1.0
bin/runharbor --version
bin/runharbor demo --out demo-output
```

PowerShell:

```powershell
Get-FileHash runharbor-0.1.0.zip -Algorithm SHA256
Get-Content SHA256SUMS
Expand-Archive runharbor-0.1.0.zip -DestinationPath .
Set-Location runharbor-0.1.0
.\bin\runharbor.bat --version
.\bin\runharbor.bat demo --out demo-output
```

Compare the PowerShell hash with the entry for that ZIP. Java 21 is required. The distribution includes dependencies; it does not bundle a JDK or a language model. In README commands, replace `build/install/runharbor/bin/runharbor` with `bin/runharbor` when using the extracted release.

## Configuration

| Option | Meaning / default |
| --- | --- |
| `--workspace DIR` | Required directory of supported documents. |
| `--db FILE` | Required SQLite database path; parent directories are created. |
| `--token-env NAME` | Required environment-variable name holding a 16–8,192 character printable token without spaces. |
| `--provider ollama\|demo` | `ollama` by default. Demo is scripted and visibly labeled. |
| `--model NAME` | Required with Ollama; must already be available to that service. |
| `--endpoint URL` | `http://127.0.0.1:11434/api/chat`; full chat URL. |
| `--timeout-ms N` | `120000`; includes reading the model response body. |
| `--parallel N` | `2`; accepts 1–4 active workers. |
| `--port N` | `8790`; `0` chooses a free port and prints it on startup. |

Only HTTPS or loopback HTTP model endpoints are accepted. URL credentials, query strings, fragments, and HTTP redirects are rejected. Authentication headers for remote providers are not implemented. A remote HTTPS endpoint must speak the Ollama chat contract without additional credentials.

Choose a model with tool support and enough context for the task. The connector requests nonstreaming responses, disables thinking, sets temperature to zero, and limits generated output to 2,048 tokens. These settings do not guarantee deterministic model responses. Model usage counts are required. An endpoint that returns only prose or omits those counts causes `MODEL_INVALID_RESPONSE`.

## Data handling

Keep the database on a local filesystem, outside OneDrive, Dropbox, network shares, and source control. Restrict its directory to the OS account running the service. SQLite files, WAL sidecars, event exports, and reports can contain sensitive document text. There is no application-level encryption or redaction.

Use a dedicated, curated source directory. An accepted extension does not mean a file is safe to send to a model. `.properties`, JSON, and YAML files may contain credentials. Hidden files and directories are excluded, but RunHarbor is not a secret scanner.

All clients with the bearer token share full access to the database. The server binds only to `127.0.0.1`; do not expose it through a public tunnel or reverse proxy. User accounts, roles, SSO, TLS termination, multi-tenant isolation, and external approval identities are outside this release's scope.

## Stop, restart, and back up

Pause active runs before a planned stop when convenient. After an unexpected stop, start with the same database, model endpoint, and model name. Interrupted runs appear as paused; inspect their last events and resume explicitly. A model request interrupted before its response was committed may run again and consume another request attempt.

For a simple backup, stop the service, wait for it to exit, and copy the database directory, including any remaining `-wal` and `-shm` files. Restore the set together while the service is stopped. Do not copy only the live `.db` file during execution. The adjacent `.lock` file may remain after shutdown; its presence alone does not indicate that a process owns the OS lock.

The application rejects unsupported schema versions. There is no automatic upgrade path from future schemas or a retention job. Archive old stopped databases and start a new database when needed. The dashboard displays only the latest 100 runs; this is a display window, not deletion or retention.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Cannot connect to dashboard | Read the startup URL, verify the process is running, and use the configured port. |
| Unauthorized | Use the token from the same environment as the server; refreshing clears the browser's copy. |
| Origin or Host rejected | Use the local URL directly. Cross-origin frontends and forwarded hostnames are unsupported. |
| No supported files | Use nonempty UTF-8 files with supported extensions; inspect the snapshot limits in the README. |
| `MODEL_HTTP_ERROR` | Check Ollama and the exact installed model name. |
| `MODEL_TIMEOUT` | Check model load time and hardware, then adjust the timeout or resume when ready. |
| `MODEL_INVALID_RESPONSE` | Use a native tool-capable model that returns usage counts and valid object arguments. |
| `BUDGET_EXHAUSTED` | Start a new run with suitable request/tool limits. Existing limits cannot be increased. |
| `TOKEN_BUDGET_EXHAUSTED` | Start a new run with a higher threshold or a smaller task. |
| `CONTEXT_LIMIT` / `MODEL_CONTEXT_LIMIT` | Narrow the task or source directory. Conversations are not automatically summarized. |
| Resume returns conflict | Check the current state, remaining budgets, and provider identity. |
| Second service cannot open database | Stop the existing process or choose another database. Do not remove its lock while it is running. |

For a reproducible bug report, include the application version, OS, Java version, provider mode, error code, and minimal fictional documents. Do not post real access tokens, full databases, or private source excerpts.
