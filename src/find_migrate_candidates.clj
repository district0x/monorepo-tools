#!/usr/bin/env bb

(ns find-migrate-candidates
  (:require [clojure.java.shell :refer [sh with-sh-dir]]
            [clojure.string :refer [trim]]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.tools.cli :as cli]
            [script-helpers :refer [log read-edn write-edn] :as helpers]))

(defn readable? [p] (and (fs/exists? p) (fs/readable? p)))

(defn migration-missing-checks [path]
  {"shadow-cljs.edn" (not (readable? (str path "/shadow-cljs.edn")))
   "deps.edn" (not (readable? (str path "/deps.edn")))
   "(lib_version.clj or build.clj)" (not (or (readable? (str path "/lib_version.clj"))
                                             (readable? (str path "/build.clj"))))})

; Usage bb find-candidates server ~/code/district0x/district-server-*
(defn -main [& args]
  (let [group (first args)
        paths (rest args)
        inspection-results (map #(hash-map :path % :checks (migration-missing-checks %)) paths)
        printable-results (map-indexed #(merge %2
                                               {:nr (inc %1)
                                                "OK?" (if (every? false? (vals (:checks %2))) "✓" "")
                                                "missing" (keys (filter val (:checks %2)))})
                                       inspection-results)
        migratable-paths (map :path (filter #(every? false? (vals (:checks %))) inspection-results))]
    (println "Inspected paths:")
    (clojure.pprint/print-table [:nr :path "OK?" "missing"] printable-results)

    (println "\nCommands to run (for migrating to current folder `.`): \n")
    (run! println (map #(format "bb migrate %s . %s --no-create-branch" % group (last (fs/components %)) group) migratable-paths))
    (println "")
    ))

; To allow running as commandn line util but also required & used in other programs or REPL
; https://book.babashka.org/#main_file
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
