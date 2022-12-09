#!/usr/bin/env bb

(ns transform-deps
  (:require [clojure.java.shell :refer [sh with-sh-dir]]
            [clojure.string :refer [trim]]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.tools.cli :as cli]
            [script-helpers :as helpers])
  (:import [java.time.format DateTimeFormatter]
           [java.time LocalDateTime]))


(defn known-library? [lib-name known-full-names]
  (let [lib-only-name (last (clojure.string/split (str lib-name) #"/"))
        known-only-names (doall (map #(last (clojure.string/split % #"/")) known-full-names))
        matches (filter (fn [known] (= lib-only-name known)) known-only-names)]
    (not (empty? matches))))

(defn replace-group [new-group lib-full-name]
  (let [lib-str (str lib-full-name)
        [current-group lib-name] (clojure.string/split lib-str #"/")
        match-current-group (re-pattern (str current-group "/"))
        group-replacement (str new-group "/")]
    (symbol (clojure.string/replace lib-str match-current-group group-replacement))))

(defn library-from-deps-path
  "Returns library name from path to library or its deps.edn

  Example: /one/two/library-name/deps.edn => library-name
           ../library-name => library-name"
  [deps-path]
  (->> (clojure.string/replace deps-path #"deps.edn$" "")
       (fs/components ,,,)
       (map str ,,,)
       (last ,,,)))

(defn relativize-known
  "Takes KNOWN_LIB (a lib in monorepo) and returns a relative path to it
  form the perspective of LIB_DEPS_PATH

  The objective is to have LIB_DEPS_PATH be able to reference KNOWN_LIB via
  relative path"
  [known-lib known-lib-paths lib-deps-path]
  (let [lib-name (last (clojure.string/split (str known-lib) #"/"))
        found-paths (filter #(clojure.string/ends-with? % (str lib-name)) known-lib-paths)
        lib-path (clojure.string/replace lib-deps-path #"/deps.edn$" "")]
    (when (> (count found-paths) 1)
      (throw (Exception. (format "Ambiguous known-lib: %s vs %s" known-lib found-paths))))
    (str (fs/relativize lib-path (first found-paths)))))

(defn transform-deps [known-lib-paths target-group target-version lib-deps-path deps-edn]
  (let [new-version {:mvn/version target-version}
        known-libraries (map library-from-deps-path known-lib-paths)
        library (library-from-deps-path lib-deps-path)
        new-deps (reduce (fn [deps [lib ver]]
                           (if (known-library? lib known-libraries)
                             (assoc deps (replace-group target-group lib) new-version)
                             (assoc deps lib ver)))
                         {} (:deps deps-edn))
        local-deps (reduce (fn [deps [lib ver]]
                           (if (known-library? lib known-libraries)
                             (assoc deps
                                    (replace-group target-group lib)
                                    {:local/root (relativize-known lib known-lib-paths lib-deps-path)})
                             deps))
                         {} (:deps deps-edn))]
    (-> deps-edn
        (assoc-in ,,, [:deps] new-deps)
        (assoc-in ,,, [:aliases :local-deps :override-deps] local-deps))))

(defn update-deps [updater-fn lib-deps-path]
  (let [current-deps-contents (helpers/read-edn lib-deps-path)
        updated-deps-contents (updater-fn lib-deps-path current-deps-contents)]
    (helpers/write-edn updated-deps-contents lib-deps-path)))

(defn -main [& args]
  (let [[root-path lib-group group-artefact-id target-version] *command-line-args*
        deps-pattern (format "{%s}/*/deps.edn" lib-group)
        deps-files (map str (fs/glob root-path deps-pattern))
        names-pattern "{server,browser,shared}/*"
        library-paths (fs/glob root-path names-pattern) ;(map str (fs/glob root-path names-pattern))
        path->group-name (fn [path]
                           (->> path
                           fs/components
                           (take-last 2 ,,,)
                           (clojure.string/join "/" ,,,)))
        updater-fn (partial transform-deps library-paths group-artefact-id target-version)
        updates-to-deps (map #(update-deps updater-fn %) deps-files)]
    (doall updates-to-deps)))

; To allow running as commandn line util but also required & used in other programs or REPL
; https://book.babashka.org/#main_file
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
