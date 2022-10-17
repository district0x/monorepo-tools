# Monorepo Tools
[![CircleCI](https://dl.circleci.com/status-badge/img/gh/district0x/monorepo-tools/tree/master.svg?style=svg)](https://dl.circleci.com/status-badge/redirect/gh/district0x/monorepo-tools/tree/master)

Set of scripts to facilitate importing existing Clojure(Script) libraries under a monorepo.

## Features

- imports repository with its commit history (using _git subtree_)
- automatic change detection and version bumping (recursive, goes for transitive deps as well)

## Workings

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

## Example workflow

### 1. Start: setup

To make more convenient running the scripts, they are defined in form of [babashka tasks](https://book.babashka.org/#tasks).
> It's worth mentioning that these scripts can also be run as command line scripts directly, e.g. `./src/migrate_library.clj ~/path-to/library ~/path-to/target-repo group`

As for now the `bb.edn` file where Babashka configuration (and the task definitions live) doesn't get looked up and applied recursively. So to make it work at the top level of the monorepo (of which the current implementation of `monorepo-tools` resides in a subdirectory), copy the `:tasks` submap to the top level of your monorepo. E.g.

1. Add the `monorepo-tools` as git submodule
```bash
git submodule add -b master git@github.com:district0x/monorepo-tools.git
cp monorepo-tools/bb.edn .
```

Then change `bb.edn` to have
```clojure
{:paths ["monorepo-tools/src" "monorepo-tools/test"]
; ... the rest omitted
}
```

2. Make babashka tasks available by copying config (tasks section & adjusting `:paths`)
```bash
cp monorepo-tools/bb.edn .
# Then edit the bb.edn to have its `src` and `test` paths point to the subfolder it lives in
```

After which you'll have the babashka tasks available for you at the top level of the monorepo:
```
❯ bb tasks
The following tasks are available:

migrate         Import existing CLJS (using shadow-cljs, deps.edn) library with history from git repo
ci-config       Generates config for CirlceCi dynamic config continuation steps
update-versions Take changed library and bump versions of all affected by it through dependency
mt-test         Run monorepo-tools tests
```

3. Install [babashka](https://github.com/babashka/babashka#installation) to have the `bb` command on PATH
  - the release binary (just 1 file) can be downloaded and made available on the PATH

### 2. Migrate new library

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

### 4. Run *library* tests (i.e. not `monorepo-tools` tests)

### 5. Start a REPL
