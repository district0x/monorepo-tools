(ns script-helpers-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clojure.java.shell :refer [sh]]
            [script-helpers :as helpers]))

(defn fixture-path [file-name]
  (str "test/fixtures/" file-name))

(deftest helper-function-tests
  (testing "guess-group-id from lib_version.clj"
    (let [lib-version-file-path (fixture-path "lib-version-file")
          lib-version-build-path (fixture-path "lib-version-build")]
      (is (= "district0x" (helpers/guess-group-id lib-version-file-path)))
      (is (= "io.bithub.district0x" (helpers/guess-group-id lib-version-build-path)))
      (is (= "io.github.district0x" (helpers/guess-group-id "/non-existent"))))))
