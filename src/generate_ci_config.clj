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
         (reduce #(and %1 %2) ,,,))))

(defn test-section [library]
  [(format "      - run:")
   (format "          name: Install node modules")
   (format "          command: yarn install")
   (format "      - save_cache:")
   (format "          name: Save npm package cache")
   (format "          key: npm-packages-{{ checksum \"yarn.lock\" }}")
   (format "          paths:")
   (format "            - ./node_modules/")
   (format "      - run:")
   (format "          name: Deploy %s smart contracts" library)
   (format "          command: \"cd %s && npx truffle migrate --network ganache --reset\"" library)
   (format "      - run:")
   (format "          name: Compile Node tests for %s" library)
   (format "          command: cd %s clj -Ashadow:district-server-smart-contracts compile test-node" library)
   (format "      - run:")
   (format "          name: Run Node tests for %s" library)
   (format "          command: cd %s && node out/node-tests.js" library)])

(defn deploy-section [library]
  [(format "      - run:")
   (format "          name: Build JAR")
   (format "          command: cd %s && clojure -T:build jar" library)
   (format "      - run:")
   (format "          name: Release to clojars")
   (format "          command: cd %s && clojure -T:build deploy" library)])

(defn generate-test-run-config [libraries]
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
        "      - restore_cache:"
        "          name: Restore npm package cache"
        "          keys:"
        "            - npm-packages-{{ checksum \"yarn.lock\" }}"
        "      - run:"
        "          name: Install node modules"
        "          command: yarn install"
        "      - save_cache:"
        "          name: Save npm package cache"
        "          key: npm-packages-{{ checksum \"yarn.lock\" }}"
        "          paths:"
        "            - ./node_modules/"
        (map test-section libraries)
       ; "      - run:"
       ; "          name: Deploy library's smart contracts"
       ; "          command: \"cd server/district-server-smart-contracts && npx truffle migrate --network ganache --reset\""
       ; "      - run:"
       ; "          name: Compile Node tests"
       ; "          command: clj -Ashadow:district-server-smart-contracts compile test-node"
       ; "      - run:"
       ; "          name: Run Node tests"
       ; "          command: node out/node-tests.js"
       "  deploy:"
       "    working_directory: ~/ci"
       "    docker:"
       "      - image: 487920318758.dkr.ecr.us-west-2.amazonaws.com/cljs-web3-ci:latest"
       "        aws_auth:"
       "            aws_access_key_id: $AWS_ACCESS_KEY_ID"
       "            aws_secret_access_key: $AWS_SECRET_ACCESS_KEY"
       "    steps:"
       "      - checkout"
       (map deploy-section libraries)
       ; "      - run:"
       ; "          name: Build JAR"
       ; "          command: clojure -T:build jar"
       ; "      - run:"
       ; "          name: Release to clojars"
       ; "          command: clojure -T:build deploy"
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
        generated-config (generate-test-run-config libraries)]
    (log "Generating dynamic config for CircleCI continuation:")
    (log generated-config)
    (spit continuation-filename generated-config)))

; To allow running as commandn line util but also required & used in other programs or REPL
; https://book.babashka.org/#main_file
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
