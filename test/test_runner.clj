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

(def test-namespaces
  (->> (fs/glob "./" "**/*_test.clj")
       (mapv test-file->test-ns)))

(apply require test-namespaces)

(defn -main [& args]
  (let [results (apply t/run-tests test-namespaces)]
    (System/exit (+ (:fail results) (:error results)))))

; To allow running as commandn line util but also required & used in other programs or REPL
; https://book.babashka.org/#main_file
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
