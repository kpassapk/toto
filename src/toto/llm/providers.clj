(ns toto.llm.providers
  "Shared provider selection for headless and TUI entry points."
  (:require [toto.llm.anthropic :as anthropic]
            [toto.llm.openai :as openai]))

(defn make
  ([] (make {}))
  ([opts]
   (let [provider (keyword (or (:provider opts)
                              (System/getenv "TOTO_PROVIDER") "anthropic"))
         opts (cond-> opts
                (and (nil? (:model opts)) (System/getenv "TOTO_MODEL"))
                (assoc :model (System/getenv "TOTO_MODEL")))]
     (case provider
       :anthropic (anthropic/make opts)
       :openai (openai/make opts)
       (throw (ex-info "Unknown provider; use anthropic or openai"
                       {:provider provider}))))))
