# ClojureScript Build

A library that builds ClojureScript.

Its main contribution is an awareness of how changed Clojure files in
cljs source paths affect an incremental ClojureScript build.

The `cljs.clojure/build` library call handles incremental builds
pretty well with regards to changes in `cljs` source files. However it
ignores how changes in Clojure source files affect a build. If you
only change a Clojure source file and none of the `cljs` source files
your clj changes won't take.

This library rectifies this situation by noticing if a `clj` file in a
`cljs` source path has changed, and if so it does one of two things:

If the file contains macro definitions, it finds the cljs files that
depend on those macro definitions and marks them for recompile.

If the file doesn't contain macro definitions, it marks all `cljs`
files that depend on macros in the current set of source directories
and marks them for recompile.

It's simple strategy but leads to greatly improved compile times when
working on `clj` source files.

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

### Clojurescript Compiler Options

Here is list of compiler options that can be passed to the
ClojureScript compiler.

#### :output-to 

The path to the JavaScript file that will be output.

```clojure
:output-to "resources/public/js/main.js"
```

#### :output-dir

Sets the output directory for temporary files used during
compilation. Defaults to "out".

```clojure
:output-dir "resources/public/js/out"
```

#### :optimizations

The optimization level. May be `:none`, `:whitespace`, `:simple`, or
`:advanced`. `:none` is the recommended setting for development, while
`:advanced` is the recommended setting for production, unless something
prevents it (incompatible external library, bug, etc.).

`:none` requires manual code loading and hence a separate HTML from
the other options.

Defaults to `:none`.

```clojure
:optimizations :none
```

#### :source-map

See https://github.com/clojure/clojurescript/wiki/Source-maps

```clojure
:source-map true
```

#### :warnings

This flag will turn on compiler warnings for references to  
undeclared vars, wrong function call arities, etc. Defaults to true.

```clojure
:warnings true
```

#### :elide-asserts

This flag will cause all (assert x) calls to be removed during compilation
Useful for production. Default is always false even in advanced compilation.
Does NOT specify goog.asserts.ENABLE_ASSERTS which is different and used by
the closure library.

```clojure
:elide-asserts true
```

#### :pretty-print

Determines whether the JavaScript output will be tabulated in
a human-readable manner.  Defaults to true.

```clojure
:pretty-print true
```

#### :print-input-delimiter

Determines whether comments will be output in the JavaScript that
can be used to determine the original source of the compiled code.

Defaults to false.

```clojure
:print-input-delimiter false
```

#### :target

If targeting nodejs add this line. Takes no other options at the moment.

```clojure
:target :nodejs
```

#### :output-wrapper

Wrap the JavaScript output in (function(){...};)() to avoid clobbering globals.
Defaults to true when using advanced compilation, false otherwise.

```clojure
:output-wrapper false`
```

#### :externs

Configure externs files for external libraries.

Defaults to the empty vector `[]`.

For this option, and those below, you can find a very good explanation at:
   http://lukevanderhart.com/2011/09/30/using-javascript-and-clojurescript.html

```clojure
:externs ["jquery-externs.js"]
```

#### :libs

Adds dependencies on external libraries.  Note that files in these directories will be
watched and a rebuild will occur if they are modified.

Defaults to the empty vector `[]`.

```clojure
:libs ["closure/library/third_party/closure"]
```

#### `:foreign-libs [{:file "http://example.com/remote.js" :provides  ["my.example"]}]`

Adds dependencies on foreign libraries. Be sure that the url returns a HTTP Code 200

Defaults to the empty vector `[]`.

```clojure
:foreign-libs [{ :file "http://example.com/remote.js"
                 :provides  ["my.example"]}]
```

##### `:preamble ["license.js"]`

Prepends the contents of the given files to each output file.

Defaults to the empty vector [].
          
#### :language-in and :language-out

Configure the input and output languages for the closure library.
May be `:ecmascript3`, `ecmascript5`, or `ecmascript5-strict`.

Defaults to ecmascript3.

```clojure
:language-in  :ecmascript3
:language-out :ecmascript3
```

#### `:closure-warnings {:externs-validation :off}}`

Configure warnings generated by the Closure compiler.

```clojure
:closure-warnings {:externs-validation :off}}
```

## License

Copyright © 2014 Bruce Hauman

Distributed under the Eclipse Public License either version 1.0 or any
later version.
