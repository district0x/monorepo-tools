(ns mark-for-release-test
  (:require [mark-for-release :as mfr]
            [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clojure.java.shell :refer [sh]]
            [script-helpers :as helpers]))

(defn =set [a b] (= (into #{} a) (into #{} b)))

(deftest mark-for-release-tests
  (testing "checks library structure (deps.edn, shadow-cljs)"
    (let [lib-names ["ui-a" "ui-b-web3" "ui-b-button" "different-lib"]
          group-name "browser"
          temp-root (fs/create-temp-dir {:prefix "monorepo-test-mark-for-release"})
          lib-paths (map #(fs/create-dirs (str temp-root "/" group-name "/" %)) lib-names)]
      (doall lib-paths)
      (is (=set ["browser/ui-a" "browser/ui-b-web3" "browser/ui-b-button" "browser/different-lib"]
             (mfr/collect-libraries "browser" temp-root)) "All under browser")
      (is (=set ["browser/ui-a"] (mfr/collect-libraries "browser/ui-a" temp-root)) "Only one ui-a")
      (is (=set ["browser/ui-b-web3" "browser/ui-b-button"] (mfr/collect-libraries "browser/ui-b" temp-root)) "Only ui-b"))))

(deftest add-to-version-tracking-tests
  (testing "adding first time to a new version"
    (let [tracking [{:created-at "2022-11-01 16:20"
                     :version "22.11.1"
                     :description "This was before"
                     :libs ["browser/some-lib"]}]
          libs-to-release ["browser/new-lib" "server/modified-lib"]
          updated-tracking (mfr/add-to-tracking libs-to-release "22.12.2" tracking)]
      (is (= 2 (count updated-tracking)))
      (is (=set [:created-at :version :description :libs] (keys (first updated-tracking))))
      (is (= (get-in updated-tracking [0 :libs]) libs-to-release)))))
