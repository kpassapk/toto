# Toto — Implementation Plan

Target: Babashka
host:  Anthropic-only LLM
scope: walking skeleton + sessions
extensions: tool definitions.  See `DESIGN.md` for rationale.

Each phase ends with a concrete demo (acceptance). Build order de-risks the two
unknowns first (charm streaming, sci-in-bb), then headless agent, then TUI, then
extensions, then session/CLI polish.

---

## Phase 0 — Spikes (de-risk) 

Checks with `clj-nrepl-eval` or throwaway scripts in `spike/`

Use babashka built-in libraies for HTTP, babashka.process, babashka.cli etc. 

Goal: prove the four risky integrations before committing structure.

- [x] **0.1 charm hello-loop** — init/update/view on bb: type, echo, quit on
      `ctrl-c`. Confirms JLine raw mode + run-async under babashka.
      → `spike/02_charm_hello.clj`, PASS.
- [x] **0.2 streaming cmd** — a cmd that emits N delayed messages into the loop;
      view counts them live. Settles **Risk #1** (how a cmd streams many msgs).
      → `spike/05_charm_stream.clj`, PASS. Mechanism = producer thread + side
      core.async channel + self-rescheduling `poll` cmd (0.2.71 has no msg-chan
      injection; this is the timer pattern over a channel). See `spike/FINDINGS.md`.
- [x] **0.3 sci-in-bb** — add `org.babashka/sci`; build a context; `eval-string`
      an extension that calls a stubbed `register-tool`; confirm the tool appears.
      → `spike/01_sci.clj`, PASS (sandbox boundary holds: raw slurp blocked).
- [x] **0.4 Anthropic SSE** — raw bb script: stream one completion to stdout via
      `http-client {:as :stream}`, parsing `text_delta`. Confirms incremental read.
      → `spike/03_anthropic_sse.clj`. Parser + incremental read PASS (synthetic
      pipe). Live streaming now confirmed end-to-end via the Phase 1 headless
      driver (`accept-encoding: identity` to avoid gzip buffering).
- [x] **0.5 SQLite** — for persistence, try the babashka sqlite3 pod. https://github.com/babashka/pod-babashka-go-sqlite3
      → `spike/04_sqlite.clj`, PASS (pod `org.babashka/go-sqlite3` 0.2.5).

**Acceptance:** spikes run under `bb` (0.4 live call pending an API key). Charm
streaming mechanism documented in `spike/FINDINGS.md` (gates the FSM wiring).

NB:
Babashka built-in libraies:

babashka.cli: CLI arg parsing

babashka.http-client: making HTTP requests

babashka.process: shelling out to external processes

babashka.fs: file system manipulation

bencode.core aliased as bencode: read-bencode, write-bencode

cheshire.core aliased as json: dealing with JSON

clojure.core.async aliased as async.

clojure.data.csv aliased as csv

clojure.data.xml aliased as xml

clojure.tools.cli aliased as tools.cli

clj-yaml.core alias as yaml

cognitect.transit aliased as transit

org.httpkit.client

org.httpkit.server

clojure.core.match

hiccup.core and hiccup2.core

clojure.test.check:

    clojure.test.check

    clojure.test.check.generators

    clojure.test.check.properties

rewrite-clj:

    rewrite-clj.parser

    rewrite-clj.node

    rewrite-clj.zip

    rewrite-clj.paredit

Selmer:

    selmer.parser

clojure.tools.logging

taoensso.timbre: logging

edamame.core: Clojure parser

core.rrb-vector

nextjournal.markdown


---

## Phase 1 — Headless agent core (no TUI)

Goal: a working agent loop driven from the REPL/stdout, no charm yet.

- [x] **1.1 `llm/core`** — `Provider` protocol; internal toto message model
      (`user`/`assistant`/`tool` constructors). Wire mapping lives in `anthropic`.
- [x] **1.2 `llm/anthropic`** — streaming client; `emit` callback yields
      `:llm-delta` / `:llm-tool-call` / `:llm-done` / `:llm-error`. (Promoted
      spike 0.4.) Internal↔wire message + tool mapping here.
- [x] **1.3 `tools/core`** — registry atom, tool spec, split result, dispatch
      (exceptions captured, never thrown).
- [x] **1.4 `tools/builtin`** — `read` / `write` / `edit` / `bash` (bb-native).
- [x] **1.5 `agent`** — pure `(state, event) -> [state, cmds]` FSM
      (`:idle`/`:waiting-llm`/`:running-tools`). Unit-tested with a fake provider.
