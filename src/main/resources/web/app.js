"use strict";
(() => {
  const $ = (id) => document.getElementById(id);
  const labels = {
    QUEUED: "Queued",
    RUNNING: "Running",
    PAUSED: "Paused",
    REVIEW: "Awaiting review",
    COMPLETED: "Approved",
    FAILED: "Failed",
    CANCELLED: "Cancelled",
  };
  const eventLabels = {
    RUN_CREATED: "Workspace captured",
    RUN_STARTED: "Execution started",
    MODEL_REQUESTED: "Model request reserved",
    MODEL_COMPLETED: "Model response saved",
    TOOL_STARTED: "Tool call reserved",
    TOOL_COMPLETED: "Tool result saved",
    REPORT_READY: "Draft ready for review",
    RUN_FAILED: "Execution stopped",
    RECOVERED: "Checkpoint recovered",
    OPERATOR_PAUSE: "Paused by operator",
    OPERATOR_RESUME: "Resumed by operator",
    OPERATOR_CANCEL: "Cancelled by operator",
    OPERATOR_APPROVE: "Report approved",
    OPERATOR_REVISE: "Revision requested",
  };
  const reasons = {
    PROCESS_INTERRUPTED:
      "The service restarted. Resume explicitly from the last committed checkpoint.",
    BUDGET_EXHAUSTED:
      "A request or tool budget was exhausted. Create a new run with suitable limits.",
    TOKEN_BUDGET_EXHAUSTED:
      "Reported model usage reached the token budget. No further calls will be made.",
    MODEL_HTTP_ERROR:
      "The model endpoint returned an error. Check that Ollama is running and the configured model is available.",
    MODEL_TIMEOUT:
      "The model request exceeded its deadline. The attempt is counted; resuming can issue another request.",
    MODEL_TRANSPORT_ERROR:
      "The model endpoint could not complete the request. Check the local service before resuming.",
    MODEL_INVALID_RESPONSE:
      "The provider did not return valid tool calls and usage counts. Use a model that supports tool calling.",
    MODEL_CONTEXT_LIMIT: "The model context exceeded the request size limit.",
    CONTEXT_LIMIT: "The accumulated conversation reached its context limit.",
    EXECUTION_ERROR:
      "Execution could not be committed. Inspect the activity and service configuration before resuming.",
  };
  let token = "",
    runs = [],
    selected = null,
    current = null,
    cursor = 0,
    eventCount = 0,
    selection = 0,
    polling = false,
    reportKey = "",
    listKey = "",
    submission = null;
  const text = (id, value) => {
    $(id).textContent = value;
  };
  const element = (tag, className, value) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (value !== undefined) node.textContent = value;
    return node;
  };
  const time = (value) =>
    new Date(value).toLocaleTimeString([], {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
  const date = (value) =>
    new Date(value).toLocaleDateString([], { month: "short", day: "numeric" });
  function notice(message, error = false) {
    text("notice", message);
    $("notice").className = "notice" + (error ? " error" : "");
    $("notice").hidden = false;
  }
  async function api(path, options = {}) {
    const headers = { Authorization: "Bearer " + token, ...options.headers };
    if (options.body !== undefined)
      headers["Content-Type"] = "application/json";
    const response = await fetch(path, {
      ...options,
      headers,
      body:
        options.body === undefined ? undefined : JSON.stringify(options.body),
    });
    const data = await response.json();
    if (!response.ok) {
      const error = new Error(data.message || data.error || "Request failed");
      error.status = response.status;
      throw error;
    }
    return data;
  }
  function badge(state) {
    return element("span", "badge " + state, labels[state] || state);
  }
  function renderMarkdown(value) {
    const root = $("report-markdown");
    root.replaceChildren();
    let paragraph = [],
      list = null,
      code = null,
      fence = "";
    const flush = () => {
      if (paragraph.length) {
        root.append(element("p", "", paragraph.join("\n")));
        paragraph = [];
      }
      list = null;
    };
    for (const line of value.split(/\r?\n/)) {
      if (code !== null) {
        if (line.trim().startsWith(fence)) {
          root.append(element("pre", "", code.join("\n")));
          code = null;
        } else code.push(line);
        continue;
      }
      const marker = line.match(/^\s*(```|~~~)/);
      if (marker) {
        flush();
        fence = marker[1];
        code = [];
        continue;
      }
      const heading = line.match(/^(#{1,6})\s+(.+)$/);
      if (heading) {
        flush();
        root.append(
          element("h" + Math.min(6, heading[1].length + 2), "", heading[2]),
        );
        continue;
      }
      const item = line.match(/^\s*(?:[-*+]\s+|\d+[.)]\s+)(.+)$/);
      if (item) {
        if (!list) {
          flush();
          list = element("ul");
          root.append(list);
        }
        list.append(element("li", "", item[1]));
        continue;
      }
      if (!line.trim()) {
        flush();
        continue;
      }
      if (line.startsWith("> ")) {
        flush();
        root.append(element("blockquote", "", line.slice(2)));
        continue;
      }
      list = null;
      paragraph.push(line);
    }
    flush();
    if (code !== null) root.append(element("pre", "", code.join("\n")));
  }
  function renderList() {
    const query = $("search").value.toLowerCase(),
      filter = $("status-filter").value;
    const key = JSON.stringify([runs, query, filter, selected]);
    if (key === listKey) return;
    listKey = key;
    const visible = runs.filter(
      (run) =>
        (!filter || run.state === filter) &&
        run.title.toLowerCase().includes(query),
    );
    $("run-list").replaceChildren();
    text("list-count", visible.length);
    text("sidebar-count", runs.length);
    $("empty-list").hidden = visible.length !== 0;
    text(
      "empty-list",
      runs.length
        ? "No runs match these filters."
        : "No runs yet. Start with a question about the documents in your workspace.",
    );
    for (const run of visible) {
      const button = element(
        "button",
        "run-item" + (run.id === selected ? " selected" : ""),
      );
      button.setAttribute("aria-label", `${run.title}, ${labels[run.state]}`);
      button.setAttribute("aria-pressed", String(run.id === selected));
      const top = element("div", "row");
      top.append(badge(run.state), element("small", "", date(run.createdAt)));
      button.append(
        top,
        element("strong", "", run.title),
        element(
          "small",
          "",
          `${run.requests} model requests · ${run.tools} tool calls`,
        ),
      );
      button.addEventListener("click", () => selectRun(run.id));
      $("run-list").append(button);
    }
    text("total-count", runs.length);
    text(
      "active-count",
      runs.filter((r) => ["RUNNING", "QUEUED"].includes(r.state)).length,
    );
    text("review-count", runs.filter((r) => r.state === "REVIEW").length);
    text("complete-count", runs.filter((r) => r.state === "COMPLETED").length);
  }
  function setTab(name, focus = false) {
    for (const button of document.querySelectorAll("[data-tab]")) {
      const active = button.dataset.tab === name;
      button.setAttribute("aria-selected", String(active));
      button.tabIndex = active ? 0 : -1;
      $("pane-" + button.dataset.tab).hidden = !active;
      if (active && focus) button.focus();
    }
  }
  function renderDetail(run) {
    const previous = current;
    current = run;
    text("run-title", run.title);
    text("run-task", run.task);
    text("run-id", run.id);
    const status = $("run-status");
    status.textContent = labels[run.state];
    status.className = "badge " + run.state;
    text(
      "request-budget",
      `${run.requests} / ${run.maxRequests} model requests`,
    );
    text("tool-budget", `${run.tools} / ${run.maxTools} tool calls`);
    text(
      "token-budget",
      `${run.tokens.toLocaleString()} / ${run.maxTokens.toLocaleString()} reported tokens`,
    );
    $("run-reason").hidden = !reasons[run.reason];
    text("run-reason", reasons[run.reason] || "");
    if (!previous || previous.state !== run.state) {
      $("run-actions").replaceChildren();
      const actions = [];
      if (["RUNNING", "QUEUED"].includes(run.state))
        actions.push(["pause", "Pause", false]);
      if (
        ["PAUSED", "FAILED"].includes(run.state) &&
        ![
          "BUDGET_EXHAUSTED",
          "TOKEN_BUDGET_EXHAUSTED",
          "CONTEXT_LIMIT",
          "MODEL_CONTEXT_LIMIT",
        ].includes(run.reason)
      )
        actions.push(["resume", "Resume from checkpoint", false]);
      if (
        ["QUEUED", "RUNNING", "PAUSED", "FAILED", "REVIEW"].includes(run.state)
      )
        actions.push(["cancel", "Cancel run", true]);
      for (const [action, label, danger] of actions) {
        const button = element(
          "button",
          danger ? "danger" : "secondary",
          label,
        );
        button.addEventListener("click", () => control(action, button));
        $("run-actions").append(button);
      }
    }
    const nextKey = JSON.stringify(run.report);
    $("report-empty").hidden = !!run.report;
    $("report-content").hidden = !run.report;
    if (run.report && nextKey !== reportKey) {
      text("report-title", run.report.title);
      renderMarkdown(run.report.markdown);
      $("citations").replaceChildren();
      for (const citation of run.report.citations) {
        const details = element("details", "citation");
        details.append(
          element(
            "summary",
            "",
            `${citation.path} · lines ${citation.startLine}–${citation.endLine}`,
          ),
          element("pre", "", citation.text),
          element("small", "mono", citation.sourceId),
        );
        $("citations").append(details);
      }
      reportKey = nextKey;
    }
    text(
      "report-state",
      run.state === "COMPLETED"
        ? "Approved by operator"
        : "Draft · requires review",
    );
    $("review-controls").hidden = run.state !== "REVIEW";
    if (run.state === "REVIEW" && previous && previous.state !== "REVIEW")
      setTab("report");
  }
  function appendEvents(events) {
    for (const event of events) {
      if (event.seq <= cursor) continue;
      const item = element("li", "event " + event.type),
        header = element("div", "event-head");
      header.append(
        element("strong", "", eventLabels[event.type] || event.type),
        element("time", "", time(event.at)),
      );
      item.append(header);
      let description = "";
      if (event.type === "MODEL_COMPLETED")
        description = `${event.data.tokens || 0} reported tokens · ${event.data.toolCalls || 1} tool calls · ${event.data.durationMs || 0} ms`;
      if (event.type === "RUN_CREATED")
        description = `${event.data.files} files captured · ${event.data.skipped} skipped`;
      if (event.type === "TOOL_COMPLETED")
        description = `${event.data.name}${event.data.isError ? " · rejected arguments or unavailable tool" : ""}`;
      if (event.data.reason) description = event.data.reason;
      if (event.data.feedback) description = event.data.feedback;
      if (description) item.append(element("p", "", description));
      if (event.type === "TOOL_COMPLETED") {
        const details = element("details");
        details.append(
          element("summary", "", "Inspect arguments and result"),
          element("pre", "", JSON.stringify(event.data, null, 2)),
        );
        item.append(details);
      }
      $("event-list").append(item);
      cursor = event.seq;
      eventCount++;
    }
    text("event-count", eventCount || "");
  }
  async function selectRun(id) {
    selected = id;
    current = null;
    cursor = 0;
    eventCount = 0;
    reportKey = "";
    const version = ++selection;
    $("empty-detail").hidden = true;
    $("run-detail").hidden = false;
    $("event-list").replaceChildren();
    $("source-list").replaceChildren();
    $("feedback").value = "";
    text("run-title", "Loading run…");
    text("run-task", "");
    text("event-count", "");
    setTab("activity");
    renderList();
    try {
      const [run, sources, events] = await Promise.all([
        api(`/api/runs/${id}`),
        api(`/api/runs/${id}/sources`),
        api(`/api/runs/${id}/events?after=0`),
      ]);
      if (version !== selection || !token) return;
      renderDetail(run);
      appendEvents(events.events);
      for (const source of sources.sources) {
        const row = element("div", "source-row");
        row.append(
          element("span", "", source.path),
          element(
            "small",
            "",
            `${source.lines} lines · ${source.hash.slice(0, 10)}`,
          ),
        );
        $("source-list").append(row);
      }
      text(
        "skipped-sources",
        run.skipped.length
          ? "Skipped: " + run.skipped.join(", ")
          : "All eligible files were captured.",
      );
      if (run.report) setTab("report");
    } catch (error) {
      if (version === selection) notice(error.message, true);
    }
  }
  async function refresh() {
    if (!token || polling || document.hidden) return;
    polling = true;
    const id = selected,
      version = selection;
    try {
      const list = await api("/api/runs?limit=100");
      if (!token) return;
      runs = list.runs;
      renderList();
      if (id && id === selected) {
        const [run, events] = await Promise.all([
          api(`/api/runs/${id}`),
          api(`/api/runs/${id}/events?after=${cursor}`),
        ]);
        if (version === selection && token) {
          renderDetail(run);
          appendEvents(events.events);
        }
      }
      text("connection-state", "Connected · updated " + time(Date.now()));
    } catch (error) {
      text("connection-state", "Connection interrupted");
      if (error.status === 401) {
        disconnect();
        text(
          "login-error",
          "The access token was rejected. Reconnect to continue.",
        );
      }
    } finally {
      polling = false;
    }
  }
  async function control(action, button) {
    if (!selected) return;
    const id = selected,
      version = selection,
      feedback = $("feedback").value.trim();
    if (action === "revise" && !feedback) {
      notice("Write revision feedback before requesting a new draft.", true);
      $("feedback").focus();
      return;
    }
    button.disabled = true;
    try {
      const run = await api(`/api/runs/${id}/${action}`, {
        method: "POST",
        body: action === "revise" ? { feedback } : {},
      });
      if (version !== selection) return;
      renderDetail(run);
      notice(
        action === "revise"
          ? "Revision queued. The existing sources and conversation will be reused."
          : "Run updated: " + labels[run.state] + ".",
      );
      if (action === "revise") {
        $("feedback").value = "";
        setTab("activity");
      }
      await refresh();
    } catch (error) {
      notice(error.message, true);
    } finally {
      button.disabled = false;
    }
  }
  function openCreate() {
    submission = null;
    $("create-form").reset();
    text("create-error", "");
    $("create-dialog").showModal();
    $("new-title").focus();
  }
  function disconnect() {
    token = "";
    selection++;
    selected = null;
    current = null;
    runs = [];
    $("app").hidden = true;
    $("login").hidden = false;
    $("access-token").value = "";
    $("access-token").focus();
  }
  $("login-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const button = event.currentTarget.querySelector("button");
    button.disabled = true;
    token = $("access-token").value;
    text("login-error", "");
    try {
      const meta = await api("/api/meta");
      text("workspace-name", meta.workspace);
      text("provider-name", meta.provider.model);
      text(
        "provider-mode",
        meta.provider.mode === "demo" ? "DEMO PROVIDER" : "OLLAMA CONNECTOR",
      );
      text("version", meta.version);
      $("demo-banner").hidden = meta.provider.mode !== "demo";
      $("access-token").value = "";
      $("login").hidden = true;
      $("app").hidden = false;
      $("empty-detail").hidden = false;
      $("run-detail").hidden = true;
      $("notice").hidden = true;
      await refresh();
      if (runs.length) await selectRun(runs[0].id);
    } catch (error) {
      token = "";
      text("login-error", error.message);
    } finally {
      button.disabled = false;
    }
  });
  $("create-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const button = $("submit-run");
    button.disabled = true;
    text("create-error", "");
    try {
      const body = {
        title: $("new-title").value.trim(),
        task: $("new-task").value.trim(),
        maxRequests: Number($("max-requests").value),
        maxTools: Number($("max-tools").value),
        maxTokens: Number($("max-tokens").value),
      };
      const signature = JSON.stringify(body);
      if (!submission || submission.signature !== signature)
        submission = { signature, key: crypto.randomUUID() };
      const run = await api("/api/runs", {
        method: "POST",
        headers: { "Idempotency-Key": submission.key },
        body,
      });
      $("create-dialog").close();
      runs = [run, ...runs.filter((item) => item.id !== run.id)].slice(0, 100);
      await selectRun(run.id);
      notice("Run queued. Its document snapshot is saved.");
    } catch (error) {
      text("create-error", error.message);
    } finally {
      button.disabled = false;
    }
  });
  const templates = {
    release: [
      "Review release readiness",
      "Review the release procedure. Identify deployment prerequisites, rollback conditions, and the owner of each decision. Cite the captured sources and flag missing information.",
    ],
    incident: [
      "Review incident response",
      "Review the incident runbook. Summarize escalation steps, evidence to collect, and recovery criteria. Cite the relevant sources and separate documented requirements from suggestions.",
    ],
    onboarding: [
      "Prepare an onboarding brief",
      "Create an onboarding brief from this workspace. Explain key systems, operating procedures, and unresolved questions. Include citations to the captured documents.",
    ],
  };
  $("run-template").addEventListener("change", (event) => {
    const item = templates[event.target.value];
    if (item) {
      $("new-title").value = item[0];
      $("new-task").value = item[1];
    }
  });
  $("download-report").addEventListener("click", async () => {
    if (!selected) return;
    try {
      const response = await fetch(`/api/runs/${selected}/export`, {
        headers: { Authorization: "Bearer " + token },
      });
      if (!response.ok) throw new Error("Report export failed");
      const url = URL.createObjectURL(await response.blob());
      const a = element("a");
      a.href = url;
      a.download = `run-${selected}.md`;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      notice(error.message, true);
    }
  });
  $("new-run").addEventListener("click", openCreate);
  $("empty-new-run").addEventListener("click", openCreate);
  $("close-dialog").addEventListener("click", () => $("create-dialog").close());
  $("logout").addEventListener("click", disconnect);
  $("search").addEventListener("input", renderList);
  $("status-filter").addEventListener("change", renderList);
  $("all-runs").addEventListener("click", () => {
    $("search").value = "";
    $("status-filter").value = "";
    renderList();
  });
  $("approve-report").addEventListener("click", (event) =>
    control("approve", event.currentTarget),
  );
  $("revise-report").addEventListener("click", (event) =>
    control("revise", event.currentTarget),
  );
  for (const button of document.querySelectorAll("[data-tab]")) {
    button.addEventListener("click", () => setTab(button.dataset.tab));
    button.addEventListener("keydown", (event) => {
      if (["ArrowLeft", "ArrowRight"].includes(event.key)) {
        event.preventDefault();
        const names = ["activity", "report", "sources"],
          index = names.indexOf(button.dataset.tab);
        setTab(names[(index + (event.key === "ArrowRight" ? 1 : 2)) % 3], true);
      }
    });
  }
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) refresh();
  });
  setInterval(refresh, 1500);
  setTab("activity");
})();
