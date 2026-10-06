(ns toto.tools.core
  "Tool registry, spec, split-result, and dispatch.

  A tool is data + a handler:

    {:name        \"read\"
     :description \"Read a file...\"
     :schema      { ..JSON Schema for the Anthropic tool def.. }
     :handler     (fn [args ctx] {:llm \"..for the model..\"
                                  :display \"..for the TUI..\"})}

  Result is split (Pi pattern): `:llm` goes back to the model, `:display` is
  what the transcript shows. A handler may signal failure with `:error? true`.")

(defonce ^{:doc "name -> tool spec"} registry (atom {}))

(defn register!
  "Add (or replace) a tool in the registry. Returns the tool."
  [{:keys [name handler] :as tool}]
  (assert (string? name) "tool needs a string :name")
  (assert (ifn? handler) "tool needs an :handler fn")
  (swap! registry assoc name tool)
  tool)

(defn all
  "All registered tool specs."
  []
  (vals @registry))

(defn get-tool [name]
  (@registry name))

(defn dispatch
  "Run tool `name` with `args` and `ctx`. Always returns a result map with at
  least `:llm` (String for the model) and `:error?`. Exceptions in the handler
  are captured, never thrown."
  [name args ctx]
  (if-let [t (get-tool name)]
    (try
      (let [r ((:handler t) args ctx)]
        (-> r
            (update :llm #(or % (:display r) ""))
            (update :error? boolean)))
      (catch Exception e
        (let [msg (str "Error running " name ": " (.getMessage e))]
          {:llm msg :display msg :error? true})))
    (let [msg (str "Unknown tool: " name)]
      {:llm msg :display msg :error? true})))
