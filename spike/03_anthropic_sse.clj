#!/usr/bin/env bb
;; Spike 0.4 — Anthropic SSE streaming
;; Goal: prove (a) the SSE parser extracts text_delta / tool_use incrementally,
;; and (b) http-client {:as :stream} yields bytes as they arrive (no buffering).
;; Run: bb spike/03_anthropic_sse.clj
;;   - Always runs a synthetic incremental-read test (no network).
;;   - If ANTHROPIC_API_KEY is set, also streams one real completion.

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.java.io :as io])
(import '[java.io PipedInputStream PipedOutputStream BufferedReader InputStreamReader]
        '[java.time Instant])

;; ---------------------------------------------------------------------------
;; The parser under test. `in` is an InputStream; `emit` is called per event.
;; Emits {:type :llm-delta :text}, {:type :llm-tool-call :id :name :args},
;; and {:type :llm-done :stop-reason :usage}.
;; ---------------------------------------------------------------------------
(defn parse-sse [in emit]
  (let [rdr (BufferedReader. (InputStreamReader. in "UTF-8"))
        ;; per-index accumulator for tool_use input_json
        blocks (atom {})]
    (loop []
      (when-let [line (.readLine rdr)]
        (when (clojure.string/starts-with? line "data: ")
          (let [data (subs line 6)
                ev (json/parse-string data true)]
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
                (emit {:type :llm-tool-call
                       :id (:id b) :name (:name b)
                       :args (json/parse-string (str (:json b)) true)}))

              "message_delta"
              (emit {:type :llm-done
                     :stop-reason (get-in ev [:delta :stop_reason])
                     :usage (:usage ev)})

              nil)))
        (recur)))))

;; ---------------------------------------------------------------------------
;; Synthetic incremental-read test: a writer thread emits events with delays
;; through a pipe; we record arrival times to prove the reader gets each
;; event BEFORE the stream closes (i.e. not fully buffered).
;; ---------------------------------------------------------------------------
(defn synthetic-test []
  (let [out (PipedOutputStream.)
        in  (PipedInputStream. out 64)
        words ["Hello" ", " "stream" "ing" "!"]
        t0 (System/currentTimeMillis)
        arrivals (atom [])]
    (.start (Thread. (fn []
                       (doseq [w words]
                         (.write out (.getBytes (str "event: content_block_delta\n"
                                                     "data: " (json/generate-string
                                                               {:type "content_block_delta" :index 0
                                                                :delta {:type "text_delta" :text w}})
                                                     "\n\n")))
                         (.flush out)
                         (Thread/sleep 60))
                       (.write out (.getBytes (str "data: " (json/generate-string
                                                             {:type "message_delta"
                                                              :delta {:stop_reason "end_turn"}
                                                              :usage {:output_tokens 5}})
                                                   "\n\n")))
                       (.flush out)
                       (.close out))))
    (let [text (StringBuilder.)
          done (atom nil)]
      (parse-sse in (fn [ev]
                      (swap! arrivals conj (- (System/currentTimeMillis) t0))
                      (case (:type ev)
                        :llm-delta (.append text (:text ev))
                        :llm-done (reset! done ev)
                        nil)))
      (let [spread (- (last @arrivals) (first @arrivals))]
        (println "streamed text:" (str text))
        (println "arrival ms offsets:" @arrivals)
        (println "done event:" @done)
        (assert (= "Hello, streaming!" (str text)))
        (assert (= "end_turn" (:stop-reason @done)))
        ;; If buffered, all arrivals would cluster at the end. Spread proves
        ;; incremental delivery.
        (assert (> spread 150) "events did not arrive incrementally")
        (println "incremental spread:" spread "ms")
        (println "SPIKE 0.4 SSE parser + incremental read (synthetic): PASS")))))

;; ---------------------------------------------------------------------------
;; Real network test (only if key present).
;; ---------------------------------------------------------------------------
(defn real-test []
  (println "\n--- real Anthropic stream ---")
  (let [resp (http/post "https://api.anthropic.com/v1/messages"
                        {:headers {"x-api-key" (System/getenv "ANTHROPIC_API_KEY")
                                   "anthropic-version" "2023-06-01"
                                   "content-type" "application/json"}
                         :body (json/generate-string
                                {:model "claude-haiku-4-5-20251001"
                                 :max_tokens 64
                                 :stream true
                                 :messages [{:role "user"
                                             :content "Say 'streaming works' and nothing else."}]})
                         :as :stream})]
    (print "assistant: ")
    (flush)
    (parse-sse (:body resp)
               (fn [ev]
                 (when (= :llm-delta (:type ev)) (print (:text ev)) (flush))
                 (when (= :llm-done (:type ev))
                   (println "\nstop:" (:stop-reason ev) "usage:" (:usage ev)))))
    (println "SPIKE 0.4 real stream: PASS")))

(synthetic-test)
(if (System/getenv "ANTHROPIC_API_KEY")
  (real-test)
  (println "\n(ANTHROPIC_API_KEY not set — skipped real network test)"))
