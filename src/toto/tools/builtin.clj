(ns toto.tools.builtin
  "The four builtin tools: read / write / edit / bash. All babashka-native.
  Each registers itself into `toto.tools.core/registry` via `register-all!`."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [toto.tools.core :as tools]))

(defn- resolve-path
  "Resolve `path` against ctx :cwd (absolute paths pass through)."
  [ctx path]
  (let [cwd (or (:cwd ctx) (System/getProperty "user.dir"))]
    (str (fs/absolutize (fs/path cwd path)))))

;; --- read ------------------------------------------------------------------

(defn read-handler [{:keys [path offset limit]} ctx]
  (let [f (resolve-path ctx path)]
    (if-not (fs/exists? f)
      {:llm (str "File not found: " path) :error? true}
      (let [lines   (str/split-lines (slurp f))
            total   (count lines)
            start   (max 0 (dec (or offset 1)))
            chosen  (cond->> (drop start lines)
                      limit (take limit))
            numbered (map-indexed
                      (fn [i ln] (format "%6d\t%s" (+ start i 1) ln))
                      chosen)]
        {:llm (str/join "\n" numbered)
         :display (str path " — " (count numbered) "/" total " lines")
         :error? false}))))

(def read-tool
  {:name "read"
   :description "Read a file from disk. Returns line-numbered content. Use offset (1-based line) and limit to page through large files."
   :schema {:type "object"
            :properties {:path   {:type "string" :description "Path to the file, relative to the working directory or absolute."}
                         :offset {:type "integer" :description "1-based line to start at (optional)."}
                         :limit  {:type "integer" :description "Max number of lines to read (optional)."}}
            :required ["path"]}
   :handler read-handler})

;; --- write -----------------------------------------------------------------

(defn write-handler [{:keys [path content]} ctx]
  (let [f (resolve-path ctx path)]
    (when-let [parent (fs/parent f)]
      (fs/create-dirs parent))
    (spit f (or content ""))
    {:llm (str "Wrote " (count (or content "")) " bytes to " path)
     :display (str "wrote " path)
     :error? false}))

(def write-tool
  {:name "write"
   :description "Write (overwrite) a file with the given content. Creates parent directories as needed."
   :schema {:type "object"
            :properties {:path    {:type "string" :description "Path to the file to write."}
                         :content {:type "string" :description "Full file content."}}
            :required ["path" "content"]}
   :handler write-handler})

;; --- edit ------------------------------------------------------------------

(defn edit-handler [{:keys [path old_string new_string]} ctx]
  (let [f (resolve-path ctx path)]
    (cond
      (not (fs/exists? f))
      {:llm (str "File not found: " path) :error? true}

      (str/blank? old_string)
      {:llm "old_string must not be empty" :error? true}

      :else
      (let [content (slurp f)
            n (count (re-seq (re-pattern (java.util.regex.Pattern/quote old_string)) content))]
        (cond
          (zero? n)
          {:llm (str "old_string not found in " path) :error? true}

          (> n 1)
          {:llm (str old_string " is not unique in " path " (" n " matches). Add more context.") :error? true}

          :else
          (do (spit f (str/replace-first content old_string new_string))
              {:llm (str "Edited " path)
               :display (str "edited " path)
               :error? false}))))))

(def edit-tool
  {:name "edit"
   :description "Replace an exact, unique occurrence of old_string with new_string in a file. Errors if old_string is missing or matches more than once."
   :schema {:type "object"
            :properties {:path       {:type "string" :description "Path to the file to edit."}
                         :old_string {:type "string" :description "Exact text to replace. Must be unique in the file."}
                         :new_string {:type "string" :description "Replacement text."}}
            :required ["path" "old_string" "new_string"]}
   :handler edit-handler})

;; --- bash ------------------------------------------------------------------

(defn bash-handler [{:keys [command timeout]} ctx]
  (let [cwd (or (:cwd ctx) (System/getProperty "user.dir"))
        timeout-ms (or timeout 120000)
        proc (p/process {:dir cwd :out :string :err :string} "bash" "-c" command)
        res (deref (future @proc) timeout-ms ::timeout)]
    (if (= res ::timeout)
      (do (p/destroy-tree proc)
          {:llm (str "Command timed out after " timeout-ms "ms") :error? true})
      (let [{:keys [out err exit]} res
            body (str/join "\n"
                           (cond-> []
                             (seq out) (conj out)
                             (seq err) (conj (str "[stderr]\n" err))
                             true      (conj (str "[exit " exit "]"))))]
        {:llm body
         :display (str "$ " command)
         :error? (not (zero? exit))}))))

(def bash-tool
  {:name "bash"
   :description "Run a shell command with bash in the working directory. Returns stdout, stderr, and exit code. Default timeout 120s."
   :schema {:type "object"
            :properties {:command {:type "string" :description "The shell command to run."}
                         :timeout {:type "integer" :description "Timeout in milliseconds (optional, default 120000)."}}
            :required ["command"]}
   :handler bash-handler})

;; --- registration ----------------------------------------------------------

(defn register-all! []
  (run! tools/register! [read-tool write-tool edit-tool bash-tool]))
