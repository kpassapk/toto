(ns toto.agent-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [toto.agent :as agent]
   [toto.headless :as headless]
   [toto.llm.core :as llm]
   [toto.tools.builtin :as builtin]
   [toto.tools.core :as tools]))

;; A provider that replays scripted event sequences, one per stream-chat call.
(defrecord FakeProvider [scripts]
  llm/Provider
  (stream-chat [_ _req emit]
    (let [script (first @scripts)]
      (swap! scripts rest)
      (doseq [ev script] (emit ev)))))

(deftest fsm-prose-only
  (testing "a plain prose turn returns to :idle"
    (let [s0 (agent/init {:model "m"})
          [s1 c1] (agent/step s0 {:type :user-submit :text "hi"})]
      (is (= :waiting-llm (:status s1)))
      (is (= :stream-llm (:cmd (first c1))))
      (let [[s2 _] (agent/step s1 {:type :llm-delta :text "hel"})
            [s3 _] (agent/step s2 {:type :llm-delta :text "lo"})
            [s4 c4] (agent/step s3 {:type :llm-done :stop-reason "end_turn"})]
        (is (= :idle (:status s4)))
        (is (= [{:cmd :done}] c4))
        (is (= [:user :assistant] (map :role (:history s4))))
        (is (= "hello" (:text (last (:history s4)))))))))

(deftest fsm-multi-tool-terminates
  (testing "two parallel tool calls; loop continues only after both results"
    (let [s0 (agent/init {:model "m"})
          [s1 _] (agent/step s0 {:type :user-submit :text "go"})
          [s2 _] (agent/step s1 {:type :llm-tool-call :id "a" :name "read" :args {}})
          [s3 _] (agent/step s2 {:type :llm-tool-call :id "b" :name "read" :args {}})
          [s4 c4] (agent/step s3 {:type :llm-done :stop-reason "tool_use"})]
      (is (= :running-tools (:status s4)))
      (is (= #{"a" "b"} (:awaiting s4)))
      (is (= 2 (count c4)))
      (is (= #{:run-tool} (set (map :cmd c4))))
      (let [[s5 c5] (agent/step s4 {:type :tool-result :id "a" :result {:llm "ra"}})]
        (is (= :running-tools (:status s5)))
        (is (empty? c5))
        (let [[s6 c6] (agent/step s5 {:type :tool-result :id "b" :result {:llm "rb"}})]
          (is (= :waiting-llm (:status s6)))
          (is (= :stream-llm (:cmd (first c6))))
          (is (= ["a" "b"]
                 (map :tool-use-id (filter #(= :tool (:role %)) (:history s6)))))
          (is (= "ra" (:content (first (filter #(= :tool (:role %)) (:history s6)))))))))))

(deftest fsm-llm-error
  (let [s0 (agent/init {:model "m"})
        [s1 _] (agent/step s0 {:type :user-submit :text "x"})
        [s2 c2] (agent/step s1 {:type :llm-error :status 400 :message "boom"})]
    (is (= :idle (:status s2)))
    (is (= :error (:cmd (first c2))))
    (is (= "boom" (:message (first c2))))))

(deftest headless-write-loop
  (testing "end-to-end loop with a fake provider actually writes the file"
    (let [dir (str (fs/create-temp-dir))
          ctx {:cwd dir}
          provider (->FakeProvider
                    (atom [[{:type :llm-delta :text "Writing. "}
                            {:type :llm-tool-call :id "t1" :name "write"
                             :args {:path "hello.txt" :content "hi"}}
                            {:type :llm-done :stop-reason "tool_use"}]
                           [{:type :llm-delta :text "Done."}
                            {:type :llm-done :stop-reason "end_turn"}]]))
          state-atom (atom (headless/init-state {}))]
      (with-out-str
        (headless/run-turn state-atom provider ctx "create hello.txt with hi"))
      (let [st @state-atom]
        (is (agent/idle? st))
        (is (= "hi" (slurp (str dir "/hello.txt"))))
        (is (= [:user :assistant :tool :assistant] (map :role (:history st))))
        (is (= "Done." (:text (last (:history st)))))))))

(deftest builtin-tools
  (builtin/register-all!)
  (let [dir (str (fs/create-temp-dir)) ctx {:cwd dir}]
    (testing "write + read"
      (tools/dispatch "write" {:path "a.txt" :content "l1\nl2\nl3"} ctx)
      (is (= "l1\nl2\nl3" (slurp (str dir "/a.txt"))))
      (let [r (tools/dispatch "read" {:path "a.txt"} ctx)]
        (is (false? (:error? r)))
        (is (str/includes? (:llm r) "1\tl1"))
        (is (str/includes? (:llm r) "3\tl3"))))
    (testing "read offset/limit"
      (let [r (tools/dispatch "read" {:path "a.txt" :offset 2 :limit 1} ctx)]
        (is (= "     2\tl2" (:llm r)))))
    (testing "edit unique vs non-unique"
      (is (false? (:error? (tools/dispatch "edit" {:path "a.txt" :old_string "l2" :new_string "L2"} ctx))))
      (is (str/includes? (slurp (str dir "/a.txt")) "L2"))
      (tools/dispatch "write" {:path "b.txt" :content "x x"} ctx)
      (is (:error? (tools/dispatch "edit" {:path "b.txt" :old_string "x" :new_string "y"} ctx)))
      (is (:error? (tools/dispatch "edit" {:path "b.txt" :old_string "zzz" :new_string "y"} ctx))))
    (testing "bash exit codes"
      (let [r (tools/dispatch "bash" {:command "echo hello"} ctx)]
        (is (false? (:error? r)))
        (is (str/includes? (:llm r) "hello")))
      (is (:error? (tools/dispatch "bash" {:command "exit 3"} ctx))))
    (testing "missing file + unknown tool"
      (is (:error? (tools/dispatch "read" {:path "nope.txt"} ctx)))
      (is (:error? (tools/dispatch "frobnicate" {} ctx))))))
