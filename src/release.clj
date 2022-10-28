(ns release
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]
            [clojure.tools.cli :as cli]
            [babashka.fs :as fs]
            [config :refer [get-config]]))

(def output-root "releases")

(defn make-artefact-id [group library]
  (symbol group library))

(defn clean [_]
  (b/delete {:path output-root}))

(defn subdir [root dir]
  (str root "/" dir))

(defn jar [lib-paths version artefact-id class-dir jar-path]
  (let [src-dirs (map #(subdir % "src") lib-paths)]
    (b/write-pom {:class-dir class-dir
                  :lib artefact-id
                  :version version
                  :basis (b/create-basis {:project (subdir (first lib-paths) "deps.edn")})
                  :src-dirs src-dirs})
    (b/copy-dir {:src-dirs src-dirs
                 :target-dir class-dir})
    (b/jar {:class-dir class-dir
            :jar-file jar-path})))

(defn deploy [jar-path class-dir artefact-id]
  (dd/deploy {:installer :remote
              :artifact jar-path
              :pom-file (b/pom-path {:lib artefact-id :class-dir class-dir})}))

(defn collect-lib-paths
  "When lib-path refers to a project/library (meaning it contains deps.edn
  directly), then this will be used. Otherwise the `lib-path` is considered a
  `container`, in other words bundle path (like browser, server, shared). And
  all libraries in it (according to) the previous definition will be included"
  [repo-root lib-path]
  (let [libs-path-from-root (str repo-root "/" lib-path)
        files-direct (fs/glob libs-path-from-root "*/deps.edn")
        files-bundle (fs/glob libs-path-from-root "deps.edn")
        target-paths (if (empty? files-direct) files-bundle files-direct)]
    (map #(first (clojure.string/split (str %) #"/deps.edn")) target-paths)))

(defn release [version lib-path repo-root]
  (let [[bundle-name library-name] (clojure.string/split lib-path #"\/")
        artefact-name (or library-name (str (:bundle-prefix (get-config)) bundle-name))
        class-dir (str output-root "/" artefact-name)
        jar-path (str output-root "/" artefact-name "-" version ".jar")
        artefact-group (:artefact-group (get-config))
        artefact (make-artefact-id artefact-group artefact-name)
        lib-paths (collect-lib-paths repo-root lib-path)]
    (println "Constructing JAR" artefact-name "@" version)
    (jar lib-paths version artefact class-dir jar-path)

    (println "Deploying to clojars: " jar-path)
    (deploy jar-path class-dir artefact)))

(def cli-options
  [[nil "--repo-root" "specify path to monorepo root. When not specified tries REPO_ROOT env variable, falling back to './' as default"
    :default "./"]])

(defn usage-info [options-summary]
  (->> [""
        "Usage:"
        "  export CLOJARS_USERNAME=... && export CLOJARS_TOKEN=..."
        "  release VERSION LIBPATH"
        ""
        "Options:"
        options-summary
        ""
        "Example:"
        "  bb release 22.10.20 server/district-server-web3"
        "  bb release 22.10.20 server --repo-root=../d0x-libs"]
       (clojure.string/join "\n" ,,,)))

(defn str-or-nil [value]
  (if (and
        (not (clojure.string/blank? (str value)))
        (not (= "null" (str value)))
        (not (nil? value)))
    value
    nil))

; As -main is called via `clj -X` the args get passed as a map.
;   See: https://clojure.org/guides/deps_and_cli#_using_a_main
(defn -main [arg-map & args]
  (let [parsed-opts (cli/parse-opts *command-line-args* cli-options)
        version (str (:version arg-map))
        library (str (:library arg-map))
        repo-root (or (str-or-nil (:repo-root arg-map))
                      (str-or-nil (System/getenv "REPO_ROOT"))
                      "./")]
    (println (format "Trying to execute release with: version '%s' library '%s' repo-root '%s'" version library repo-root))
    (if (or (nil? (str-or-nil version)) (nil? (str-or-nil library)))
      (println "ERROR: some arguments were missing\n" (usage-info (:summary parsed-opts)))
      (release version library repo-root))))
