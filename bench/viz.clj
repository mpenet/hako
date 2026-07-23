(ns viz
  "Parse the output of `clj -M:bench -m bench` (captured to a file)
  into an EDN structure. Also renders Mermaid `xychart-beta` blocks
  with hako-relative multipliers, ready to paste into README.md.

  Consumed programmatically by `bench/plot.clj`.

  Run:
    clj -M:bench -m bench > /tmp/hako-bench.txt
    clj -M:bench -m viz    /tmp/hako-bench.txt"
  (:require [clojure.string :as str]))

(def contenders
  "Order the criterium blocks appear per payload."
  [:hako :nippy :nippy-fast :deed :transit])

(def order-for-charts
  "Payloads in the order they should appear on charts — large-to-small
  so hako's biggest wins land on the left."
  [:long-array-1k :double-array-1k :vec-of-longs :nested-map
   :vec-of-strings :mixed :small-map :string-10k :string-100])

(defn- parse-time-ns
  "Parse a line like `Execution time mean : 869.09 ns` → ns as double."
  [line]
  (when-let [[_ num unit] (re-find
                           #"Execution time mean\s*:\s*([\d.]+)\s+(ns|µs|ms)"
                           line)]
    (let [n (Double/parseDouble num)]
      (case unit
        "ns" n
        "µs" (* n 1000.0)
        "ms" (* n 1000000.0)))))

(defn- parse-block
  "Advance through `lines` collecting encode + decode ns per contender.
  Returns [{:encode {peer ns} :decode {peer ns}} remaining-lines]."
  [lines]
  (loop [ls lines
         phase :encode
         idx 0
         acc {:encode {} :decode {}}]
    (cond
      (empty? ls) [acc ls]
      (= (count (:encode acc)) (count contenders))
      (if (= (count (:decode acc)) (count contenders))
        [acc ls]
        (recur ls :decode 0 acc))
      :else
      (let [line (first ls)
            t (parse-time-ns line)]
        (if t
          (let [peer (nth contenders idx)]
            (recur (rest ls) phase (inc idx)
                   (update acc phase assoc peer t)))
          (recur (rest ls) phase idx acc))))))

(defn parse-bench-output
  "Return {payload {:sizes {peer bytes} :encode {peer ns} :decode {peer ns}}}."
  [path]
  (let [lines (str/split-lines (slurp path))]
    (loop [ls lines out {}]
      (if (empty? ls)
        out
        (let [line (first ls)]
          (if-let [[_ label] (re-find #"^===\s+:(\S+)\s+===" line)]
            (let [payload (keyword label)
                  size-line (some #(when (re-find #"size\s+—" %) %)
                                  (take 3 (rest ls)))
                  sizes (into {}
                              (keep (fn [c]
                                      (when-let [[_ n]
                                                 (re-find
                                                  (re-pattern
                                                   (str (name c) ":\\s+(\\d+)"))
                                                  (or size-line ""))]
                                        [c (Long/parseLong n)]))
                                    contenders))
                  [times ls'] (parse-block (rest ls))]
              (recur ls' (assoc out payload (assoc times :sizes sizes))))
            (recur (rest ls) out)))))))

(defn- format-num [x]
  (format "%.1f" (double x)))

(defn mermaid-block
  [{:keys [title metric baseline data]}]
  (let [payloads (filter #(get-in data [% metric baseline])
                         (filter #(contains? data %) order-for-charts))
        xs (mapv name payloads)
        bars (mapv (fn [p]
                     (let [hako (get-in data [p metric :hako])
                           peer (get-in data [p metric baseline])]
                       (format-num (/ peer hako))))
                   payloads)
        max-bar (reduce max 0 (map #(Double/parseDouble %) bars))
        y-max (max 4 (int (Math/ceil (+ max-bar 0.5))))]
    (str "```mermaid\n"
         "xychart-beta\n"
         "    title \"" title "\"\n"
         "    x-axis [" (str/join ", " (map pr-str xs)) "]\n"
         "    y-axis \"× hako\" 0 --> " y-max "\n"
         "    bar [" (str/join ", " bars) "]\n"
         "```\n")))

(defn -main [& args]
  (let [path (or (first args) "/tmp/hako-bench.txt")
        data (parse-bench-output path)]
    (println "## Encode speedup vs nippy-fast\n")
    (println (mermaid-block
              {:title "Encode: nippy-fast ÷ hako"
               :metric :encode :baseline :nippy-fast :data data}))
    (println "## Decode speedup vs nippy-fast\n")
    (println (mermaid-block
              {:title "Decode: nippy-fast ÷ hako"
               :metric :decode :baseline :nippy-fast :data data}))
    (println "## Encode speedup vs nippy (default freeze)\n")
    (println (mermaid-block
              {:title "Encode: nippy ÷ hako"
               :metric :encode :baseline :nippy :data data}))
    (println "## Decode speedup vs nippy (default thaw)\n")
    (println (mermaid-block
              {:title "Decode: nippy ÷ hako"
               :metric :decode :baseline :nippy :data data}))))
