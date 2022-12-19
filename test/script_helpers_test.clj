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

(defn is< [coll val-a val-b]
  (let [coll-vector (into [] coll)]
    (is (< (.indexOf coll-vector val-a) (.indexOf coll-vector val-b))
        (format "  -> 🐛%s must come before %s" val-a val-b))))

(deftest deps-ordering-tests
  (testing "order libs based on their interdependence"
    (let [ordering ["shared/cljs-web3-next"

                    "server/district-server-web3"
                    "server/district-server-smart-contracts"

                    "browser/district-ui-web3"
                    "browser/district-ui-smart-contracts"
                    "browser/district-ui-bundle"]
          deps {"server/district-server-smart-contracts" {:deps {'is.d0x/cljs-web3-next {:mvn/version "42"}
                                                                 'is.d0x/district-server-web3 {:mvn/version "42"}}}
                "server/district-server-web3" {:deps {'is.d0x/cljs-web3-next {:mvn/version "42"}}}

                "browser/district-ui-web3" {:deps {'is.d0x/cljs-web3-next {:mvn/version "42"}}}
                "browser/district-ui-smart-contracts" {:deps {'is.d0x/cljs-web3-next {:mvn/version "42"}
                                                              'is.d0x/district-ui-web3 {:mvn/version "42"}}}
                "shared/cljs-web3-next" {:deps {}}
                "browser/district-ui-bundle" {:deps {'is.d0x/district-ui-web3 {:mvn/version "42"}
                                                     'is.d0x/district-ui-smart-contracts {:mvn/version "42"}}}}
          ordered (helpers/order-libs-for-release deps)]
      (is (= "shared/cljs-web3-next" (first ordered)))
      (is< ordered "server/district-server-web3" "server/district-server-smart-contracts")
      (is< ordered "browser/district-ui-web3" "browser/district-ui-smart-contracts")
      (is< ordered "browser/district-ui-smart-contracts" "browser/district-ui-bundle")))

  (testing "libs without deps"
    (let [deps {"shared/district-parsers" {:deps {}}}]
      (is (= '("shared/district-parsers") (helpers/order-libs-for-release deps))))

    (let [deps {"shared/district-parsers" {:deps {}}
                "shared/cljs-web3-next" {:deps {}}}]
      (is (= '("shared/cljs-web3-next" "shared/district-parsers") (helpers/order-libs-for-release deps))))))
