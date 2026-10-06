#!/usr/bin/env bb
;; Spike 0.2 — streaming cmd (settles Risk #1)
;; Question: can one logical "stream" emit MANY messages over time into the
;; charm loop? charm 0.2.71 has no public way to push to its msg-chan and no
;; self-running subscription — a :cmd returns exactly ONE message. So the
;; mechanism is: a producer thread writes deltas onto a side core.async channel,
;; and a self-rescheduling `poll` cmd takes ONE item per cycle and re-issues
;; itself until the stream ends. This is exactly the timer component's pattern,
;; applied to a channel instead of Thread/sleep. This is how stream-llm will
;; feed :llm-delta / :llm-tool-call / :llm-done into the FSM.
;; Run in a real terminal (or tmux): bb spike/05_charm_stream.clj

(require '[charm.program :as prog]
         '[charm.message :as msg]
         '[clojure.core.async :as a])

(def N 10)
(def deltas (a/chan 32))   ; the side channel the producer writes to

;; Producer: simulates an SSE stream — N delayed "tokens", then close.
(defn start-producer! []
  (a/thread
    (dotimes [i N]
      (Thread/sleep 250)
      (a/>!! deltas (str "tok" (inc i))))
    (a/close! deltas)))

;; The self-rescheduling poll cmd: blocking-take ONE item, return ONE message.
(def poll-cmd
  (prog/cmd
   (fn []
     (if-let [item (a/<!! deltas)]
       {:type :stream-item :item item}
       {:type :stream-done}))))

(defn init []
  [{:items [] :done false}
   (prog/batch (prog/cmd (fn [] (start-producer!) nil)) poll-cmd)])

(defn update- [state m]
  (case (:type m)
    :stream-item [(update state :items conj (:item m)) poll-cmd]   ; re-issue
    :stream-done [(assoc state :done true) prog/quit-cmd]
    (if (msg/key-match? m "ctrl+c")
      [state prog/quit-cmd]
      [state nil])))

(defn view [{:keys [items done]}]
  (str "streaming cmd spike — Risk #1\n"
       "-----------------------------\n"
       "received " (count items) "/" N " live: "
       (clojure.string/join " " items) "\n"
       (if done "[stream complete]" "[streaming…]")))

(prog/run {:init init :update update- :view view})
(println "\nstream spike done.")
