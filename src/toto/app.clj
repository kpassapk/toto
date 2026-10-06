(ns toto.app
  "The charm program: the agent FSM hosted in an Elm-architecture update loop.

  Effectful cmds run off the update loop. Streaming uses the Phase-0 mechanism
  (settled as Risk #1): a producer thread writes :llm-* events onto a side
  core.async channel; a self-rescheduling `poll` cmd takes ONE event per cycle
  and re-issues itself until the channel closes."
  (:require
   [charm.components.spinner :as spinner]
   [charm.components.text-input :as ti]
   [charm.message :as msg]
   [charm.program :as prog]
   [clojure.core.async :as a]
   [clojure.string :as str]
   [toto.agent :as agent]
   [toto.llm.anthropic :as anthropic]
   [toto.llm.core :as llm]
   [toto.prompt :as prompt]
   [toto.tools.builtin :as builtin]
   [toto.tools.core :as tools]
   [toto.ui.components :as comp]))

;; --- effectful cmds --------------------------------------------------------

(defn- stream-llm-cmd
  "Start the SSE stream on a worker thread, writing :llm-* events onto `ch`.
  Emits no message itself. (`ch` is created in `update` and stored in the
  runtime *before* this and `poll-cmd` run, so polling can never race ahead of
  channel creation.)"
  [runtime ch request]
  (prog/cmd
   (fn []
     (a/thread
       (try
         (llm/stream-chat (:provider runtime) request
                          (fn [ev] (a/>!! ch ev)))
         (catch Exception e
           (a/>!! ch {:type :llm-error :status nil :message (.getMessage e)}))
         (finally (a/close! ch))))
     nil)))

(defn- poll-cmd
  "Blocking-take ONE event from the current side channel; surface it as a charm
  msg. A closed channel yields {:type :poll-closed} (stop polling)."
  [runtime]
  (prog/cmd
   (fn []
     (let [ch @(:deltas runtime)]
       (or (and ch (a/<!! ch))
           {:type :poll-closed})))))

(defn- run-tool-cmd [runtime {:keys [id name args]}]
  (prog/cmd
   (fn []
     {:type :tool-result :id id
      :result (tools/dispatch name args (:ctx runtime))})))

(defn- agent-cmd->charm
  "Map a pure agent cmd to a charm cmd. For :stream-llm the side channel is
  created HERE (synchronously, inside the single-threaded update loop) and
  stored in the runtime, so the batched poll never races channel creation."
  [runtime cmd]
  (case (:cmd cmd)
    :stream-llm (let [ch (a/chan 256)]
                  (reset! (:deltas runtime) ch)
                  (prog/batch (stream-llm-cmd runtime ch (:request cmd))
                              (poll-cmd runtime)))
    :run-tool   (run-tool-cmd runtime cmd)
    :done       nil
    :error      nil))

(def ^:private stream-event? #{:llm-delta :llm-tool-call :llm-done})

;; --- update ----------------------------------------------------------------

(defn- submit [runtime state text]
  (let [[ast cmds] (agent/step (:agent state) {:type :user-submit :text text})
        state' (-> state
                   (assoc :agent ast :scroll 0 :note nil)
                   (update :input ti/reset))]
    [state' (apply prog/batch (map #(agent-cmd->charm runtime %) cmds))]))

(defn- rollback
  "Drop trailing incomplete messages (dangling :user, partial :assistant with
  unanswered tool-calls, orphan :tool) so the next request stays valid —
  Anthropic rejects consecutive user turns / unmatched tool_use."
  [history]
  (loop [h history]
    (if (empty? h)
      h
      (let [m (peek h)]
        (if (and (= :assistant (:role m)) (empty? (:tool-calls m)))
          h
          (recur (pop h)))))))

(defn- interrupt
  "Soft interrupt: stop consuming the in-flight stream and return to :idle. The
  underlying HTTP request drains in the background (channel closed)."
  [runtime state]
  (some-> @(:deltas runtime) a/close!)
  [(-> state
       (update :agent (fn [a]
                        (-> a
                            (assoc :status :idle :pending nil :awaiting #{} :results {})
                            (update :history rollback))))
       (assoc :note "interrupted"))
   nil])

(defn- handle-agent-event [runtime state msg]
  (if (and (agent/idle? (:agent state)) (stream-event? (:type msg)))
    [state nil]                                    ; stale event after interrupt
    (let [[ast cmds] (agent/step (:agent state) msg)
          state' (cond-> (assoc state :agent ast :scroll 0)
                   (= :tool-result (:type msg))
                   (assoc-in [:tool-display (:id msg)]
                             {:display (get-in msg [:result :display])
                              :error? (get-in msg [:result :error?])})
                   (= :llm-done (:type msg))
                   (assoc :usage (:usage msg))
                   (= :llm-error (:type msg))
                   (assoc :note (str "error" (when (:status msg) (str " " (:status msg)))
                                     ": " (:message msg))))
          charm-cmds (cond-> (mapv #(agent-cmd->charm runtime %) cmds)
                       (stream-event? (:type msg)) (conj (poll-cmd runtime)))]
      [state' (apply prog/batch charm-cmds)])))

(defn- handle-key [runtime state msg]
  (let [busy? (not (agent/idle? (:agent state)))]
    (cond
      (msg/key-match? msg "ctrl+c") [state prog/quit-cmd]
      (and (msg/key-match? msg :escape) busy?) (interrupt runtime state)
      (msg/key-match? msg :page-up)   [(update state :scroll + 10) nil]
      (msg/key-match? msg :page-down) [(update state :scroll #(max 0 (- % 10))) nil]
      (and (msg/key-match? msg :enter) (not busy?))
      (let [text (str/trim (ti/value (:input state)))]
        (if (str/blank? text) [state nil] (submit runtime state text)))
      :else (let [[inp c] (ti/text-input-update (:input state) msg)]
              [(assoc state :input inp) c]))))

(defn make-update [runtime]
  (fn [state msg]
    (cond
      (= :window-size (:type msg))
      [(assoc state :width (:width msg) :height (:height msg)) nil]

      (spinner/tick-msg? msg)
      (let [[sp c] (spinner/spinner-update (:spinner state) msg)]
        [(assoc state :spinner sp) c])

      (= :poll-closed (:type msg)) [state nil]
      (msg/key-press? msg)         (handle-key runtime state msg)
      :else                        (handle-agent-event runtime state msg))))

;; --- view ------------------------------------------------------------------

(def ^:private reserved-lines 3)   ; separator + input + footer

(defn view [state]
  (let [{:keys [agent tool-display width height input spinner usage note scroll]} state
        w (max 20 (min 100 (or width 80)))
        avail (max 1 (- (or height 24) reserved-lines))
        body-lines (str/split-lines (comp/transcript agent tool-display w))
        total (count body-lines)
        max-off (max 0 (- total avail))
        off (max 0 (- max-off (or scroll 0)))     ; scroll counts up from bottom
        window (->> body-lines (drop off) (take avail))
        window (concat window (repeat (- avail (count window)) ""))
        foot (comp/footer agent {:spinner (spinner/spinner-view spinner)
                                 :usage usage
                                 :note (or note (when (< off max-off) "↓ pgdn for latest"))})]
    (str (str/join "\n" window) "\n"
         (apply str (repeat w "─")) "\n"
         (ti/text-input-view input) "\n"
         foot)))

;; --- init / run ------------------------------------------------------------

(defn init-state [{:keys [model max-tokens]}]
  (builtin/register-all!)
  {:agent (agent/init {:system (prompt/system-prompt)
                       :tools (tools/all)
                       :model (or model "claude-haiku-4-5-20251001")
                       :max-tokens (or max-tokens 4096)})
   :input (ti/text-input :prompt "› " :placeholder "ask toto…")
   :spinner (spinner/spinner :dots)
   :tool-display {}
   :usage nil
   :note nil
   :scroll 0
   :width 80
   :height 24})

(defn program
  "Build a charm program map for the given runtime + initial options."
  [runtime opts]
  {:init (fn []
           (let [st (init-state opts)
                 [_ tick] (spinner/spinner-init (:spinner st))]  ; tick targets st's spinner
             [st tick]))
   :update (make-update runtime)
   :view view})

(defn run [opts]
  (let [provider (anthropic/make (select-keys opts [:model :max-tokens]))
        runtime {:provider provider
                 :ctx {:cwd (System/getProperty "user.dir")}
                 :deltas (atom nil)}]
    (prog/run (program runtime opts))))

(defn -main [& _args]
  (let [provider (anthropic/make {})]
    (if (str/blank? (:api-key provider))
      (println "ANTHROPIC_API_KEY not set.")
      (do (run {}) (println "\ntoto: bye.")))))
