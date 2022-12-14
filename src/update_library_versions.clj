#!/usr/bin/env bb

(ns update-library-versions
  (:require [clojure.java.shell :refer [sh with-sh-dir]]
            [clojure.string :refer [trim]]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.tools.cli :as cli]
            [script-helpers :refer [log read-edn write-edn] :as helpers])
  (:import [java.time.format DateTimeFormatter]
           [java.time LocalDateTime]))

(defn contains-deps-edn? [path]
  (fs/exists? (str path "/deps.edn")))

(defn current-timestamp []
  (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")))

(def three-part-semver-regex
  ; Supports 3 part version e.g. "1.2.3"
  ; with optional last part (ignored), e.g. "1.2.3-SUPERDUPER"
  ; And also supports 2 part, where the missing 1st will be considered zero
  ;   - this is to
  ; E.g."1.2" => [0 1 2]
  #"(\d+)?\.?(\d+)\.(\d+)(-.*)?")

(defn version->numeric [version]
  (->> version
       (re-matches three-part-semver-regex ,,,) ; "1.2.3-XXX" => ["1.2.3-XXX" "1" "2" "3" "-XXX"]
       rest                  ; drop first (the whole match)
       reverse               ; reverse to use (rest) again to drop but this time from the other end
       rest                  ; drop last (which can be nil or for example -SNAPSHOT or similar)
       reverse               ; restore original order
       (map #(or % "0") ,,,) ; replace optional 1st semver part (nil) with 0
       (map #(Integer/parseInt %) ,,,)
       (into [])))

(defn version< [version-a version-b]
  (let [a-parts (into [] (version->numeric version-a))
        b-parts (into [] (version->numeric version-b))]
    (if (or (nil? version-a) (nil? version-b))
      false
      (= -1 (compare a-parts b-parts)))))

(defn load-all-libs-in-subfolders
  "Takes path and returns list of paths to deps.edn files in its direct subfolders"
  [path]
  (let [library-paths (->> (fs/list-dir path)
                           (filter fs/directory?)
                           (filter contains-deps-edn?))]
    (map #(str % "/deps.edn") library-paths)))

(defn update-deps-if-outdated [library-version library-name deps-details]
  (let [deps-map (:edn deps-details)
        library-sym (symbol library-name)
        used-version (get-in deps-map [:deps library-sym :mvn/version])] ; io.github.district0x/cljs-web3-next {:mvn/version "0.2.0-SNAPSHOT"}
    (cond
      (nil? used-version) nil
      (nil? library-version) nil
      (version< used-version library-version) (assoc-in deps-details [:edn :deps library-sym :mvn/version] library-version))))

(defn find-first-dep
 "Given library as symbol finds the entry in vector or list of maps that has it
 under {:library <...>}"
 [library deps-details-to-search]
  (first (filter #(= (:library %) library) deps-details-to-search)))

(defn given-or-newer-deps [new-deps-details old-single-detail]
  (or (find-first-dep (:library old-single-detail) new-deps-details) old-single-detail))

(defn calculate-deps-updates
  "Takes one or more modified libraries and a single version (newest). Returns
  deps-details map with :deps (contents of deps.edn) updated for each library
  that depends on an entry in `modified-libs` directly or transitively."
  [version modified-libs deps-details]
  (if (empty? modified-libs)
    deps-details
    (let [first-lib (first modified-libs)
          rest-libs (rest modified-libs)
          new-deps-details (map #(update-deps-if-outdated version first-lib %) deps-details)
          compacted (remove nil? new-deps-details)
          changed-lib-names (map :library compacted)
          updated-deps-details (doall (map #(given-or-newer-deps compacted %) deps-details))
          modified-libs-remaining (into rest-libs changed-lib-names)]
      (calculate-deps-updates version modified-libs-remaining updated-deps-details))))

(defn filter-changed-deps-details
  "Takes 2 vectors of dep details and returns only the entries that have changed in *newer*"
  [older newer]
  (filter (fn [dep-detail]
            (not (let [library (:library dep-detail)
                       old-first (find-first-dep library older)
                       new-first (find-first-dep library newer)]
                   (= old-first new-first)))) newer))

(defn updated-deps
  "Takes 3 params:
    1) version (calendar versioning) e.g. 2022.09.22
    2) library symbol e.g. is.mad/some-library
    3) vector of deps.edn details like {:library <symbol> :path <string path filesystem>
                                        :deps <contents of deps.edn>}
  And finds dependencies and transitive dependencies (recursively) that need to
  be updated directly or as a result of another library getting updated.

  Returns vector described in 3) with ONLY the updated deps.edn details (i.e.
  if it stayed the same, gets filtered)
  "
  [version library deps-details]
  (let [updated-deps-details (calculate-deps-updates version [library] deps-details)]
    (filter-changed-deps-details deps-details updated-deps-details)))

(defn lib-name-from-path
  "Takes deps.edn path and returs penultimate component of the path.
  E.g. /one/two/three-is-lib/deps.edn => three-is-lib"
  [library-path]
  (-> library-path
      fs/components
      reverse
      second
      str))

(defn collect-deps-details
  ([deps-edn-path] (collect-deps-details deps-edn-path helpers/guess-group-id))
  ([deps-edn-path group-id-fn]
   {:library (symbol (str (group-id-fn deps-edn-path) "/" (lib-name-from-path deps-edn-path)))
    :path (clojure.string/replace deps-edn-path #"/deps.edn$" "")
    :edn (helpers/read-edn deps-edn-path)}))

(defn write-deps-detail [detail]
  (let [library (:library detail)
        target-path (str (:path detail) "/deps.edn")
        deps-edn (:edn detail)]
    (helpers/write-edn deps-edn target-path)
    target-path))

(defn update-version-tracking [version change-details repo-path]
  (let [version-tracking-path (str repo-path "/version-tracking.edn")
        _ (helpers/ensure-edn-file version-tracking-path [])
        previous-details (helpers/read-edn version-tracking-path)
        updated-libraries (->> change-details
                               (map :path)
                               (map #(fs/relativize repo-path %))
                               (map str))
        version-info {:created-at (current-timestamp)
                      :version version
                      :description "placeholder"
                      :libs (into [] updated-libraries)}
        updated-version-tracking (into [] (concat [version-info] previous-details))]
    (helpers/write-edn updated-version-tracking version-tracking-path)))

(defn update-deps-at-path
  "Takes updated library path, version and path where to look for affected
  libraries. Then bumps the version when finds affected library and tries it
  recursively to update all libraries whose deps.edn got affected by previous
  update and thus need the libraries that depend on them to be bumped"
  [updated-library new-version updatable-libraries-path & {:keys [source-group-id] :or {source-group-id helpers/guess-group-id}}]
  (log "Updating dependencies. Starting from:" updated-library new-version updatable-libraries-path)
  (let [deps-details (map #(collect-deps-details % source-group-id) (load-all-libs-in-subfolders updatable-libraries-path))
        change-details (updated-deps new-version updated-library deps-details)]
    (if (not (empty? change-details)) (update-version-tracking new-version change-details (fs/parent updatable-libraries-path)))
    (doall (map write-deps-detail change-details))))

(def task-doc
  "Recursively finds libraries in LIBRARY path (and transitively dependent
  libraries) that have library at UPDATED_PATH as their dependency and updates
  the version to the VERSION

  Usage: bb update-versions LIBRARY VERSION UPDATED_PATH

  Example usage:
    bb update-versions is.d0x/cljs-web3-next 22.12.5 browser")
(defn -main [& args]
  (let [[library version updated-path] *command-line-args*]
    (if (some clojure.string/blank? [library version updated-path])
      (do
        (log "ERROR: LIBRARY VERSION or UPDATED_PATH missing from arguments\n")
        (log task-doc))
      (update-deps-at-path library version updated-path))))

; To allow running as commandn line util but also required & used in other programs or REPL
; https://book.babashka.org/#main_file
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
