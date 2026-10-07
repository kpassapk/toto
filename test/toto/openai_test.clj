(ns toto.openai-test
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [toto.agent :as agent]
            [toto.headless :as headless]
            [toto.llm.core :as llm]
            [toto.llm.openai :as openai]
            [toto.llm.providers :as providers]
            [toto.tools.core :as tools])
  (:import [java.io ByteArrayInputStream]))

(defn input [s] (ByteArrayInputStream. (.getBytes s "UTF-8")))

(defn sse [events]
  (apply str (map #(str ": keepalive\nevent: " (:type %) "\ndata: "
                       (json/generate-string %) "\n\n") events)))

(def reasoning {:type "reasoning" :id "rs_1" :summary [] :encrypted_content "encrypted"})

(def call-a {:type "function_call" :id "fc_a" :call_id "call_a" :name "read" :arguments "{\"path\":\"x\"}" :status "completed"})

(def call-b {:type "function_call" :id "fc_b" :call_id "call_b" :name "read" :arguments "{\"path\":\"y\"}" :status "completed"})

(def output [reasoning call-a call-b])

(def tool-stream
  [{:type "response.output_text.delta" :delta "Checking."}
   {:type "response.output_item.added" :output_index 1 :item (assoc call-a :arguments "")}
   {:type "response.function_call_arguments.delta" :output_index 1 :delta "{\"path\":"}
   {:type "response.function_call_arguments.delta" :output_index 1 :delta "\"x\"}"}
   {:type "response.output_item.done" :output_index 2 :item call-b}
   {:type "response.output_item.done" :output_index 0 :item reasoning}
   {:type "response.output_item.done" :output_index 1 :item call-a}
   {:type "response.completed" :response {:status "completed" :output output
                                          :usage {:input_tokens 12 :output_tokens 8 :total_tokens 20}}}])

(def prose-stream
  [{:type "response.output_text.delta" :delta "Hello."}
   {:type "response.completed"
    :response {:status "completed"
               :output [{:type "message" :id "msg_1" :role "assistant" :status "completed"
                         :content [{:type "output_text" :text "Hello." :annotations []}]}]}}])

