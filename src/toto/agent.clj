(ns toto.agent
  "The agent as a pure finite state machine: `(state, event) -> [state, cmds]`.

  Effects are *data* (`cmds`); an interpreter (headless driver in Phase 1, charm
  `update` in Phase 2) runs them and feeds the resulting events back in. This is
  Pi's loop-until-no-tool-calls, re-expressed so it never blocks.

  Status: :idle | :waiting-llm | :running-tools

  Events  (in):  :user-submit :llm-delta :llm-tool-call :llm-done
                 :tool-result :tool-error :llm-error
  Cmds    (out): {:cmd :stream-llm :request {..}}
                 {:cmd :run-tool :id .. :name .. :args ..}
                 {:cmd :done}
                 {:cmd :error :status .. :message ..}"
  (:require
   [toto.llm.core :as llm]))

(defn init
  "Fresh idle state. `config` = {:system :tools :model :max-tokens}."
  [config]
  {:status :idle
   :config config
   :history []
   :pending nil        ; assistant turn under construction {:text :tool-calls}
   :awaiting #{}        ; tool-use ids whose results are still outstanding
   :results {}})        ; tool-use-id -> {:content :error?}

(defn idle? [state] (= :idle (:status state)))

(defn- build-request [{:keys [config history]}]
  {:system (:system config)
   :messages history
   :tools (:tools config)
   :model (:model config)
   :max-tokens (:max-tokens config)})

;; --- per-event transitions -------------------------------------------------

(defn- on-user-submit [state {:keys [text]}]
  (let [state' (-> state
                   (update :history conj (llm/user-msg text))
                   (assoc :status :waiting-llm
                          :pending {:text "" :tool-calls []}))]
    [state' [{:cmd :stream-llm :request (build-request state')}]]))

(defn- on-delta [state {:keys [text]}]
  [(update-in state [:pending :text] str text) []])

(defn- on-tool-call [state ev]
  [(update-in state [:pending :tool-calls] conj
              {:id (:id ev) :name (:name ev) :args (:args ev)})
   []])

(defn- on-done [state _]
  (let [{:keys [text tool-calls]} (:pending state)
        state' (-> state
                   (update :history conj (llm/assistant-msg text tool-calls))
                   (assoc :pending nil))]
    (if (seq tool-calls)
      [(assoc state' :status :running-tools
              :awaiting (set (map :id tool-calls))
              :results {})
       (mapv (fn [tc] {:cmd :run-tool :id (:id tc) :name (:name tc) :args (:args tc)})
             tool-calls)]
      [(assoc state' :status :idle) [{:cmd :done}]])))

(defn- record-result [state id content error?]
  (-> state
      (update :awaiting disj id)
      (assoc-in [:results id] {:content content :error? error?})))

(defn- maybe-continue
  "When every outstanding tool result is in, append them (in the assistant's
  tool-call order) and re-enter :waiting-llm to continue the loop."
  [state]
  (if (seq (:awaiting state))
    [state []]
    (let [order (map :id (:tool-calls (peek (:history state))))
          results (:results state)
          tool-msgs (for [id order
                          :let [r (results id)]]
                      (llm/tool-msg id (:content r) (:error? r)))
          state' (-> state
                     (update :history into tool-msgs)
                     (assoc :status :waiting-llm :results {}))]
      [state' [{:cmd :stream-llm :request (build-request state')}]])))

(defn- on-tool-result [state {:keys [id result]}]
  (maybe-continue (record-result state id (:llm result) (:error? result))))

(defn- on-tool-error [state {:keys [id error]}]
  (maybe-continue (record-result state id error true)))

(defn- on-llm-error [state ev]
  [(assoc state :status :idle :pending nil)
   [{:cmd :error :status (:status ev) :message (:message ev)}]])

;; --- step ------------------------------------------------------------------

(defn step
  "Pure transition. Returns [state' cmds]."
  [state event]
  (case (:type event)
    :user-submit   (on-user-submit state event)
    :llm-delta     (on-delta state event)
    :llm-tool-call (on-tool-call state event)
    :llm-done      (on-done state event)
    :tool-result   (on-tool-result state event)
    :tool-error    (on-tool-error state event)
    :llm-error     (on-llm-error state event)
    [state []]))
