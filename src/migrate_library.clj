#!/usr/bin/env bb

(ns migrate-library
  (:require [clojure.java.shell :refer [sh with-sh-dir]]
            [clojure.string :refer [trim]]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.tools.cli :as cli]
            [config :refer [get-config]]
            [script-helpers :refer [log read-edn write-edn] :as helpers]))

(defn absolutize-path [path]
  (let [absolute-path? (clojure.string/starts-with? path "/")]
    (if absolute-path? path (str (fs/cwd) "/" path))))

(defn library-structure-as-expected? [library-path]
  (let [absolute-path (absolutize-path library-path)
        using-deps? (fs/exists? (str absolute-path "/deps.edn"))]
    using-deps?))

(defn add-aliases
  "Adds aliases to deps.edn map to allow building, loading and releasing subsets of
  the libraries under this monorepo. Adds under:
    1) {:aliases {:<server/browser/shared>-all / {...}}
    2) {:aliases {:<library-name> {...}}"
  [deps-map library-path library-name group]
  (let [deps-edn-template {:paths [] :deps {} :aliases {}}
        library-relative-path library-path ; FIXME: parse or pass in library relative path e.g. server/...
        library-alias-key (keyword library-name)
        group-id (helpers/guess-group-id library-path)
        artefact-id library-name
        new-extra-deps {(symbol (str group-id "/" artefact-id)) {:local/root library-relative-path}}
        new-extra-paths [(str library-path "/test")]
        library-alias-value {:extra-deps new-extra-deps
                             :extra-paths new-extra-paths}
        ; The group deps are useful to be able to build or load all libraries
        ; in the group at once E.g. to load all `server` libraries (under alias
        ; :server-all (in REPL) or to build `browser` bundle with all libraries
        ; (under alias :browser-all)
        group-alias (keyword (str group "-all"))
        old-group-deps (or (get-in deps-map [:aliases group-alias :extra-deps]) {})
        old-group-paths (or (get-in deps-map [:aliases group-alias :extra-paths]) [])
        new-group-deps (merge old-group-deps new-extra-deps)
        new-group-paths (->> old-group-paths
                             (into new-extra-paths)
                             (distinct)
                             (into []))
        updated-group {:extra-deps new-group-deps :extra-paths new-group-paths}]
    (-> deps-map
        (assoc-in [:aliases library-alias-key] library-alias-value)
        (assoc-in [:aliases group-alias] updated-group))))

(def default-deps-edn
  {:paths ["src" "test"]
   :mvn/repos {"central" {:url "https://repo1.maven.org/maven2/"}
               "clojars" {:url "https://clojars.org/repo"}}

   :deps {'org.clojure/clojure       {:mvn/version "1.11.1"}
          'thheller/shadow-cljs {:mvn/version "2.20.5"}}
   :install-deps true
   :aliases {}})

(defn move-merging-git-histories
  "Creates branch and merges repository at source-path (with its commit
  history) to repository at `target-path` using last component (folder) name as
  the subfolder (or prefix) in the target. Optionally the destination can be
  specified with 3rd argument, e.g. \"server/smart-contracts\". "
  [source-path target-path group & {:keys [create-new-branch? by-script?] :or {create-new-branch? true by-script? false}}]
  (let [source-path (str (fs/real-path source-path)) ; Going through `real-path` to make relative paths work (e.g. ".")
        target-path (str (fs/real-path target-path))
        source-name (str (last (fs/components source-path))) ; the-thing from /this/is/the-thing
        prefix (str group "/" source-name) ; e.g. browser/district-ui-web3
        merge-result (atom {})
        deps-edn-path (str target-path "/deps.edn")
        has-changes? (= 1 (:exit (sh "git" "diff-index" "HEAD" "--exit-code" "--quiet" :dir target-path)))]
    (with-sh-dir target-path
      (if has-changes?
        (throw (ex-info "Can't continue because repo has changes. Stash or reset them before trying again" {})))
      (if create-new-branch? (sh "git" "checkout" "-b" source-name)) ; Create new branch where to put the history

      (log "Migrating" source-path "to" target-path "under" prefix)
      (if by-script?
        (do
          (log "Migrating with git filter-branch (bin/git-migrate-repo.sh) strategy")
          (let [filter-repo-check (sh "sh" "-c" "hash git-filter-repo")
                temp-container (fs/create-temp-dir {:prefix (str "migrate-library-" source-name)})
                source-copy-in-tmp (str temp-container "/" source-name)
                remote-name prefix]
            (if (not (= 0 (:exit filter-repo-check))) (throw (RuntimeException. (:err filter-repo-check))))

            (fs/copy-tree source-path source-copy-in-tmp)
            (sh "git" "filter-repo" "--force" "--to-subdirectory-filter" prefix :dir source-copy-in-tmp)
            (sh "git" "remote" "add" "-f" remote-name source-copy-in-tmp :dir target-path)
            (reset! merge-result (sh "git" "merge" "--allow-unrelated-histories" (str remote-name "/master") :dir target-path))
            (sh "git" "remote" "remove" remote-name :dir target-path)))
        (do
          (log "Migrating with git subtree strategy...")
          (reset! merge-result (sh "git" "subtree" "add" (str "--prefix=" prefix) source-path "master")))))

    (if (= 1 (:exit @merge-result))
      (throw (ex-info (str "Failed running `git subtree`: " (:err @merge-result)) @merge-result)))
    (helpers/ensure-edn-file deps-edn-path default-deps-edn)
    (-> (read-edn deps-edn-path)
        (add-aliases ,,, prefix source-name group)
        (write-edn ,,, deps-edn-path))
    (log "Done updating deps.edn at" deps-edn-path)))

(def cli-options
  [[nil "--[no-]create-branch" "(default: true) create new branch with library name where to import"
    :default true]
   [nil "--by-script" "(default: false) instead of `git-subtree` use script"
    :default false]
   ["-h" "--help" "print this help about usage"]])

(defn usage [options-summary]
  (->> ["migrate-library  | import individual ClojureScript library repo (with commit history) into a monorepo"
        ""
        "Usage: migrate_library.clj library-path target-path group [options]"
        ""
        "Options:"
        options-summary
        ""
        "  library-path   filesystem or git URL path to the library (has to have deps.edn & shadow-cljs.edn)"
        "  target-path    local filesystem location of a git repository where to import the history"
        "  group          folder under which to put the imported library (normally one of server, browser, shared)"]
       (clojure.string/join "\n")))

(defn validate-args [library-path target-path group]
  (let [errors (atom [])]
    (cond
      (nil? library-path)
      (swap! errors conj "Error: 1st arg `library-path` missing")

      (not (fs/directory? library-path))
      (swap! errors conj (str "Error: library-path `" library-path "` isn't a directory"))

      (nil? target-path)
      (swap! errors conj "Error: 2nd arg `target-path` missing")

      (not (fs/directory? target-path))
      (swap! errors conj (str "Error: target-path `" target-path "` isn't a directory"))

      (nil? group)
      (swap! errors conj "Error: 3rd arg `group` can't be empty"))
    [(empty? @errors) @errors]))

(defn -main [& args]
  (let [[library-path target-path group] *command-line-args*
        parsed-args (cli/parse-opts *command-line-args* cli-options)
        [args-ok? args-errors] (validate-args library-path target-path group)
        {:keys [errors options arguments summary]} parsed-args
        all-errors (concat errors args-errors)]
    (cond
      (seq all-errors)
      (do
        (run! println all-errors)
        (println (usage summary))
        (System/exit -1))
      (:help options)
      (do
        (println (usage summary))
        (System/exit 0))
      args-ok?
      (move-merging-git-histories library-path target-path group :create-new-branch? (:create-branch options) :by-script? (:by-script options))
      :else
      (do
        (println (usage summary))
        (System/exit 0)))))

; To allow running as commandn line util but also required & used in other programs or REPL
; https://book.babashka.org/#main_file
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
