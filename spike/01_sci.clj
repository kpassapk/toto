#!/usr/bin/env bb
;; Spike 0.3 — sci-in-bb
;; Goal: add org.babashka/sci; build a context; eval-string an extension that
;; calls a stubbed `register-tool`; confirm the tool appears in the registry.
;; Run: bb spike/01_sci.clj

(require '[sci.core :as sci])

(def registry (atom {}))

(defn register-tool
  "Host fn injected into the sandbox as toto.api/register-tool."
  [tool]
  (swap! registry assoc (:name tool) tool)
  (:name tool))

;; Build the sandboxed context. `toto.api` is the only host surface exposed.
(def ctx
  (sci/init
   {:namespaces {'toto.api {'register-tool register-tool}
                 'clojure.string {'upper-case clojure.string/upper-case}}}))

;; An "extension" file — Clojure source a user would drop in ~/.toto/extensions/.
(def extension-src
  "(require '[toto.api :as api]
            '[clojure.string :as str])
   (api/register-tool
     {:name \"shout\"
      :description \"Uppercase the input.\"
      :handler (fn [{:keys [text]} _ctx]
                 {:llm (str/upper-case text)})})")

;; Eval the extension inside the sandbox.
(def registered-name (sci/eval-string* ctx extension-src))

(println "registered:" registered-name)
(println "registry keys:" (keys @registry))

;; Confirm the handler (a sci fn) is callable through the context.
(def tool (get @registry "shout"))
(def result (sci/eval-string* ctx
                              "(require '[toto.api :as api])"))
(def handler-out ((:handler tool) {:text "hello"} {}))
(println "handler output:" handler-out)

;; Confirm sandbox boundary: an extension may NOT reach arbitrary host fns.
(def escaped?
  (try
    (sci/eval-string* ctx "(slurp \"/etc/passwd\")")
    :LEAKED
    (catch Exception _ :blocked)))
(println "raw slurp from sandbox:" escaped?)

(assert (= "shout" registered-name))
(assert (contains? @registry "shout"))
(assert (= {:llm "HELLO"} handler-out))
(assert (= :blocked escaped?))
(println "\nSPIKE 0.3 sci-in-bb: PASS")
