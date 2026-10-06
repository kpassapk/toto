# Toto — Design

A coding agent harness in the spirit of [Pi](https://github.com/earendil-works/pi).

| Pi (TypeScript)                         | Toto (Clojure/bb)                          |
| --------------------------------------- | ------------------------------------------ |
| `pi-tui` — custom differential renderer | **charm.clj** — Elm-architecture TUI       |
| TypeScript extensions                   | **sci** — sandboxed Clojure extensions     |
| `pi-ai` — multi-provider LLM API        | `toto.llm` — Anthropic only (v1)           |
| `pi-agent-core` — agent loop            | `toto.agent` — message-driven FSM          |

We do **not** write a renderer or a sandbox: charm.clj already gives line-diffing /
flicker-free rendering (Pi's reason for pi-tui), and sci already gives safe,
configurable Clojure eval (Pi's reason for the TS extension host).

Toto provides agent loop + tools + Anthropic streaming + session log, expressed as a charm program.

## Design principles (inherited from Pi)

- **Minimal.** Four builtin tools: `read`, `write`, `edit`, `bash`.  Use babashka/process
and other babashka tools whenever possible.

Lean system
  prompt (<1000 tokens incl. tool defs). No plan mode, no todo tracker, no MCP,
  no sub-agents, no background bash — these live in files/tmux/extensions, not core.
- **Observable.** Native/inline terminal (preserve scrollback). User sees every
  tool call and result. No hidden orchestration.
- **Loop until done.** Agent runs tool calls and feeds results back until the model
  replies with no tool calls. No `max_steps`.
- **Reshapeable.** Behavior added via sci extensions, not by forking core.

---

## Component map

```
src/toto/
  main.clj          ; entry: arg parse, env, session bootstrap, start program
  app.clj           ; charm init/update/view — the FSM host + rendering
  agent.clj         ; pure agent step: (state, event) -> [state, cmds]
  prompt.clj        ; system prompt assembly (base + AGENTS.md + tool defs)
  session.clj       ; append-only EDN log, continue/resume, AGENTS.md discovery
  tools/
    core.clj        ; tool registry, tool spec, result struct, dispatch
    builtin.clj     ; read / write / edit / bash
  llm/
    core.clj        ; Provider protocol; toto<->wire message mapping
    anthropic.clj   ; streaming Messages API client (SSE over http-client)
  ext/
    api.clj         ; the `toto.api` surface exposed INTO sci (register-tool, host fns)
    core.clj        ; sci context build + extension loader
  ui/
    components.clj   ; charm view helpers: transcript, tool cards, footer, input
bb.edn               ; deps (charm.clj + org.babashka/sci) + tasks
```

---

## Concurrency model — the crux

charm.clj is the **Elm Architecture**: a single-threaded `update` loop. Pure
`update : (state, msg) -> [state, cmd]`. Side effects are `cmd`s executed off the
loop (via core.async); their results re-enter as `msg`s. `view : state -> string`.

The agent is inherently async (network streaming, possibly-blocking bash). So the
agent loop is **not** a blocking `while` — it is a state machine driven by messages.

### Agent FSM (lives in app state)

```
:idle          waiting for user input
:waiting-llm   request in flight; text/tool-call deltas streaming in
:running-tools executing tool calls; results arriving one by one
```

### Messages (charm msgs)

```
{:type :user-submit   :text "..."}                 ; from the input editor
{:type :llm-delta     :text "..."}                 ; streamed assistant text chunk
{:type :llm-tool-call :id .. :name .. :args {..}}  ; a completed tool_use block
{:type :llm-done      :stop-reason .. :usage {..}} ; assistant turn complete
{:type :tool-result   :id .. :result {:llm .. :display ..}}
{:type :tool-error    :id .. :error "..."}
; plus charm key/mouse msgs, handled by the input component
```

### Transitions (in `agent.clj`, called from `app/update`)

- `:user-submit` → append user msg to history; `state :waiting-llm`;
  cmd = **stream-llm** (Anthropic call).
- `:llm-delta` → append text to the in-progress assistant message (re-render).
- `:llm-tool-call` → stash the pending tool call.
- `:llm-done`:
  - if pending tool calls → `state :running-tools`; cmd = **batch** of
    **run-tool** (one per call).
  - else → `state :idle`.
- `:tool-result` / `:tool-error` → record result; when **all** pending results in,
  append them to history; `state :waiting-llm`; cmd = **stream-llm** (loop continues).

This is Pi's "loop until no tool calls", re-expressed as a charm FSM.

### The two effectful cmds

- **stream-llm**: opens the SSE stream and emits `:llm-delta` / `:llm-tool-call`
  messages as bytes arrive, then `:llm-done`. Requires charm to let a long-running
  cmd emit *many* messages over time (streaming subscription), not just one return
  value. **This is the #1 integration question to confirm in Phase 0** (see Risks).
- **run-tool**: executes one tool handler on a worker thread, emits one
  `:tool-result` / `:tool-error`. Keeps bash off the update loop.

---

## Tools

A tool is data + a handler:

```clojure
{:name        "read"
 :description "Read a file. Supports offset/limit for large files."
 :schema      {…JSON Schema for Anthropic tool def…}
 :handler     (fn [args ctx] {:llm "…for the model…" :display "…for the TUI…"})}
```

- **Split result** (Pi pattern): `:llm` text goes back to the model; `:display`
  (possibly richer) is what the transcript shows.
- `ctx` carries cwd, session, config — handlers don't reach globals.
- **Registry**: `name -> tool` atom. Builtins registered at startup; extensions
  append via `toto.api/register-tool`.
- **Builtins** (`tools/builtin.clj`, all bb-native):
  - `read` — `slurp` + offset/limit, line-numbered output.
  - `write` — overwrite file with content.
  - `edit` — exact unique string replacement (error if 0 or >1 matches).
  - `bash` — `babashka.process/shell`, optional timeout, returns stdout/stderr/exit.

Anthropic tool defs are derived from the registry (`:name`/`:description`/`:schema`).

---

## LLM client (Anthropic, v1)

`toto.llm.core` defines a tiny protocol so multi-provider is a later, additive step:

```clojure
(defprotocol Provider
  (stream-chat [_ {:keys [system messages tools model max-tokens]} emit]))
;; `emit` is called with {:type :llm-delta|:llm-tool-call|:llm-done ...}
```

`toto.llm.anthropic`:

- `babashka.http-client` POST `https://api.anthropic.com/v1/messages` with
  `{:as :stream}` → body is an `InputStream`.
- Headers: `x-api-key` (env `ANTHROPIC_API_KEY`), `anthropic-version`, content-type.
- Body: `model`, `system`, `messages`, `tools`, `max_tokens`, `stream true`.
- Parse SSE line-by-line: `message_start`, `content_block_start/_delta/_stop`
  (`text_delta` for prose, `input_json_delta` accumulated into tool_use args),
  `message_delta` (stop_reason, usage), `message_stop`. Call `emit` per event.
- Message mapping: toto's internal history (user / assistant / tool_use /
  tool_result) <-> Anthropic wire format lives here so the rest of the app is
  provider-agnostic.

---

## Extensions (sci)

Extensions are Clojure files evaluated in a **sandboxed sci context** — they can
only touch the host through the `toto.api` surface we hand them. (v1 scope:
extensions **define tools**; slash commands / TUI components / lifecycle hooks are
designed-for but deferred.)

- `ext/api.clj` — the surface injected into sci as namespace `toto.api`:
  - `register-tool` — append a tool spec to the registry.
  - curated host helpers (`slurp`, `spit`, `sh`, `log`, …) so handlers can do real
    work without raw access to everything. **This map is the sandbox boundary.**
- `ext/core.clj`:
  - `sci/init` with `:namespaces {toto.api …, clojure.string …}` etc.
  - Loader: read `*.clj` from `~/.toto/extensions/` and `./.toto/extensions/`,
    `sci/eval-string` each in the shared context.
  - Tool handlers defined in an extension are **sci fns**; invoking a tool calls
    them through the sci context.

**Babashka note:** bb's internal sci context isn't exposed, so we add
`org.babashka/sci` to `bb.edn` deps and build our own context. Extension handlers
then run as sci-inside-sci — fine, they're not hot loops.

**Sandbox vs. Pi's YOLO:** Pi is full-YOLO (no permission system). Toto's sci
boundary lets us choose: default to a generous-but-curated `toto.api` (match Pi's
ergonomics) while retaining the option to lock it down. v1 default: generous.

---

## Sessions & context

- **Session** = append-only event log + metadata `{:id :created :model :cwd}`.
- **Format**: newline-delimited EDN (one event per line) under
  `~/.toto/sessions/<id>.edn` — append-only (crash-safe), resume = replay log into
  message history.
- **continue/resume**: `--continue` (latest session for cwd), `--resume <id>`.
- **AGENTS.md**: discovered hierarchically (cwd → parents, plus `~/.toto/AGENTS.md`),
  concatenated into the system prompt (`prompt.clj`).
- **Out of scope v1**: branching, compaction (designed-for; the append-only log and
  provider message mapping leave room for both).

---

## TUI layout (charm.clj)

Native/inline mode (preserve scrollback), like Pi:

```
┌ transcript (grows in scrollback) ──────────────┐
│ › user message                                  │
│ assistant streamed text…                        │
│ ▸ read(src/foo.clj)            ⟶ 42 lines       │  ← tool card
│ assistant continues…                            │
├─────────────────────────────────────────────────┤
│ > _input editor_                                 │  ← charm text-input
│ claude-opus · idle · 12.3k tok                   │  ← footer/status
└─────────────────────────────────────────────────┘
```

charm components used: `text-input` (editor), `spinner` (during `:waiting-llm`),
optional `viewport`. `view` renders transcript + tool cards + input + footer;
charm caches fully-streamed message components and diffs the rest.

---

## Risks / open integration questions (spike in Phase 0)

1. **Streaming cmd API (highest risk).** Does charm.clj let a single long-running
   `cmd` emit *many* messages over time (token streaming), e.g. a channel-returning
   cmd or a program handle to inject messages? The async design depends on it.
   Fallback: poll a core.async channel via a tick subscription.
2. **sci on bb.** Confirm `org.babashka/sci` loads and builds a context under bb.
3. **SSE incremental read.** Confirm `http-client {:as :stream}` yields bytes
   incrementally (no full-response buffering) on bb.
4. **JLine raw mode on bb.** Confirm key input / raw terminal works under babashka.
