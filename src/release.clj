(ns release
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def output-root "releases")
(def artefact-group "is.mad")

(defn artefact-id [group library]
  (symbol group library))

(defn clean [_]
  (b/delete {:path output-root}))

(defn subdir [root dir]
  (str root "/" dir))

(defn jar [lib-path version artefact-id class-dir jar-path]
  (b/write-pom {:class-dir class-dir
                :lib artefact-id
                :version version
                :basis (b/create-basis {:project (subdir lib-path "deps.edn")})
                :src-dirs [(subdir lib-path "src")]})
  (b/copy-dir {:src-dirs [(subdir lib-path "src") (subdir lib-path "resources")]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-path}))

(defn deploy [jar-path class-dir artefact-id]
  (dd/deploy {:installer :remote
              :artifact jar-path
              :pom-file (b/pom-path {:lib artefact-id :class-dir class-dir})}))


(defn release [version lib-path]
  (let [[_ library-name] (clojure.string/split lib-path #"\/")
        class-dir (str output-root "/" library-name)
        jar-path (str output-root "/" library-name "-" version ".jar")
        artefact (artefact-id artefact-group library-name)]
    (println "Constructing JAR" library-name "@" version)
    (jar lib-path version artefact class-dir jar-path)
    (println "Deploying to clojars: " jar-path)
    (deploy jar-path class-dir artefact)))

(defn -main [arg-map & args]
  (let [version (str (:version arg-map))
        library (str (:library arg-map))]
    (release version library)))

; To allow running as commandn line util but also required & used in other programs or REPL
; https://book.babashka.org/#main_file
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
