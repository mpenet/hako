(ns bench
  "Criterium benchmarks: meep vs Nippy.

  Run: clj -M:bench -m bench"
  (:require [criterium.core :as c]
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
        nippy-enc (nippy/fast-freeze payload)]
    (println "  meep  encoded size:" (alength meep-enc) "bytes")
    (println "  nippy encoded size:" (alength nippy-enc) "bytes")
    (println "  meep encode:")
    (c/quick-bench (meep/encode payload))
    (println "  nippy encode:")
    (c/quick-bench (nippy/fast-freeze payload))
    (println "  meep decode:")
    (c/quick-bench (meep/decode meep-enc))
    (println "  nippy decode:")
    (c/quick-bench (nippy/fast-thaw nippy-enc))))

(defn -main [& args]
  (let [selected (if (seq args)
                   (select-keys payloads (map keyword args))
                   payloads)]
    (doseq [[label payload] selected]
      (bench-one label payload)
      (println))))
