# Monorepo Tools
[![CircleCI](https://dl.circleci.com/status-badge/img/gh/district0x/monorepo-tools/tree/master.svg?style=svg)](https://dl.circleci.com/status-badge/redirect/gh/district0x/monorepo-tools/tree/master)

Set of command line tools to facilitate importing and managing existing Clojure(Script) libraries under a monorepo.

## Quickstart

The functionality is made available via Babashka tasks
```
❯ bb tasks
The following tasks are available:

migrate          Import existing Clojure library with git history. Use: bb migrate LIBRARY_PATH TARGET_REPO GROUP
ci-config        Generate CircleCI dynamic configuration. Use: bb ci-config YAML_FILE_NAME
update-versions  Update (transitively) all depending libraries. Use: bb update-versions LIBRARY_SYM VERSION LIBS_TO_UPDATE_PATH
mark-for-release Include libraries to release (on merge) Use: bb mark-for-release LIB_PATH_OR_GLOB
release          Try to shell out to clojure. Use: bb release VERSION LIBPATH
mt-test          Run monorepo-tools tests. Use: bb mt-test [NAMESPACE_OR_PART]
find-candidates  Find conforming libraries to for monorepo migration. Use: bb find-candidates GROUP PATH
```

Most likely during normal development the `bb update-versions` and `bb mark-for-release` are the tasks a developer would use most frequently.



## Detailed use instruction

### 1. Start: setup

To make more convenient running the scripts, they are defined in form of [babashka tasks](https://book.babashka.org/#tasks).
> These scripts can also be run as command line scripts directly, e.g. `./src/migrate_library.clj ~/path-to/library ~/path-to/target-repo group`

To run the babashka tasks and scripts, install [babashka](https://github.com/babashka/babashka#installation) to have the `bb` command on PATH
  - the release binary (just 1 file) can be downloaded and made available on the PATH

As for now the `bb.edn` file where Babashka configuration (and the task definitions live) doesn't get looked up and applied recursively. So to make it work at the top level of the monorepo (of which the current implementation of `monorepo-tools` resides in a subdirectory), copy the `:tasks` submap to the top level of your monorepo. E.g.

1. Add the `monorepo-tools` as git submodule
    ```bash
    git submodule add -b master git@github.com:district0x/monorepo-tools.git
    cp monorepo-tools/bb.edn .
    ```
2. Add `monorepo-tools` as local dependency to `deps.edn`
    ```clojure
    {:deps {is.d0x/monorepo-tools {:local/root "./monorepo-tools"}}}
    ```
3. Then copy over `monorepo-tools/bb.edn` to your monorepo root and change it to have
    ```clojure
    {:paths ["monorepo-tools/src" "monorepo-tools/test"]
    ; ... the rest omitted
    }
    ```
4. Make babashka tasks available by copying config (tasks section & adjusting `:paths`)
    ```bash
    cp monorepo-tools/bb.edn .
    # Then edit the bb.edn to have its `src` and `test` paths point to the subfolder it lives in
    ```

### 2. Migrate existing library to monorepo

```bash
bb migrate ~/path-to-current-library ~/root-path-of-monorepo browser
```
Where:
1. `~/path-to-current-library` - location of the library to be migrate (git repo)
2. `~/root-path-of-monorepo` - the destination root path of git monorepo
3. `browser` string of the group (subfolder) under which to put the migrate library

### 3. Update versions (prepare for release)

```bash
bb update-versions is.mad/some-library 20.10.9 server
```

Where:
1. `is.mad/some-library` - is the library you changed that now needs to be released under new version AND that can cause other libraries that depend on it also need to be re-released (with updated dependency of the originally changed library)
2. `20.10.9` - the version that the library in (1.) will get on new release
  - can be anything (2 or 3 part version consisting of numbers, though we recommend calendar versioning)
3. `server` is the group (folder name) under which the script will look for affected libraries

### 4. Release (build & publish) the library
> While the following is possible manually on developer's computer. The `bb ci-config` (for CircleCI) does the same automatically, producing config that runs the tests and if successful, deploys the libraries according to `version-tracking.edn` to Clojars (only on builds from the master branch)

This step depends on the latest (topmost) entry in `version-tracking.edn` and more specifically the `:libs` and `:version` keys in that map.
Normally a library gets released after a successful merge & test run on master via CircleCI workflow.

If you want to release manually the following is needed:
1. Export `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` (password relly has the value of a _deploy token_) to the ENV
2. Run `bb release 22.10.7 server/cljs-web3-next` to release `cljs-web3-next` with a version `22.10.7`
  - the version will be interpreted as string, so you can put whatever there, also `-SNAPSHOT` versions

## Reasoning behind `monorepo-tools`

### Workings

Couple points to better understand how `monorepo-tools` are designed and meant to work

1. There's only 1 version
  - calendar versioning is used (i.e. `22.10.14` for things published on 14th of October 2022)
  - if you change library and other libraries depending on it are affected, they'll all be published under same version
  - in other words, one merge to master or "publishing event" causes all the artefacts affected to have same version (always increasing)
  - it means also that there can be holes in versioning. For example if last version was `22.10.1` and today iw 14th of October, with no releases in between, then there won't be a `22.10.2` or `22.10.10`, but rather directly next version is `22.10.14`
  - this is possible because Clojure is an interpreted language and versions will be checked during fetch time (e.g. by Maven), so at publish time we can publish a library even though its dependencies with the same version have not yet been published
2. Changed libraries and version history will be tracked in `version_history.edn` in the folder where the scripts are used (likely the monorepo top level)
3. There is a grouping of libraries. In district0x case they are `server`, `browser` and `shared`
  - these will get their aliases in monorepo top level `deps.edn`, which will also automatically updated when new library gets migrated to monorepo
  - the group aliases allow publishing library bundles (e.g. all `server` or `browser` libraries)
  - the group folder names `server`, `browser`, etc. are arbitrary and don't affect namespaces of the libraries
  - each library is responsible for its internal code organization in namespaces
