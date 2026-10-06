(ns toto.headless
  "Phase 1 headless driver: a stdout loop that interprets the agent FSM's cmds
  against the real provider + tool registry. No charm yet.

  This is the effect interpreter the DESIGN names: it runs `:stream-llm`,
  `:run-tool`, `:done`, `:error` cmds and feeds resulting events back into the
  pure FSM, looping until the turn returns to :idle."
  (:require
   [clojure.string :as str]
   [toto.agent :as agent]
   [toto.llm.anthropic :as anthropic]
   [toto.llm.core :as llm]
   [toto.prompt :as prompt]
   [toto.tools.builtin :as builtin]
   [toto.tools.core :as tools]))

(declare exec-cmd)

(defn- run-stream [state-atom provider ctx {:keys [request]}]
  ;; Update state per streamed event; defer any cmds (only :llm-done makes them)
  ;; until the stream completes, so tool execution happens off the SSE read.
  (let [deferred (atom [])]
    (llm/stream-chat provider request
                     (fn [ev]
                       (when (= :llm-delta (:type ev))
                         (print (:text ev)) (flush))
                       (let [[st cmds] (agent/step @state-atom ev)]
                         (reset! state-atom st)
                         (swap! deferred into cmds))))
    (doseq [c @deferred] (exec-cmd state-atom provider ctx c))))

(defn- run-tool [state-atom provider ctx {:keys [id name args]}]
  (println (str "\n  ▸ " name " " (pr-str args)))
  (let [result (tools/dispatch name args ctx)]
    (println (str "    ⟶ " (when (:error? result) "[error] ")
                  (or (:display result) (first (str/split-lines (str (:llm result)))))))
    (let [[st cmds] (agent/step @state-atom {:type :tool-result :id id :result result})]
      (reset! state-atom st)
      (doseq [c cmds] (exec-cmd state-atom provider ctx c)))))

(defn- exec-cmd [state-atom provider ctx cmd]
  (case (:cmd cmd)
    :stream-llm (run-stream state-atom provider ctx cmd)
    :run-tool   (run-tool state-atom provider ctx cmd)
    :done       (println)
    :error      (println (str "\n[llm-error " (:status cmd) "] " (:message cmd)))))

(defn run-turn
  "Drive one user turn end-to-end. Returns the resulting state."
  [state-atom provider ctx text]
  (let [[st cmds] (agent/step @state-atom {:type :user-submit :text text})]
    (reset! state-atom st)
    (doseq [c cmds] (exec-cmd state-atom provider ctx c))
    @state-atom))

(defn init-state [{:keys [model max-tokens]}]
  (builtin/register-all!)
  (agent/init {:system (prompt/system-prompt)
               :tools (tools/all)
               :model (or model "claude-haiku-4-5-20251001")
               :max-tokens (or max-tokens 4096)}))

(defn -main [& _args]
  (let [provider (anthropic/make)
        ctx {:cwd (System/getProperty "user.dir")}
        state-atom (atom (init-state {}))]
    (if (str/blank? (:api-key provider))
      (println "ANTHROPIC_API_KEY not set.")
      (do
        (println "toto headless —" (get-in @state-atom [:config :model])
                 "· type a task, ctrl-d to quit.")
        (loop []
          (print "\n› ") (flush)
          (when-let [line (read-line)]
            (when-not (str/blank? line)
              (run-turn state-atom provider ctx line))
            (recur)))))))
