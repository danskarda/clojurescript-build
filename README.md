# ClojureScript Build

This is just getting started but it hopes to be a fast build lib for
ClojureScript with an emphasis on incremental builds.

Its main contribution is an awareness of how changed Clojure files in
cljs source paths affect a cljs build.

This is not going to be a watching lib. It is going to provide a
similar interface to `cljs.closure/build`. But it is going to be a
smarter `build` in that changes to .clj files in clojurescript source
paths will mark the appropriate .cljs files for recompile.

It also serves as a mind dump of everything I have learned about the
compiler and incremental builds and is probably destined to be the
main build library for figwheel.

## License

Copyright © 2014 Bruce Hauman

Distributed under the Eclipse Public License either version 1.0 or any
later version.
