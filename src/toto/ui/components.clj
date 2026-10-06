(ns toto.ui.components
  "Charm view helpers: transcript renderer, tool-call cards, footer.
  Pure string functions over agent state + a UI-side tool-display map."
  (:require
   [charm.style.core :as style]
   [clojure.string :as str]))

;; --- styles ----------------------------------------------------------------

(def ^:private s-user  (style/style :fg 39 :bold true))
(def ^:private s-tool  (style/style :fg 244))
(def ^:private s-error (style/style :fg 203))
(def ^:private s-faint (style/style :faint true))

(defn- faint [s] (style/render s-faint s))

;; --- word wrap -------------------------------------------------------------

(defn- wrap-line [line width]
  (if (or (<= width 0) (<= (count line) width))
    [line]
    (loop [words (str/split line #" ") cur "" out []]
      (if-let [w (first words)]
        (let [candidate (if (str/blank? cur) w (str cur " " w))]
          (if (<= (count candidate) width)
            (recur (rest words) candidate out)
            (if (str/blank? cur)
              ;; single word longer than width: hard-split
              (recur (rest words) "" (conj out (subs w 0 (min (count w) width))))
              (recur words "" (conj out cur)))))
        (if (str/blank? cur) out (conj out cur))))))

(defn wrap
  "Greedy word-wrap, preserving existing newlines."
  [s width]
  (->> (str/split-lines (str s))
       (mapcat #(wrap-line % width))
       (str/join "\n")))

(defn- truncate [s n]
  (let [s (str s)]
    (if (<= (count s) n) s (str (subs s 0 (max 0 (dec n))) "…"))))

;; --- tool cards ------------------------------------------------------------

(defn- args-summary [name args]
  (truncate
   (case name
     ("read" "write" "edit") (:path args)
     "bash" (:command args)
     (pr-str args))
   60))

(defn tool-card
  "Render one tool call as a two-line card. `disp` = {:display :error?} or nil
  while still running."
  [{:keys [id name args]} tool-display]
  (let [disp (get tool-display id)
        header (str "  ▸ " name "  " (args-summary name args))
        result (cond
                 (nil? disp)       "    ⟶ …"
                 (:error? disp)    (str "    ⟶ [error] " (:display disp))
                 :else             (str "    ⟶ " (:display disp)))]
    (str (style/render s-tool header) "\n"
         (style/render (if (:error? disp) s-error s-faint) result))))

;; --- message blocks --------------------------------------------------------

(defn- user-block [text width]
  (str (style/render s-user "› ") (wrap text (- width 2))))

(defn- assistant-block [{:keys [text tool-calls]} tool-display width]
  (str/join "\n"
            (remove str/blank?
                    (concat (when-not (str/blank? text) [(wrap text width)])
                            (map #(tool-card % tool-display) tool-calls)))))

(defn transcript
  "Render the full transcript (history + in-progress assistant turn)."
  [agent tool-display width]
  (let [blocks (for [m (:history agent)]
                 (case (:role m)
                   :user      (user-block (:text m) width)
                   :assistant (assistant-block m tool-display width)
                   :tool      nil))
        pending (:pending agent)
        live (when (and pending
                        (or (seq (:tool-calls pending))
                            (not (str/blank? (:text pending)))))
               (assistant-block pending tool-display width))]
    (str/join "\n\n" (remove str/blank? (concat blocks (when live [live]))))))

;; --- footer ----------------------------------------------------------------

(defn- fmt-tokens [usage]
  (when usage
    (let [in (or (:input_tokens usage) 0)
          out (or (:output_tokens usage) 0)
          tot (+ in out)]
      (if (>= tot 1000)
        (format "%.1fk tok" (/ tot 1000.0))
        (str tot " tok")))))

(defn footer
  "model · <spinner?> state · tokens · note?"
  [agent {:keys [spinner usage note]}]
  (let [model (get-in agent [:config :model])
        st (name (:status agent))
        state-part (if (and spinner (= :waiting-llm (:status agent)))
                     (str spinner " " st)
                     st)
        parts (remove nil? [model state-part (fmt-tokens usage) note])]
    (faint (str/join " · " parts))))
