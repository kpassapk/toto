(ns toto.llm.anthropic
  "Anthropic streaming Messages API client (v1, Anthropic-only).
  Promotes spike 0.4: line-based SSE parser + http-client {:as :stream}.
  Maps toto's internal history <-> Anthropic wire format here, so the rest of
  the app stays provider-agnostic."
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.string :as str]
   [toto.llm.core :as llm])
  (:import
   [java.io BufferedReader InputStream InputStreamReader]
   [java.util.regex Pattern]))

(def ^:private url "https://api.anthropic.com/v1/messages")
(def ^:private version "2023-06-01")

;; --- SSE parser (from spike 0.4) -------------------------------------------

(defn parse-sse
  "Read the SSE `InputStream`, calling `emit` per logical event:
  {:type :llm-delta :text} / {:type :llm-tool-call :id :name :args} /
  {:type :llm-done :stop-reason :usage}."
  [^InputStream in emit]
  (let [rdr (BufferedReader. (InputStreamReader. in "UTF-8"))
        blocks (atom {})]                                   ; index -> tool_use accumulator
    (loop []
      (when-let [line (.readLine rdr)]
        (when (str/starts-with? line "data: ")
          (let [ev (json/parse-string (subs line 6) true)]
            (case (:type ev)
              "content_block_start"
              (when (= "tool_use" (get-in ev [:content_block :type]))
                (swap! blocks assoc (:index ev)
                       {:id (get-in ev [:content_block :id])
                        :name (get-in ev [:content_block :name])
                        :json (StringBuilder.)}))

              "content_block_delta"
              (let [d (:delta ev)]
                (case (:type d)
                  "text_delta" (emit {:type :llm-delta :text (:text d)})
                  "input_json_delta" (some-> (get @blocks (:index ev))
                                             :json
                                             (.append (:partial_json d)))
                  nil))

              "content_block_stop"
              (when-let [b (get @blocks (:index ev))]
                (let [raw (str (:json b))]
                  (emit {:type :llm-tool-call
                         :id (:id b) :name (:name b)
                         :args (if (str/blank? raw) {} (json/parse-string raw true))})))

              "message_delta"
              (emit {:type :llm-done
                     :stop-reason (get-in ev [:delta :stop_reason])
                     :usage (:usage ev)})

              nil)))
        (recur)))))

;; --- internal history <-> wire mapping -------------------------------------

(defn- assistant->wire [{:keys [text tool-calls]}]
  {:role "assistant"
   :content (vec (concat
                  (when-not (str/blank? text) [{:type "text" :text text}])
                  (for [tc tool-calls]
                    {:type "tool_use" :id (:id tc) :name (:name tc) :input (:args tc)})))})

(defn- tool->block [{:keys [tool-use-id content error?]}]
  {:type "tool_result" :tool_use_id tool-use-id :content content :is_error (boolean error?)})

(defn wire-messages
  "Map internal history to Anthropic wire messages. Consecutive :tool entries
  fold into a single user message of tool_result blocks."
  [messages]
  (loop [msgs (seq messages) acc []]
    (if-let [m (first msgs)]
      (case (:role m)
        :user      (recur (next msgs) (conj acc {:role "user"
                                                 :content [{:type "text" :text (:text m)}]}))
        :assistant (recur (next msgs) (conj acc (assistant->wire m)))
        :tool      (let [[tools more] (split-with #(= :tool (:role %)) msgs)]
                     (recur (seq more) (conj acc {:role "user"
                                                  :content (mapv tool->block tools)}))))
      acc)))

(defn wire-tools [tools]
  (vec (for [t tools]
         {:name (:name t)
          :description (:description t)
          :input_schema (:schema t)})))

;; --- provider --------------------------------------------------------------

(defn- error-message [body]
  (try
    (let [s (if (instance? InputStream body) (slurp body) (str body))]
      (or (get-in (json/parse-string s true) [:error :message]) s))
    (catch Exception _ "unknown error")))

(defrecord Anthropic [api-key model max-tokens]
  llm/Provider
  (stream-chat [_ req emit]
    (let [model      (or (:model req) model)
          max-tokens (or (:max-tokens req) max-tokens 4096)
          payload (cond-> {:model model
                           :max_tokens max-tokens
                           :stream true
                           :messages (wire-messages (:messages req))}
                    (:system req)      (assoc :system (:system req))
                    (seq (:tools req)) (assoc :tools (wire-tools (:tools req))))
          resp (http/post url
                          {:headers {"x-api-key" api-key
                                     "anthropic-version" version
                                     "content-type" "application/json"
                                     "accept-encoding" "identity"} ; plain SSE, no gzip buffering
                           :body (json/generate-string payload)
                           :as :stream
                           :throw false})]
      (if (= 200 (:status resp))
        (parse-sse (:body resp) emit)
        (emit {:type :llm-error
               :status (:status resp)
               :message (error-message (:body resp))})))))

(defn make
  "Build an Anthropic provider. Reads ANTHROPIC_API_KEY from env by default."
  ([] (make {}))
  ([{:keys [api-key model max-tokens]}]
   (->Anthropic (or api-key (System/getenv "ANTHROPIC_API_KEY"))
                (or model "claude-haiku-4-5-20251001")
                (or max-tokens 4096))))
