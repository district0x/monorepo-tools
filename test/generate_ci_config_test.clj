(ns generate-ci-config-test
  (:require [generate-ci-config :as gc]
            [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clojure.java.shell :refer [sh]]
            [script-helpers :as helpers]))

(def temp-prefix "d0x-libs-ci-config-test")

(defn prepare-library-with-smart-contracts [root-path name]
  (let [library-folder (str root-path "/" name)]
    (fs/create-dir library-folder)
    (fs/create-dir (str library-folder "/migrations"))
    library-folder))

(deftest generate-config-tests
  (testing "config for CI"
    (let [root-path (fs/create-temp-dir {:prefix temp-prefix})
          library-a (prepare-library-with-smart-contracts root-path "district-server-dumb-contracts")
          ci-config (gc/generate-test-run-config [library-a] [library-a] "22.10.18")
          test-section "district-server-dumb-contracts && npx truffle migrate"
          deploy-section (format "bb release 22.10.18 %s" library-a)]
      (is (clojure.string/includes? ci-config test-section))
      (is (clojure.string/includes? ci-config deploy-section)))))
