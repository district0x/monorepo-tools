(ns script-helpers
  (:require [clojure.java.shell :refer [sh with-sh-dir]]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]))

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
  (let [version-path (str (first (clojure.string/split library-path #"/deps.edn")) "/lib_version.clj")
        version-exists? (fs/exists? version-path)
        default-group-id "io.github.district0x"]
    (if version-exists?
      (-> version-path
          read-clj-wrapped
          second
          (nth ,,, 2)
          namespace
          (clojure.string/replace-first ,,, "'" ""))
      (do
        (log "WARNING: group-id couldn't be detected, using the default: " default-group-id)
        default-group-id))))
