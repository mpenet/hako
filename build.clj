(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.s-exp/hako)
(def version (format "1.0.0-alpha%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def test-class-dir "target/test-classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
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

(defn javac-test [_]
  (b/javac {:src-dirs ["test-java"]
            :class-dir test-class-dir
            :basis @basis
            :javac-opts ["--release" "25"]}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src/clj"]
                :pom-data [[:description "Schemaless, low-alloc binary serialization for Clojure (JDK 25 FFM)."]
                           [:url "https://github.com/s-exp/hako"]
                           [:licenses
                            [:license
                             [:name "Mozilla Public License 2.0"]
                             [:url "https://www.mozilla.org/en-US/MPL/2.0/"]]]
                           [:scm
                            [:url "https://github.com/s-exp/hako"]
                            [:connection "scm:git:git://github.com/s-exp/hako.git"]
                            [:developerConnection "scm:git:ssh://git@github.com/s-exp/hako.git"]]]})
  (b/copy-dir {:src-dirs ["src/clj" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file
          :manifest {"Enable-Native-Access" "ALL-UNNAMED"}}))

(defn install [_]
  (jar nil)
  (b/install {:basis @basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))
