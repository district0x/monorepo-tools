(ns script-helpers
  (:require [clojure.java.shell :refer [sh with-sh-dir]]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [com.stuartsierra.dependency :as dep]))

(defn log [& args]
  (apply println args))

(defn read-edn [deps-edn-path]
  (edn/read-string (slurp deps-edn-path)))

(defn read-clj-wrapped [deps-edn-path]
  (edn/read-string (str "[" (slurp deps-edn-path) "]")))

(defn write-edn [deps-map deps-edn-path]
  (binding [*print-readably* true ; necessary to get quotes around strings in the written EDN
            *print-namespace-maps* false] ; to have {:mvn/version ...} instead of #:mvn{:version ...}
    (spit deps-edn-path (with-out-str (pp/pprint deps-map)))))

(defn ensure-edn-file [path content]
  (let [already-exists? (and (fs/exists? path) (fs/regular-file? path))]
    (if (not already-exists?) (write-edn content path))))

(defn parse-group-from-qualified-symbol [qualified-symbol]
  (-> qualified-symbol
      namespace
      (clojure.string/replace-first ,,, "'" "")))

(defn clojure-lib-info-reducer [acc elem]
  (cond
    (= 'version (nth elem 1)) (assoc acc :version (nth elem 2))
    (= 'lib (nth elem 1)) (assoc acc :group (parse-group-from-qualified-symbol (nth elem 2)))
    :else acc))

(defn guess-group-id
  "The deps.edn only knows the artefact names that the library itself depends
  on. It doesn't know under which name the library eventually gets published
  (e.g. to Clojars). We can deduce the library name from its folder name, but
  the group-id (e.g. is.mad in is.mad/the-library) is unknown (but necessary).
  For that we need heuristic and during the move to shadow-cljs ib_version.clj
  was started to be used to denote that, for the build script to use.

  So this method tries to read lib_version.clj from the library folder and
  extract group-id from there"
  [library-path]
  (let [lib-root (if (clojure.string/ends-with? library-path "deps.edn")
                   (first (clojure.string/split library-path #"/deps.edn"))
                   library-path)
        version-file-path (str lib-root "/lib_version.clj")
        build-file-path (str lib-root "/build.clj")
        default-group-id "io.github.district0x"
        extract-group (comp :group #(reduce clojure-lib-info-reducer {} %) read-clj-wrapped)]
    (cond
      (fs/exists? version-file-path) (extract-group version-file-path)
      (fs/exists? build-file-path) (extract-group build-file-path)
      :else (do
              (log "WARNING: group-id couldn't be detected, using the default: " default-group-id)
              default-group-id))))

(defn grouped-libs->libs-deps-map
  ([grouped-libs]
   (grouped-libs->libs-deps-map grouped-libs "."))
  ([grouped-libs libs-root]
   (reduce #(assoc %1 %2 (read-edn (str libs-root "/" %2 "/deps.edn"))) {} grouped-libs)))

(defn order-libs-for-release
  "Detects interdependencies between libraries, orders independent libs first so that
  those that need dependencies, can have them released by the time it's their turn

  In the output list first come the sorted dependencies, last come the libraries w/o deps (independent)"
  [library-deps]
  (let [known-libs (reduce (fn [known grouped-name]
                             (assoc known (name (symbol grouped-name)) grouped-name))
                           {}
                           (keys library-deps))
        add-dependency (fn [graph lib dependency]
                               (if (contains? known-libs (name dependency))
                                 (dep/depend graph lib (get known-libs (name dependency)))
                                 graph))
        empty-graph (dep/graph)
        graph (reduce
                (fn [graph [lib deps]]
                  (reduce (fn [graph [needed-lib _]]
                            (add-dependency graph lib needed-lib))
                          graph
                          (:deps deps)))
                empty-graph
                library-deps)
        sorted (dep/topo-sort graph)
        the-rest (clojure.set/difference (set (keys library-deps)) (set sorted))]
    (into sorted the-rest)))
