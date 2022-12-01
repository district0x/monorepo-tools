#!/usr/bin/env bb
;; Inspired from https://book.babashka.org/#_running_tests

(ns test-runner
  (:require [clojure.test :as t]
           [clojure.string :as string]
           [babashka.classpath :as cp]
           [babashka.fs :as fs]))

(cp/add-classpath "bin:bin_test")

(defn test-file->test-ns
  [file]
  (as-> file $
        (fs/components $)
        (drop 1 $)
        (mapv str $)
        (string/join "." $)
        (string/replace $ #"_" "-")
        (string/replace $ #".clj$" "")
        (symbol $)))

(defn get-test-namespaces [pattern]
  (let [pattern-or-all (or pattern "")
        underscored (clojure.string/replace pattern-or-all #"-" "_")
        namespace->file (str "**/" underscored "*_test.clj")]
    (->> (fs/glob "./" namespace->file)
         (mapv test-file->test-ns))))

(defn -main [& args]
  (doall (map #(require %) (get-test-namespaces (first (first args)))))
  (let [results (apply t/run-tests (map symbol (get-test-namespaces (first (first args)))))]
    (System/exit (+ (:fail results) (:error results)))))

; To allow running as commandn line util but also required & used in other programs or REPL
; https://book.babashka.org/#main_file
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
