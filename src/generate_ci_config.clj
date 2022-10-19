#!/usr/bin/env bb

(ns generate-ci-config
  (:require [clojure.java.shell :refer [sh with-sh-dir]]
            [clojure.string :refer [trim]]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [script-helpers :refer [log read-edn write-edn] :as helpers]))

(defn uses-smart-contracts? [library-path]
  (let [indicator-files ["migrations" "truffle.js" "truffle-config.js"]]
    (->> indicator-files
         (map #(str library-path "/" %) ,,,)
         (map fs/exists? ,,,)
         (reduce #(or %1 %2) ,,,))))

(defn server-library? [library]
  (clojure.string/starts-with? library "server/"))

(defn browser-library? [library]
  (clojure.string/starts-with? library "browser/"))

(defn shared-library? [library]
  (clojure.string/starts-with? library "shared/"))

(defn test-section [library]
  [(format "      - run:")
   (format "          name: Install node modules in %s" library)
   (format "          command: cd %s && yarn install" library)
   (when (uses-smart-contracts? library)
     (vector
       (format "      - run:")
       (format "          name: Deploy %s smart contracts" library)
       (format "          command: \"cd %s && npx truffle migrate --network ganache --reset\"" library)))
   (when (or (server-library? library) (shared-library? library))
     (vector
       (format "      - run:")
       (format "          name: Compile Node tests for %s" library)
       (format "          command: cd %s && npx shadow-cljs compile test-node" library)
       (format "      - run:")
       (format "          name: ⭐Run Node tests for %s" library)
       (format "          command: cd %s && node out/node-tests.js" library)))
   (when (or (browser-library? library) (shared-library? library))
     (vector
       (format "      - run:")
       (format "          name: Compile Browser tests for %s" library)
       (format "          command: cd %s && npx shadow-cljs compile test-ci" library)
       (format "      - run:")
       (format "          name: ⭐Run Browser (karma) tests for %s" library)
       (format "          command: CHROME_BIN=`which chromium-browser` cd %s && npx karma start karma.conf.js --single-run" library)))])

(defn deploy-section [library version]
  [(format "      - run:")
   (format "          name: Build JAR & publishing to Clojars %s @ %s" library version)
   (format "          command: bb release %s %s" version library)])

(defn generate-test-run-config [libraries version]
  (->> ["version: 2.1"
        "jobs:"
        "  test:"
        "    working_directory: ~/ci"
        "    docker:"
        "      # Primary container image where all steps run."
        "      - image: 487920318758.dkr.ecr.us-west-2.amazonaws.com/cljs-web3-ci:latest"
        "        aws_auth:"
        "            aws_access_key_id: $AWS_ACCESS_KEY_ID"
        "            aws_secret_access_key: $AWS_SECRET_ACCESS_KEY"
        "      # Secondary container image on common network."
        "      - image: trufflesuite/ganache-cli:latest"
        "        command: [-d, -m district0x, -p 8549, -l 8000000]"
        "    steps:"
        "      - checkout"
        "      - run:"
        "          name: Install node modules"
        "          command: yarn install"
        "      - save_cache:"
        "          name: Save npm package cache"
        "          key: npm-packages-{{ checksum \"yarn.lock\" }}"
        "          paths:"
        "            - ./node_modules/"
        (map test-section libraries)
       "  deploy:"
       "    working_directory: ~/ci"
       "    docker:"
       "      - image: 487920318758.dkr.ecr.us-west-2.amazonaws.com/cljs-web3-ci:latest"
       "        aws_auth:"
       "            aws_access_key_id: $AWS_ACCESS_KEY_ID"
       "            aws_secret_access_key: $AWS_SECRET_ACCESS_KEY"
       "    steps:"
       "      - checkout"
       "      - run:"
       "          name: Get the contents of monorepo-tools git submodule"
       "          command: git submodule update --init"
       "      - run:"
       "          name: Make bb available globally"
       "          command: sudo ln -fs `pwd`/bin/bb /usr/local/bin/bb"
       (map #(deploy-section % version) libraries)
       "workflows:"
       "  version: 2"
       "  test_and_deploy:"
       "    jobs:"
       "      - test"
       "      - deploy:"
       "          requires:"
       "            - test"
       "          filters:"
       "            branches:"
       "              only: master"
       ""]
       (flatten ,,,)
       (clojure.string/join "\n")))

(defn -main [& args]
  (let [continuation-filename (first args)
        latest-version (first (helpers/read-edn "./version-tracking.edn")) ; ./ is the current folder from where the script executed
        libraries (:libs latest-version)
        generated-config (generate-test-run-config libraries (:version latest-version))]
    (log "Generating dynamic config for CircleCI continuation:")
    (log generated-config)
    (spit continuation-filename generated-config)))

; To allow running as commandn line util but also required & used in other programs or REPL
; https://book.babashka.org/#main_file
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
