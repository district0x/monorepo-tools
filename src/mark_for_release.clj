#!/usr/bin/env bb

(ns mark-for-release
  (:require [clojure.java.shell :refer [sh with-sh-dir]]
            [clojure.string :refer [trim]]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.tools.cli :as cli]
            [script-helpers :refer [log read-edn write-edn] :as helpers])
  (:import [java.time.format DateTimeFormatter]
           [java.time LocalDateTime]))

(defn calendar-version-today []
  (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yy.M.d")))

(defn iso-time-now []
  (.format (LocalDateTime/now) DateTimeFormatter/ISO_DATE_TIME))

(defn collect-libraries [specifier root]
  (let [spec-comp (map str (fs/components specifier))
        bare-group? (< (count (fs/components specifier)) 2)
        glob-pattern (str "glob:" specifier (if bare-group? "/*" "*"))
        matches (babashka.fs/match (str root) glob-pattern {:recursive true :max-depth 2})]
    (map #(clojure.string/replace (str %) (str root "/") "") matches)))

(defn add-to-tracking [selected-libs version-today current-tracking]
  (let [new-tracking-entry {:created-at "..." :version version-today :description "..." :libs []}
        latest-release (or (first current-tracking) {})
        release-version-matches? (= version-today (:version latest-release))
        tracking-entry (if release-version-matches? latest-release new-tracking-entry)
        other-entries (if release-version-matches? (or (rest current-tracking) []) current-tracking)
        updated-libs (distinct (into (:libs tracking-entry) selected-libs))
        updated-tracking-entry (assoc-in tracking-entry [:libs] updated-libs)]
    (into [updated-tracking-entry] other-entries)))

(defn update-release [specifier root]
  (let [version-tracking-path (str root "/version-tracking.edn")
        current-version-tracking (helpers/read-edn version-tracking-path)
        selected-libraries (collect-libraries specifier root)
        version (calendar-version-today)
        updated-version-tracking (add-to-tracking selected-libraries version current-version-tracking)]
    (helpers/write-edn updated-version-tracking version-tracking-path)))

(defn -main
  "Adds libraries to version-tracking.edn topmost record (creating version for today if needed)

  Example use:
    bb mark-for-release shared                     <-- includes everything under shared
    bb mark-for-release browser/district-ui-web3-* <-- matches the browser libraries matching glob
    bb mark-for-release server/district-server-db  <-- matches one specific library
  "
  [& args]
  (let [[path-specifier] *command-line-args*]
    (log "Marking libraries for release:" path-specifier)
    (update-release path-specifier (fs/cwd))))

; To allow running as commandn line util but also required & used in other programs or REPL
; https://book.babashka.org/#main_file
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
