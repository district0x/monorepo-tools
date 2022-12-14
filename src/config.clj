(ns config
  (:require [clojure.edn :as edn])) ; Keep the dependencies minimal to make it usable in JVM & Babashka

(def ^{:dynamic true} *default-config-path* "monorepo-config.edn")
(def ^{:dynamic true} *default-config* {:tools-root "."
                                        :artefact-group "is.mad"
                                        :bundle-prefix "district-"
                                        :groups ["browser" "server" "shared"]})

(defn get-config
  "Returns config from 1) provided path
                       2) *default-config-path* path
                       3) the default values from *default-config*"
  ([] (get-config *default-config-path*))
  ([path]
   (try
     (edn/read-string (slurp path))
     (catch Exception e (do
                          (println (format "ERROR: couldn't read from '%s', returning default config" path))
                          *default-config*)))))
