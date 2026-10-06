(ns toto.llm.core
  "Provider protocol + toto's internal message model.

  History is a vector of internal messages, provider-agnostic. Each provider
  namespace maps these to/from its own wire format.

  Internal message shapes:

    {:role :user   :text \"...\"}
    {:role :assistant :text \"...\" :tool-calls [{:id .. :name .. :args {..}}]}
    {:role :tool   :tool-use-id \"..\" :content \"..\" :error? false}

  OpenAI Responses assistant messages may also carry `:provider-items`, the
  complete output items needed to replay reasoning and tool calls with store=false.
  Providers that do not use these items ignore them.

  `:text` may be empty when an assistant turn is only tool calls; `:tool-calls`
  may be empty/absent for a plain prose turn.")

(defprotocol Provider
  (stream-chat [_ req emit]
    "Stream one assistant turn.

    req  = {:system String?, :messages [internal-msg], :tools [tool-spec]?,
            :model String, :max-tokens Long?}
    emit = side-effecting fn called per event with one of:
            {:type :llm-delta     :text String}
            {:type :llm-tool-call :id String :name String :args map}
            {:type :llm-done      :stop-reason String :usage map :provider-items vector?}
            {:type :llm-error     :status Long? :message String}"))

;; --- internal message constructors -----------------------------------------

(defn user-msg [text]
  {:role :user :text text})

(defn assistant-msg [text tool-calls]
  {:role :assistant :text (or text "") :tool-calls (vec tool-calls)})

(defn tool-msg [tool-use-id content error?]
  {:role :tool :tool-use-id tool-use-id :content content :error? (boolean error?)})
