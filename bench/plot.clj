(ns plot
  "Render grouped-bar PNGs comparing hako to peer libraries.

  Consumes the output of `clj -M:bench -m bench` (captured to a file)
  and emits PNGs to docs/bench/*.png. Pure JDK — java.awt + ImageIO,
  no extra dependencies.

  Run:
    clj -M:bench -m bench > /tmp/hako-bench.txt
    clj -M:bench -m plot   /tmp/hako-bench.txt"
  (:require [clojure.java.io :as io]
            [viz])
  (:import (java.awt BasicStroke Color Font Graphics2D RenderingHints)
           (java.awt.image BufferedImage)
           (java.io File)
           (javax.imageio ImageIO)))

(def contenders viz/contenders)
(def order-for-charts viz/order-for-charts)

(def colors
  {:hako       (Color. 44 127 184)
   :nippy      (Color. 217 95 14)
   :nippy-fast (Color. 254 196 79)
   :deed       (Color. 49 163 84)
   :transit    (Color. 117 107 181)})

(def chart-w 1400)
(def chart-h 720)
(def margin-l 110)
(def margin-r 210)
(def margin-t 80)
(def margin-b 140)

(defn- plot-area []
  {:x margin-l
   :y margin-t
   :w (- chart-w margin-l margin-r)
   :h (- chart-h margin-t margin-b)})

(defn- log10 [v]
  (Math/log10 (max 1e-9 (double v))))

(defn- lerp-y-log [v y-min y-max area]
  (let [{:keys [y h]} area
        norm (/ (- (log10 v) (log10 y-min))
                (- (log10 y-max) (log10 y-min)))]
    (- (+ y h) (* norm h))))

(defn- lerp-y-lin [v y-min y-max area]
  (let [{:keys [y h]} area
        norm (/ (- (double v) y-min) (- y-max y-min))]
    (- (+ y h) (* norm h))))

(defn- format-time [v]
  (cond
    (>= v 1e6) (format "%.0f ms" (/ v 1e6))
    (>= v 1e3) (format "%.0f µs" (/ v 1e3))
    :else      (format "%.0f ns" v)))

(defn- format-size [v]
  (cond
    (>= v 1e6) (format "%.1f MB" (/ v 1e6))
    (>= v 1e3) (format "%.1f KB" (/ v 1e3))
    :else      (format "%d B" (int v))))

(defn- draw-legend
  [^Graphics2D g area]
  (let [legend-x (+ (:x area) (:w area) 30)]
    (.setFont g (Font. Font/SANS_SERIF Font/BOLD 13))
    (doseq [[i peer] (map-indexed vector contenders)]
      (let [ly (+ (:y area) (* i 28))]
        (.setColor g (colors peer))
        (.fillRect g legend-x ly 16 16)
        (.setColor g (Color. 40 40 40))
        (.drawString g (name peer) (int (+ legend-x 24)) (int (+ ly 13)))))))

(defn- draw-x-axis-labels
  [^Graphics2D g area payloads group-w]
  (.setColor g (Color. 40 40 40))
  (.setFont g (Font. Font/SANS_SERIF Font/PLAIN 12))
  (doseq [[i payload] (map-indexed vector payloads)]
    (let [gx (+ (:x area) (* i group-w) (/ group-w 2))
          label (name payload)
          cx (double gx)
          cy (double (+ (:y area) (:h area) 20))]
      (doto g
        (.rotate (- 0.5) cx cy)
        (.drawString label (int (- cx 40)) (int cy))
        (.rotate 0.5 cx cy)))))

(defn- draw-title
  [^Graphics2D g title subtitle]
  (.setColor g (Color. 20 20 20))
  (.setFont g (Font. Font/SANS_SERIF Font/BOLD 20))
  (.drawString g title 20 34)
  (.setColor g (Color. 100 100 100))
  (.setFont g (Font. Font/SANS_SERIF Font/PLAIN 13))
  (.drawString g subtitle 20 55))

(defn- draw-grouped-bars
  [^Graphics2D g data metric payloads y-min y-max y-fn value-fn]
  (let [area (plot-area)
        n (count payloads)
        group-w (double (/ (:w area) (max 1 n)))
        bar-slots (double (count contenders))
        bar-w (* group-w 0.85 (/ 1.0 bar-slots))
        left-inset (* group-w 0.075)]
    ;; Gridlines
    (.setStroke g (BasicStroke. 1.0))
    (.setColor g (Color. 230 230 230))
    (if (= y-fn lerp-y-log)
      (doseq [pow (range (int (Math/floor (log10 y-min)))
                         (inc (int (Math/ceil (log10 y-max)))))]
        (let [v (Math/pow 10 pow)
              y (y-fn v y-min y-max area)]
          (when (<= (:y area) y (+ (:y area) (:h area)))
            (.drawLine g (int (:x area)) (int y)
                       (int (+ (:x area) (:w area))) (int y)))))
      (let [tick (/ (- y-max y-min) 6.0)]
        (doseq [k (range 0 7)]
          (let [v (+ y-min (* k tick))
                y (y-fn v y-min y-max area)]
            (.drawLine g (int (:x area)) (int y)
                       (int (+ (:x area) (:w area))) (int y))))))
    ;; Y-axis labels
    (.setColor g (Color. 60 60 60))
    (.setFont g (Font. Font/SANS_SERIF Font/PLAIN 11))
    (if (= y-fn lerp-y-log)
      (doseq [pow (range (int (Math/floor (log10 y-min)))
                         (inc (int (Math/ceil (log10 y-max)))))]
        (let [v (Math/pow 10 pow)
              y (y-fn v y-min y-max area)]
          (when (<= (:y area) y (+ (:y area) (:h area)))
            (.drawString g (value-fn v) 10 (int (+ y 4))))))
      (let [tick (/ (- y-max y-min) 6.0)]
        (doseq [k (range 0 7)]
          (let [v (+ y-min (* k tick))
                y (y-fn v y-min y-max area)]
            (.drawString g (value-fn v) 10 (int (+ y 4)))))))
    ;; Axes
    (.setColor g (Color. 80 80 80))
    (.setStroke g (BasicStroke. 1.5))
    (.drawLine g (:x area) (:y area) (:x area) (+ (:y area) (:h area)))
    (.drawLine g (:x area) (+ (:y area) (:h area))
               (+ (:x area) (:w area)) (+ (:y area) (:h area)))
    ;; Bars
    (doseq [[i payload] (map-indexed vector payloads)
            [j peer] (map-indexed vector contenders)]
      (when-let [v (get-in data [payload metric peer])]
        (let [gx (+ (:x area) (* i group-w))
              bx (+ gx left-inset (* j bar-w))
              by (y-fn v y-min y-max area)
              bh (max 1.0 (- (+ (:y area) (:h area)) by))]
          (.setColor g (colors peer))
          (.fillRect g (int bx) (int by) (int (dec bar-w)) (int bh))
          (.setColor g (Color. 20 20 20 90))
          (.drawRect g (int bx) (int by) (int (dec bar-w)) (int bh)))))
    (draw-x-axis-labels g area payloads group-w)
    (draw-legend g area)))

(defn- new-canvas ^BufferedImage []
  (let [img (BufferedImage. chart-w chart-h BufferedImage/TYPE_INT_ARGB)
        g (.createGraphics img)]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING
                       RenderingHints/VALUE_ANTIALIAS_ON)
    (.setRenderingHint g RenderingHints/KEY_TEXT_ANTIALIASING
                       RenderingHints/VALUE_TEXT_ANTIALIAS_ON)
    (.setColor g Color/WHITE)
    (.fillRect g 0 0 chart-w chart-h)
    [img g]))

(defn- ensure-dir [path]
  (let [f (File. ^String path)]
    (when-let [parent (.getParentFile f)]
      (.mkdirs parent))
    path))

(defn render-time-png
  [data metric title path]
  (let [[img g] (new-canvas)
        payloads (filterv #(get-in data [% metric :hako]) order-for-charts)
        all-vals (for [p payloads c contenders
                       :let [v (get-in data [p metric c])]
                       :when v] v)
        low  (apply min all-vals)
        high (apply max all-vals)
        y-min (Math/pow 10 (Math/floor (log10 low)))
        y-max (Math/pow 10 (Math/ceil  (log10 (* 1.05 high))))]
    (draw-title g title "log scale · lower is better")
    (draw-grouped-bars g data metric payloads y-min y-max
                       lerp-y-log format-time)
    (.dispose ^Graphics2D g)
    (ImageIO/write img "png" (File. ^String (ensure-dir path)))
    path))

(defn render-size-png
  [data title path]
  (let [[img g] (new-canvas)
        payloads (filterv #(get-in data [% :sizes :hako]) order-for-charts)
        all-vals (for [p payloads c contenders
                       :let [v (get-in data [p :sizes c])]
                       :when v] v)
        y-min 0
        y-max (* 1.1 (apply max all-vals))]
    (draw-title g title "linear scale · smaller is better")
    (draw-grouped-bars g data :sizes payloads y-min y-max
                       lerp-y-lin format-size)
    (.dispose ^Graphics2D g)
    (ImageIO/write img "png" (File. ^String (ensure-dir path)))
    path))

(defn -main [& args]
  (let [in-path (or (first args) "/tmp/hako-bench.txt")
        out-dir (or (second args) "docs/bench")
        data (viz/parse-bench-output in-path)]
    (println "Rendering from" in-path "→" out-dir)
    (println (render-time-png data :encode
                              "Encode time — hako vs peers"
                              (str out-dir "/encode.png")))
    (println (render-time-png data :decode
                              "Decode time — hako vs peers"
                              (str out-dir "/decode.png")))
    (println (render-size-png data
                              "Encoded size — hako vs peers"
                              (str out-dir "/size.png")))
    (println "Done.")))
