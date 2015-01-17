# ClojureScript Build

A library that builds ClojureScript.

Its main contribution is an awareness of how changed Clojure files in
cljs source paths affect an incremental ClojureScript build.

The `cljs.clojure/build` library call handles incremental builds
pretty well with regards to changes in `cljs` source files. However it
ignores how changes in Clojure source files affect a build. If you
only change a Clojure source file and none of the `cljs` source files
your `clj` changes won't take.

This library rectifies this situation by noticing if a `clj` file in
your ClojureScript source paths has changed, and if so, it does one of
two things:

If the file contains macro definitions, it finds the ClojureScript
files that depend on those macro definitions and marks them for
recompile.

If the file doesn't contain macro definitions, it takes all `cljs`
files that depend on macros in the current set of source directories
and marks them for recompile.

It's a simple strategy. But it leads to greatly improved incremental
compile times when working on `clj` source files.

### Usage

Include `clojurescript-build` as a dependency in your `project.clj`.
Since it's probably a developement only dependency you can just place
it in the `dev` profile like so:

```clojure
:profiles {
  :dev {
    :dependencies [[clojurescript-build "0.1.0-SNAPSHOT"]]
  }
}
```

### Building ClojureScript

There are two main library calls that you can make. One for a single
build and one for an auto-watching build.

To do a single build call:

```clojure
(require '[clojurescript-build.core :as cljsb])

(cljsb/build-source-paths ["src" "dev/src"] ; list source directories
                          { :output-to "output/hello.js"
                            :output-dir "output/out"
                            :source-map true
                            :optimizations :none })
```

The `build-source-paths` call takes a list of directories that hold
cljs source and a build options map. The build options map is just
passed on to `cljs.closure/build` and as so it takes all of the
options that `cljs.closure/build` takes.

To do an auto-build where source files are watched for changes and then
recompiled.

```clojure
(require '[clojurescript-build.auto :as auto])

(auto/autobuild ["src" "dev/src"] ; list source directories
                { :output-to "output/hello.js"
                  :output-dir "output/out"
                  :source-map true
                  :optimizations :none })
```

### ClojureScript Compiler Options

[All ClojureScript compiler options](https://github.com/clojure/clojurescript/wiki/Compiler-Options)

## License

Copyright © 2014 Bruce Hauman

Distributed under the Eclipse Public License either version 1.0 or any
later version.