- [x] **1.6 `prompt`** — base system prompt + `:agents-md` hook (Phase 4).
- [x] **1.7 headless driver** (`toto.headless`) — stdout loop that interprets the
      FSM's cmds against the real API + tool registry.

**Acceptance:** ✅ `bb run` (or `bb test` with a fake provider). Live: "create
hello.txt with 'hi'" → model calls `write` → file exists (2 bytes) → model
confirms. Multi-round loop (write → bash `wc` → report) terminates correctly.
`bb test` = 5 tests / 37 assertions green.

---

## Phase 2 — TUI (charm.clj)

Goal: the agent core, driven by a charm program.

- [x] **2.1 `app`** — charm `init/update/view`; `agent` FSM wired into `update`;
      effectful cmds `stream-llm`/`poll`/`run-tool` off-loop. Streaming uses the
      Risk #1 mechanism (producer thread → side channel → self-rescheduling poll).
      **Gotcha:** the side channel must be created synchronously *in `update`*, not
      inside the cmd's `go` block — else the batched `poll` races ahead of channel
      creation and stalls the stream.
- [x] **2.2 `ui/components`** — transcript renderer, streaming assistant text,
      tool-call cards (`:display`), input editor (charm `text-input`), footer
      (model · state · spinner · tokens). Word-wrap + manual scroll windowing.
- [x] **2.3 spinner** (`:dots`) during `:waiting-llm`; live token streaming into
      the in-progress assistant block.
- [x] **2.4** keys: submit (`enter`), scroll (`page-up`/`page-down`), quit
      (`ctrl+c`), interrupt in-flight (`esc` → soft stop + history rollback so the
      next turn stays valid).

**Acceptance:** ✅ verified live in tmux — typed a task, watched tokens stream,
saw `write`/`read` tool cards, got the result; multi-round loop terminates;
esc-interrupt then re-submit works; scroll + indicator correct; `ctrl+c` exits
clean.

**Scrollback caveat:** charm 0.2.71's renderer (`render/core.clj`) caps output to
the **last `height` lines** — it does *not* push old lines into the terminal's
native scrollback (no Bubble-Tea-style `Println`). So "inline scrollback" is
really an in-app windowed view with `page-up`/`page-down`, not OS scrollback.

---

## Phase 3 — Extensions (sci)

Goal: add a tool without touching core.

- [ ] **3.1 `ext/api`** — `toto.api` surface: `register-tool` + curated host fns
      (the sandbox boundary).
- [ ] **3.2 `ext/core`** — sci context; loader for `~/.toto/extensions/*.clj` and
      `./.toto/extensions/*.clj`; tool handlers invoked through the context.
- [ ] **3.3 example extension** — e.g. a `grep` or `http-get` tool, end-to-end:
      drop the file, restart, the model can call it.

**Acceptance:** an extension file registers a tool that the agent successfully
invokes mid-session, with no core changes.

---

## Phase 4 — Sessions, AGENTS.md, CLI

- [ ] **4.1 `session`** — append-only newline-EDN log; write each event.
- [ ] **4.2 resume** — `--continue` (latest for cwd) / `--resume <id>`; replay log.
- [ ] **4.3 AGENTS.md** — hierarchical discovery → into system prompt.
- [ ] **4.4 `main`** — arg parsing, `ANTHROPIC_API_KEY`, model flag, cwd.

**Acceptance:** quit mid-task, `--continue`, history restored, conversation resumes.
Project `AGENTS.md` measurably changes behavior.

---

## Phase 5 — DX / packaging

- [ ] **5.1 `bb.edn` tasks** — `run`, `repl`, `test`.
- [ ] **5.2 docs** — README quickstart + "write your first extension".
- [ ] **5.3** (optional, later) GraalVM native-image build path.

---

## Deferred (designed-for, not v1)

Session branching · compaction · multi-provider (`pi-ai`-style) · extension slash
commands / TUI components / lifecycle hooks · permission/sandbox tightening.

## Milestone ordering rationale

Phase 0 kills the unknowns. Phase 1 makes the agent *correct* with zero UI noise
(easy to test). Phase 2 only adds presentation over a proven core. Phase 3 proves
reshapeability. Phase 4 makes it daily-usable. Nothing later forces a rewrite of
anything earlier — the provider protocol, append-only log, and `toto.api` surface
are the three seams that absorb the deferred features.
