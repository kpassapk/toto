#!/usr/bin/env bb
;; Spike 0.1 — charm hello-loop
;; Goal: init/update/view on bb: type, echo lines, quit on ctrl-c.
;; Confirms JLine raw mode + the Elm loop run under babashka.
;; Run in a real terminal (or tmux): bb spike/02_charm_hello.clj

(require '[charm.program :as prog]
         '[charm.message :as msg]
         '[clojure.string :as str])

(defn init [] {:input "" :lines []})

(defn update- [state m]
  (cond
    (not (msg/key-press? m)) [state nil]

    (msg/key-match? m "ctrl+c") [state prog/quit-cmd]

    (msg/key-match? m :enter)
    [(-> state
         (update :lines conj (:input state))
         (assoc :input ""))
     nil]

    (msg/key-match? m :backspace)
    [(update state :input #(if (seq %) (subs % 0 (dec (count %))) %)) nil]

    (msg/key-match? m :space)
    [(update state :input str " ") nil]

    ;; printable rune(s): :key is the typed string
    (string? (:key m))
    [(update state :input str (:key m)) nil]

    :else [state nil]))

(defn view [{:keys [input lines]}]
  (str "charm hello-loop (ctrl-c to quit)\n"
       "----------------------------------\n"
       (str/join "\n" (map #(str "  " %) lines))
       (when (seq lines) "\n")
       "> " input "_"))

(prog/run {:init init :update update- :view view})
(println "\nquit cleanly.")
