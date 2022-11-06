#!/usr/bin/env bb

(ns generate-ci-config
  (:require [clojure.java.shell :refer [sh with-sh-dir]]
            [clojure.string :refer [trim]]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [config :refer [get-config]]
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

(defn generate-test-run-config [libraries-to-test libraries-to-release version]
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
        (map test-section libraries-to-test)
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
       (map #(deploy-section % version) libraries-to-release)
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

(defn last-commit-timestamp [path repo-root]
  (Long/parseLong (:out (sh "git" "log" "--max-count=1" "--pretty=format:%ct" path :dir repo-root))))

(defn parent-dir [path]
  (->> path
       fs/components
       reverse
       rest
       reverse
       (clojure.string/join "/" ,,,)))

(defn collect-libraries [groups repo-root]
  (->> (reduce #(into %1 (fs/glob (str repo-root "/" %2) "*/deps.edn")) [] groups)
       (map parent-dir ,,,)))

(defn relativize-path
  "Takes relative path and removes the root part from beginning.
  Example:
    (relativize-path ../wut ../wut/server/some-lib) ; => server/some-lib"
  [root path]
  (clojure.string/replace-first path (str root "/") ""))

(defn -main
  "Produces config for CircleCI that causes tests to be run and/or library to be released
  Relying on CircleCI's dynamic config feature: https://circleci.com/docs/dynamic-config/

  1. Testing:
    - if `version-tracking.edn` is committed later than library => test based on version tracking
    - otherwise test all the libraries that have newer commits than the `version-tracking.edn`
  2. Releasing:
    - if `version-tracking.edn` is committed later than library => release based on it
    - otherwise don't release because developer didn't explicitly state it
  "
  [& args]
  (let [continuation-filename (first args)
        repo-root (if (clojure.string/blank? (second args)) "./" (second args))
        version-tracking-path "version-tracking.edn"
        latest-version (first (helpers/read-edn (str repo-root "/" version-tracking-path)))
        version-tracking-timestamp (last-commit-timestamp version-tracking-path repo-root)
        library-source-paths (collect-libraries (:groups (get-config)) repo-root)
        libraries-source-timestamps (map #(vector (last-commit-timestamp % repo-root) %) (map #(relativize-path repo-root %) library-source-paths))
        libraries-from-tracking (:libs latest-version)
        newest-source-change-timestamp (reduce max (or [-1] (map first libraries-source-timestamps)))
        libraries-to-test (if (>= version-tracking-timestamp newest-source-change-timestamp)
                               libraries-from-tracking
                               (map second libraries-source-timestamps))
        libraries-to-release (if (>= version-tracking-timestamp newest-source-change-timestamp)
                               libraries-from-tracking
                               []) ; Don't release anything if libraries had new commits but weren't explicitly marked for release
        generated-config (generate-test-run-config libraries-to-test libraries-to-release (:version latest-version))]
    (log "Generating dynamic config for CircleCI continuation:")
    (log generated-config)
    (spit continuation-filename generated-config)))

; To allow running as commandn line util but also required & used in other programs or REPL
; https://book.babashka.org/#main_file
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
