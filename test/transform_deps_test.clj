(ns transform-deps-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clojure.java.shell :refer [sh]]
            [transform-deps :as td]))

(deftest helper-function-tests
  (testing "known-library?"
    (let [known-libraries ["io.github.district0x/cljs-web3-next"
                           "district0x/district-server-db"
                           "district0x/district-server-web3"
                           "district0x/district-server-web3-events"]]
      (is (= true (td/known-library? "district0x/district-server-db" known-libraries)))
      (is (= true (td/known-library? "district-server-web3" known-libraries)))
      (is (= false (td/known-library? "district0x/unknown" known-libraries))))

   (testing "library-from-deps-path"
     (is (= "cljs-web3-next" (td/library-from-deps-path "/some/folder/cljs-web3-next/deps.edn")))
     (is (= "cljs-web3-next" (td/library-from-deps-path "../../shared/cljs-web3-next/deps.edn")))
     (is (= "cljs-web3-next" (td/library-from-deps-path "../../shared/cljs-web3-next"))))

   (testing "relativize-known"
     (is (= "../district-server-web3" (td/relativize-known
                                        'district0x/district-server-web3
                                        ["./server/district-server-web3"
                                         "./shared/cljs-web3-next"]
                                        "./server/district-server-web3-events/deps.edn")))))

  (testing "replace-group"
    (is (= 'is.d0x/some-library (td/replace-group "is.d0x" 'district0x/some-library)))))

(deftest transformation-tests
  (testing "replace group-id in :deps"
    (let [known-libraries ["../d0x-libs/shared/cljs-web3-next"
                           "../d0x-libs/server/district-server-db"
                           "../d0x-libs/server/district-server-web3"
                           "../d0x-libs/server/district-server-web3-events"]
          deps-edn {:deps {'io.github.district0x/cljs-web3-next {:mvn/version "0.0.1"}
                           'district0x/district-server-db {:mvn/version "0.0.2"}
                           'com.example/non-d0x-lib {:mvn/version "0.0.3"}}
                    :aliases {:there-before {:some-key "Some value"}}}
          expected {:deps {'is.d0x/cljs-web3-next {:mvn/version "22.12.5"}
                           'is.d0x/district-server-db {:mvn/version "22.12.5"}
                           'com.example/non-d0x-lib {:mvn/version "0.0.3"}}
                    :aliases {:there-before {:some-key "Some value"}
                              :local-deps
                              {:override-deps
                               {'is.d0x/cljs-web3-next {:local/root "../../shared/cljs-web3-next"}
                                'is.d0x/district-server-db {:local/root "../district-server-db"}}}}}]
      (is (= expected
             (td/transform-deps known-libraries "is.d0x" "22.12.5" "../d0x-libs/server/district-server-web3/deps.edn" deps-edn))))))