(deftest streaming-tools-and-usage
  (let [events (atom [])]
    (openai/parse-sse (input (sse tool-stream)) #(swap! events conj %))
    (is (= [:llm-delta :llm-tool-call :llm-tool-call :llm-done] (mapv :type @events)))
    (is (= ["call_a" "call_b"] (mapv :id (filter #(= :llm-tool-call (:type %)) @events))))
    (is (= {:path "x"} (:args (second @events))))
    (is (= 12 (get-in (last @events) [:usage :input_tokens])))
    (is (= output (:provider-items (last @events)))))
  (let [events (atom [])]
    ;; Complete items are a fallback when completed omits output.
    (openai/parse-sse (input (sse (assoc-in tool-stream [7 :response] {:status "completed"})))
                      #(swap! events conj %))
    (is (= output (:provider-items (last @events))))))

(deftest history-and-tools
  (is (= [{:role "user" :content "go"}
          {:role "assistant" :content "Checking"}
          {:type "function_call" :call_id "a" :name "read" :arguments "{\"path\":\"x\"}"}
          {:type "function_call_output" :call_id "a" :output "missing"}]
         (openai/wire-messages [(llm/user-msg "go")
                                (llm/assistant-msg "Checking" [{:id "a" :name "read" :args {:path "x"}}])
                                (llm/tool-msg "a" "missing" true)])))
  (is (= output (openai/wire-messages [{:role :assistant :text "duplicate"
                                       :tool-calls [{:id "duplicate"}] :provider-items output}])))
  (is (= [{:type "function" :name "read" :description "Read"
           :parameters {:type "object"} :strict false}]
         (openai/wire-tools [{:name "read" :description "Read" :schema {:type "object"}}]))))

(deftest request-contract
  (doseq [send-limit? [false true]]
    (let [captured (atom nil) events (atom [])]
      (with-redefs [http/post (fn [url opts]
                               (reset! captured [url opts])
                               {:status 200 :body (input (sse prose-stream))})]
        (llm/stream-chat (openai/make {:access-token "test" :model "test-model"
                                      :send-max-tokens? send-limit?})
                         {:system "Help" :messages [(llm/user-msg "hi")] :max-tokens 99
                          :tools [{:name "read" :description "Read" :schema {:type "object"}}]}
                         #(swap! events conj %)))
      (let [[url opts] @captured p (json/parse-string (:body opts) true)]
        (is (= "https://api.openai.com/v1/responses" url))
        (is (= "Bearer test" (get-in opts [:headers "Authorization"])))
        (is (= false (:store p)))
        (is (= true (:stream p)))
        (is (= "Help" (:instructions p)))
        (is (= "test-model" (:model p)))
        (is (= ["reasoning.encrypted_content"] (:include p)))
        (is (= send-limit? (contains? p :max_output_tokens)))
        (when send-limit? (is (= 99 (:max_output_tokens p))))
        (is (= "read" (get-in p [:tools 0 :name])))
        (is (not-any? #(contains? p %) [:messages :max_completion_tokens :stream_options])))
      (is (= [:llm-delta :llm-done] (mapv :type @events))))))

(deftest failed-incomplete-and-truncated-streams
  (doseq [[status body expected]
          [[401 "{\"detail\":\"Unauthorized\"}" "Unauthorized"]
           [200 (sse [{:type "response.failed" :response {:error {:message "limit"}}}]) "limit"]
           [200 (sse [{:type "response.incomplete" :response {:incomplete_details {:reason "max_output_tokens"}}}]) "max_output_tokens (incomplete)"]
           [200 (sse [{:type "error" :message "bad"}]) "bad"]
           [200 "data: [DONE]\n\n" "Responses stream closed before response.completed"]
           [200 "data: invalid\n\n" nil]
           [200 (sse [{:type "response.completed" :response {:output [(assoc call-a :arguments "invalid")]}}]) nil]]]
    (let [events (atom [])]
      (with-redefs [http/post (fn [& _] {:status status :body (input body)})]
        (llm/stream-chat (openai/make {:api-key "test"}) {} #(swap! events conj %)))
      (is (= [:llm-error] (mapv :type @events)))
      (when expected (is (= expected (:message (first @events)))))))
  ;; A completed tool item alone must never trigger execution on a failed turn.
  (let [events (atom [])]
    (with-redefs [http/post (fn [& _] {:status 200 :body (input (sse (butlast tool-stream)))})]
      (llm/stream-chat (openai/make {:api-key "test"}) {} #(swap! events conj %)))
    (is (= [:llm-delta :llm-error] (mapv :type @events)))))

(deftest full-tool-loop-preserves-reasoning
  (let [requests (atom []) scripts (atom [tool-stream prose-stream])
        state (atom (headless/init-state {:model "test-model"}))]
    (with-redefs [http/post (fn [_ opts]
                             (swap! requests conj (json/parse-string (:body opts) true))
                             (let [events (first @scripts)]
                               (swap! scripts rest)
                               {:status 200 :body (input (sse events))}))
                  tools/dispatch (fn [_ args _] {:llm (str "read " (:path args)) :error? false})]
      (with-out-str (headless/run-turn state (openai/make {:api-key "test"}) {} "read x and y")))
    (is (agent/idle? @state))
    (is (= 2 (count @requests)))
    (is (= output (get-in @state [:history 1 :provider-items])))
    (is (= output (subvec (get-in @requests [1 :input]) 1 4)))
    (is (= [{:type "function_call_output" :call_id "call_a" :output "read x"}
            {:type "function_call_output" :call_id "call_b" :output "read y"}]
           (subvec (get-in @requests [1 :input]) 4)))
    (is (= "Hello." (:text (last (:history @state)))))))

(deftest provider-selection
  (is (= "test" (:api-key (providers/make {:provider :openai :access-token "test"}))))
  (is (= "test-model" (:model (providers/make {:provider "openai" :model "test-model"}))))
  (is (thrown? Exception (providers/make {:provider :unknown}))))
