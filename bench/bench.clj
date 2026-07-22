(ns bench
  "Criterium benchmarks: meep vs Nippy vs Deed.

  Run: clj -M:bench -m bench [payload-label ...]"
  (:require [criterium.core :as c]
            [deed.core :as deed]
            [s-exp.meep.core :as meep]
            [taoensso.nippy :as nippy]))

(def payloads
  {:small-map {:name "Alice" :age 30 :city "Paris"}
   :vec-of-longs (vec (range 1000))
   :vec-of-strings (vec (map #(str "item-" %) (range 100)))
   :nested-map (into {} (for [i (range 50)]
                          [(keyword (str "k" i))
                           {:idx i :val (* i i) :tag :leaf}]))
   :long-array-1k (long-array (range 1000))
   :double-array-1k (double-array (range 1000))
   :string-100 (apply str (repeat 100 "x"))
   :string-10k (apply str (repeat 10000 "x"))
   :mixed [1 :kw "str" {:a 1 :b [2 3 4]} #{:x :y :z} 3.14]})

(defn- bench-one [label payload]
  (println "===" label "===")
  (let [meep-enc (meep/encode payload)
        nippy-enc (nippy/fast-freeze payload)
        deed-enc (deed/encode-to-bytes payload)]
    (println "  size  — meep:" (alength meep-enc)
             " nippy:" (alength nippy-enc)
             " deed:" (alength deed-enc))
    (println "  meep encode:")
    (c/quick-bench (meep/encode payload))
    (println "  nippy encode:")
    (c/quick-bench (nippy/fast-freeze payload))
    (println "  deed encode:")
    (c/quick-bench (deed/encode-to-bytes payload))
    (println "  meep decode:")
    (c/quick-bench (meep/decode meep-enc))
    (println "  nippy decode:")
    (c/quick-bench (nippy/fast-thaw nippy-enc))
    (println "  deed decode:")
    (c/quick-bench (deed/decode-from deed-enc))))

(defn -main [& args]
  (let [selected (if (seq args)
                   (select-keys payloads (map keyword args))
                   payloads)]
    (doseq [[label payload] selected]
      (bench-one label payload)
      (println))))
