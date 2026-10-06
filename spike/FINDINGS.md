# Phase 0 — Spike Findings

All spikes run under `bb` (babashka v1.12.218, charm.clj 0.2.71, sci 0.13.52).

| Spike | Status | Script |
| ----- | ------ | ------ |
| 0.1 charm hello-loop | PASS (tty) | `02_charm_hello.clj` |
| 0.2 streaming cmd | PASS (tty) — **Risk #1 settled** | `05_charm_stream.clj` |
| 0.3 sci-in-bb | PASS | `01_sci.clj` |
| 0.4 Anthropic SSE | PASS parser + incremental read; live call gated on key | `03_anthropic_sse.clj` |
| 0.5 SQLite pod | PASS | `04_sqlite.clj` |

Run the headless ones: `bb spike/01_sci.clj`, `bb spike/03_anthropic_sse.clj`,
`bb spike/04_sqlite.clj`. Run the TUI ones in a real terminal (or tmux):
`bb spike/02_charm_hello.clj`, `bb spike/05_charm_stream.clj`.

---

## Risk #1 — charm streaming mechanism (THE decision that gates FSM wiring)

**charm.program 0.2.71 has no way for one `cmd` to emit many messages.** Reading
the source (`charm/program.clj`):

- A `cmd` is `{:type :cmd :fn f}`. `execute-cmd!` calls `f` once inside a `go`
  block and puts its single return value on the internal `msg-chan`.
- `msg-chan` is **private** to `run` — no public fn injects into it.
- There is **no** built-in subscription / `every` / tick-that-repeats.
- The `timer` component streams ticks by a **self-rescheduling cmd**: its
  `tick-cmd` sleeps then returns ONE `tick-msg`; `update` re-issues `tick-cmd`
  on each tick.

**Chosen mechanism (this is what `stream-llm` will use):** producer thread +
side `core.async` channel + self-rescheduling `poll` cmd.

1. A side channel `deltas` lives in app state.
2. The `stream-llm` cmd spawns the SSE read on a worker thread that writes
   `:llm-delta` / `:llm-tool-call` / `:llm-done` items onto `deltas`, then closes it.
3. A `poll` cmd blocking-takes ONE item from `deltas` and returns it as a charm
   message.
4. `update`, on each streamed message, re-issues the `poll` cmd — until the
   channel closes (→ `:llm-done`, stop polling).

This is the timer's pattern with a channel in place of `Thread/sleep`. It is the
"core.async-channel + tick fallback" the DESIGN named — but given 0.2.71's API it
is the **primary** mechanism, not a fallback. No charm fork needed.

Proven empirically (`05_charm_stream.clj`, tmux): a 10-token producer at 250 ms
intervals rendered `received 4/10` mid-stream and `received 10/10 [stream
complete]` at the end, then exited cleanly. Many messages, one logical stream,
live re-render. ✅

### FSM wiring implication

- `:user-submit` → `:waiting-llm`, cmd = `(batch stream-llm poll)`.
- `:llm-delta` → append text, cmd = `poll` (re-issue).
- `:llm-tool-call` → stash, cmd = `poll`.
- `:llm-done` → if pending tools → `:running-tools` (batch run-tool); else `:idle`.
  Stop polling (channel closed).
- `run-tool` follows the same one-shot cmd shape (one `:tool-result`/`:tool-error`).

Per-token cost = one `go` + one `poll!` cycle through the 10 ms main loop — fine
for token streaming.

---

## Risk #2 — sci on bb: CONFIRMED

`org.babashka/sci` 0.13.52 loads under bb. Built a context exposing only
`toto.api/register-tool` + curated `clojure.string`, `eval-string*`'d an
"extension" that registered a tool; the tool appeared in the host registry and
its handler (a sci fn) was callable. Sandbox boundary holds: raw
`(slurp "/etc/passwd")` from inside the context throws (`:blocked`) — host fns
are reachable only through the injected `:namespaces`. This validates the
`ext/api.clj` surface = sandbox boundary design.

## Risk #3 — SSE incremental read: CONFIRMED (synthetic); live call gated on key

The line-based SSE parser extracts `text_delta` (prose) and accumulates
`input_json_delta` into tool_use args, emitting `:llm-delta` / `:llm-tool-call` /
`:llm-done`. Incremental delivery proven via a `PipedInputStream` producer with
60 ms gaps: arrival offsets `[2 65 127 192 255 320]` ms — events surfaced as
written, not buffered to end-of-stream (spread 318 ms).

**Not yet exercised against the live API** — `ANTHROPIC_API_KEY` was unset in the
spike shell. To confirm the real `http-client {:as :stream}` path end-to-end:

```
ANTHROPIC_API_KEY=sk-... bb spike/03_anthropic_sse.clj
```

The script streams one `claude-haiku-4-5` completion and prints tokens as they
arrive. Run before Phase 1.2 to close the loop on bb's streaming HTTP read.

## Risk #4 — JLine raw mode on bb: CONFIRMED

`charm.program/run` enters raw mode, reads keys, and restores the terminal under
bb. `02_charm_hello.clj` echoed typed runes, handled `:enter` (commit line),
`:backspace`, and quit on `ctrl-c` (both the INT signal handler and the raw
keypress path), restoring a clean prompt on exit.

---

## Notes for Phase 1+

- Main namespace is **`charm.program`** (not `charm.core`); messages in
  `charm.message`; key matching via `msg/key-match?` (`"ctrl+c"`, `:enter`,
  `:backspace`, etc.). Special keys arrive as keywords; printable runes as the
  string in `:key`.
- `prog/run` blocks; `prog/run-async` returns `{:quit! :result}` (daemon thread +
  promise) — useful for headless driving / tests.
- SQLite: `org.babashka/go-sqlite3` 0.2.5 pod, `sqlite/execute!` + `sqlite/query`.
  (DESIGN currently specifies append-only EDN session log; pod is available if we
  later want SQLite-backed sessions.)
