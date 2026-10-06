# Toto

A coding agent harness, in the spirit of [Pi](https://github.com/earendil-works/pi),
written in Clojure on [Babashka](https://babashka.org).

Two deliberate substitutions vs. Pi:

- **TUI** → [charm.clj](https://github.com/TimoKramer/charm.clj) (Elm-architecture,
  line-diffing renderer) instead of a custom one.
- **Extensions** → [sci](https://github.com/babashka/sci) (sandboxed Clojure)
  instead of TypeScript.

Minimal core (four tools: `read`/`write`/`edit`/`bash`, lean prompt, loop-until-done),
Anthropic-only for v1, reshaped via sci extensions rather than forks.

- Architecture: [`DESIGN.md`](DESIGN.md)
- Implementation plan: [`PLAN.md`](PLAN.md)

## Status

Phases 1–2 done. The agent core runs end-to-end against the live Anthropic API
(streaming, four builtin tools, loop-until-done), driven by a charm.clj TUI:
live token streaming, tool-call cards, spinner, scroll, and esc-to-interrupt.

```
bb tui      # the charm TUI (needs ANTHROPIC_API_KEY)
bb run      # headless stdout driver (same core, no TUI)
bb test     # FSM + tools + full loop (fake provider)
```

In the TUI: `enter` submit · `page-up`/`page-down` scroll · `esc` interrupt ·
`ctrl+c` quit.

See [`PLAN.md`](PLAN.md) for what's next (extensions, sessions, CLI).

## Requires

- Babashka
- `ANTHROPIC_API_KEY` in the environment
