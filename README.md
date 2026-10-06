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

## OpenAI Responses provider

Select OpenAI explicitly (Anthropic remains the default):

```sh
export TOTO_PROVIDER=openai
export TOTO_MODEL=gpt-5.6-luna  # use a model slug available to your account
bb run
# or: bb tui
```

Keep your working credential in `OPENAI_API_KEY`, or use `OPENAI_ACCESS_TOKEN`
for a ChatGPT OAuth access token. If both variables exist, OPENAI_ACCESS_TOKEN
wins. Explicit key/token options override environment variables.

The provider calls `https://api.openai.com/v1/responses` with `store: false` and
`stream: true`. It maps Toto tools to Responses function tools, sends tool
results as function_call_output items, and consumes Responses SSE events.
Tool calls run only after response.completed. Failed, incomplete, malformed,
and truncated streams emit llm-error instead of executing partial tool calls.

The small agent.clj change saves complete Responses output items with each
assistant history entry. This preserves encrypted reasoning and function-call
items for later stateless turns without duplicating text or tool calls.
Install agent.clj along with openai.clj; replacing only the endpoint is insufficient.

Programmatic use:

```clojure
(require '[toto.llm.openai :as openai]
         '[toto.llm.core :as llm])

(def provider (openai/make {:access-token (System/getenv "OPENAI_API_KEY")
                            :model "gpt-5.6-luna"}))
(llm/stream-chat provider
                 {:system "You are helpful."
                  :messages [(llm/user-msg "Hello!")]}
                 prn)
```

`:api-key` and `:access-key` are accepted aliases. When using the harness
programmatically, pass the provider's model to headless/init-state, since
request-level model values override provider defaults. Both entry points do
this automatically.

OAuth token refresh is not implemented. Supply a fresh access token when it
expires and restart Toto. No tokens are stored on disk by the provider.

The provider omits max_output_tokens by default for compatibility with the
ChatGPT inference flow. The harness's max-tokens setting therefore does not
limit OpenAI output by default. For an endpoint/account supporting that field,
construct the provider with `:send-max-tokens? true` to map max-tokens to
max_output_tokens. Chat Completions-specific fields are never sent.

Validation: Babashka 1.12.218, 11 tests / 91 assertions passed, covering mocked
Responses HTTP streams, complete tool continuation with encrypted reasoning,
error handling, and the existing FSM and builtin tools. Live OAuth/API calls
and the interactive TUI were not tested.
