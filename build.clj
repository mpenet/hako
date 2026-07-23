(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn javac [_]
  (b/javac {:src-dirs ["src/java"]
            :class-dir class-dir
            :basis @basis
            :javac-opts ["--release" "25"
                         "-Xlint:all"
                         "-Werror"]}))

(def test-class-dir "target/test-classes")

(defn javac-test [_]
  (b/javac {:src-dirs ["test-java"]
            :class-dir test-class-dir
            :basis @basis
            :javac-opts ["--release" "25"]}))
