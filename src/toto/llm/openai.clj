(ns toto.llm.openai
  "Streaming Responses API provider; supports API keys and ChatGPT OAuth tokens."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [toto.llm.core :as llm])
  (:import [java.io BufferedReader InputStream InputStreamReader]))

(defn wire-messages [messages]
  (vec
   (mapcat
    (fn [{:keys [role text tool-calls tool-use-id content provider-items]}]
      (case role
        :user [{:role "user" :content text}]
        :assistant
        (or (seq provider-items)
            (concat
             (when (seq text) [{:role "assistant" :content text}])
             (map (fn [{:keys [id name args]}]
                    {:type "function_call" :call_id id :name name
                     :arguments (json/generate-string args)}) tool-calls)))
        :tool [{:type "function_call_output" :call_id tool-use-id :output content}]))
    messages)))

(defn wire-tools [tools]
  (mapv (fn [{:keys [name description schema]}]
          {:type "function" :name name :description description
           :parameters schema :strict false}) tools))

(defn- tool-event [item]
  (let [args (json/parse-string (:arguments item) true)]
    (when-not (and (seq (:call_id item)) (seq (:name item)) (map? args))
      (throw (ex-info "Invalid Responses function call" {})))
    {:type :llm-tool-call :id (:call_id item) :name (:name item) :args args}))

(defn- response-error [response fallback]
  (or (get-in response [:error :message])
      (get-in response [:error :code])
      (some-> (:incomplete_details response) :reason (str " (incomplete)"))
      fallback))

(defn parse-sse
  "Emit tool calls only after response.completed. Preserve complete output,
  including encrypted reasoning, for subsequent stateless tool turns."
  [^InputStream in emit]
  (with-open [rdr (BufferedReader. (InputStreamReader. in "UTF-8"))]
    (let [terminal? (atom false)
          items (atom (sorted-map))
          handle
          (fn [data]
            (when-not (= "[DONE]" data)
              (let [ev (json/parse-string data true)]
                (case (:type ev)
                  "response.output_text.delta"
                  (emit {:type :llm-delta :text (:delta ev)})
                  "response.refusal.delta"
                  (emit {:type :llm-delta :text (:delta ev)})
                  "response.output_item.done"
                  (swap! items assoc (:output_index ev) (:item ev))
                  "response.completed"
                  (let [response (:response ev)
                        output (vec (or (:output response) (vals @items)))
                        calls (mapv tool-event (filter #(= "function_call" (:type %)) output))]
                    (doseq [call calls] (emit call))
                    (reset! terminal? true)
                    (emit {:type :llm-done
                           :stop-reason (if (seq calls) "tool_calls" "stop")
                           :usage (:usage response)
                           :provider-items output}))
                  "response.failed"
                  (throw (ex-info (response-error (:response ev) "Responses request failed") {}))
                  "response.incomplete"
                  (throw (ex-info (response-error (:response ev) "Responses request incomplete") {}))
                  "error"
                  (throw (ex-info (or (:message ev) (get-in ev [:error :message])
                                     "Responses stream error") {}))
                  nil))))]
      (loop [data []]
        (when-not @terminal?
          (if-let [line (.readLine rdr)]
            (cond
              (empty? line)
              (do (when (seq data) (handle (str/join "\n" data))) (recur []))
              (str/starts-with? line "data:")
              (let [s (subs line 5)]
                (recur (conj data (if (str/starts-with? s " ") (subs s 1) s))))
              :else (recur data))
            (do
              (when (seq data) (handle (str/join "\n" data)))
              (when-not @terminal?
                (throw (ex-info "Responses stream closed before response.completed" {}))))))))))

(defn- error-message [body]
  (let [s (if (instance? InputStream body) (slurp body) (str body))]
    (try (let [parsed (json/parse-string s true)]
           (or (get-in parsed [:error :message]) (:detail parsed) s))
         (catch Exception _ s))))

(defrecord OpenAI [api-key model max-tokens send-max-tokens?]
  llm/Provider
  (stream-chat [_ req emit]
    (try
      (when (str/blank? api-key)
        (throw (ex-info "OPENAI_API_KEY / OPENAI_ACCESS_TOKEN not set." {})))
      (let [payload
            (cond-> {:model (or (:model req) model)
                     :input (wire-messages (:messages req))
                     :store false :stream true
                     :include ["reasoning.encrypted_content"]}
              (:system req) (assoc :instructions (:system req))
              (seq (:tools req)) (assoc :tools (wire-tools (:tools req)))
              send-max-tokens? (assoc :max_output_tokens (or (:max-tokens req) max-tokens)))
            resp (http/post "https://api.openai.com/v1/responses"
                            {:headers {"Authorization" (str "Bearer " api-key)
                                       "Content-Type" "application/json"
                                       "Accept" "text/event-stream"
                                       "Accept-Encoding" "identity"}
                             :body (json/generate-string payload)
                             :as :stream :throw false})]
        (with-open [body (:body resp)]
          (if (= 200 (:status resp))
            (parse-sse body emit)
            (emit {:type :llm-error :status (:status resp)
                   :message (error-message body)}))))
      (catch Exception e
        (emit {:type :llm-error :message (.getMessage e)})))))

(defn make
  "Reads OPENAI_ACCESS_TOKEN first, then OPENAI_API_KEY. Does not refresh OAuth
  tokens. Set :send-max-tokens? true to send max_output_tokens when supported."
  ([] (make {}))
  ([{:keys [api-key access-key access-token model max-tokens send-max-tokens?]}]
   (->OpenAI (or access-token api-key access-key
                 (System/getenv "OPENAI_ACCESS_TOKEN") (System/getenv "OPENAI_API_KEY"))
             (or model "gpt-5.6-luna") (or max-tokens 4096)
             (boolean send-max-tokens?))))
