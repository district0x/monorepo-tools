(ns release
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]
            [config :refer [get-config]]))

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
        artefact-group (:artefact-group (get-config))
        artefact (artefact-id artefact-group library-name)]
    (println "Constructing JAR" library-name "@" version)
    (jar lib-path version artefact class-dir jar-path)
    (println "Deploying to clojars: " jar-path)
    (deploy jar-path class-dir artefact)))

(defn usage-info []
  (->> [""
        "Usage:"
        "  export CLOJARS_USERNAME=... && export CLOJARS_TOKEN=..."
        "  release VERSION LIBPATH"
        ""
        "Example:"
        "  bb release 22.10.20 server/district-server-web3"]
       (clojure.string/join "\n" ,,,)))

(defn -main [arg-map & args]
  (let [version (str (:version arg-map))
        library (str (:library arg-map))
        invalid? (fn [input] (or (clojure.string/blank? input) (= "null" input)))]
    (if (or (invalid? version) (invalid? library))
      (println "ERROR: some arguments were missing\n" (usage-info))
      (release version library))))
