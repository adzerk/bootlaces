# bootlaces

[](dependency)
```clojure
[adzerk/bootlaces "0.1.13"] ;; latest release
```
[](/dependency)

Handy tasks and things for the [boot Clojure build tool][1].

* Provides `build-jar`, `push-snapshot`, and `push-release` tasks
* Parses a `gpg.edn` file to configure GPG keyring and key ID for jar signing.

> This is an example build and deployment workflow. It showcases the generality
> of boot. We actually use this at Adzerk, but you should [fork] and tailor it
> for your own organization.

## Usage

Add `bootlaces` to your `build.boot` dependencies and `require` the namespace:

```clj
(set-env! :dependencies '[[adzerk/bootlaces "X.Y.Z" :scope "test"]])
(require '[adzerk.bootlaces :refer :all])
```

Then initialize bootlaces with the project version:

```clj
(def +version+ "0.0-2371-5")
(bootlaces! +version+)
```

#### Some things you can do in the terminal:

```bash
# build and install project jar file
boot build-jar
```

```bash
# set environment variables
export CLOJARS_USER=foo
export CLOJARS_PASS=bar
```

```bash
# deploy snapshot to clojars
boot build-jar push-snapshot
```

```bash
# deploy release to clojars
boot build-jar push-release
```

#### Signing

The `gpg.edn` file format:

```clojure
{:keyring "/path/to/secring.gpg"
 :user-id "Micha Niskin <micha.niskin@gmail.com>"}
```

`gpg.edn` can be global, sourced fom your home directory, or local to your project. Local `gpg.edn` takes precedence over global one.

#### Task generation through edn

Bootlaces provides a convenient way to use pure data for defining you own tasks.

A new task can be generated from edn with the `task.util/deftask-edn` macro: the body of this macro will be evaluated and the expected result should be a map conforming the following sample.

    {:env {:resource-paths #{...}
           :source-paths #{...}
           :dependencies '[[clojure/clojure \"1.9.0-alpha14\"]
                           [adzerk/boot-cljs \"2.0.0-SNAPSHOT\" :scope \"test\"]
                           [org.clojure/clojurescript \"1.9.456\"  :scope \"test\"]
                           [reagent \"0.6.0\"]
                           ...]}
     :pipeline '(comp (pandeiro.boot-http/serve)
                      (watch)
                      (powerlaces.boot-cljs-devtools/cljs-devtools)
                      (powerlaces.boot-figreload/reload)
                      (adzerk.boot-cljs-repl/cljs-repl)
                      (adzerk.boot-cljs/cljs))
     :props {"maven.home" (java.lang.System/getenv "M2_HOME")
             "maven.local-repo" (str (java.lang.System/getenv "HOME") "/.m2")}
     :cljs {:source-map true
            :optimizations :advanced
            :compiler-options {:closure-defines {\"goog.DEBUG\" false}
                               :verbose true}}
     :cljs-devtools {...}}

As in any declarative approach, we need to establish some convention:

 - the `:env` key will be passed with no modification to `set-env!`
 - the `:props` key will need to contain a map of `string` keys to `string` values that will set [Java System Properties](http://docs.oracle.com/javase/tutorial/essential/environment/sysprop.html)
 - the keys that match a task name will provide options to that task
 - the `:pipeline` key will contain the `comp` typically used in `boot` tasks (order counts)
 - if a tasks has full namespace in `:pipeline`, the namespace will be required and the task resolved. If not, the task will be resolved in `boot.task.built-in` first and finally in `boot.user`.

A more complete and working example can be found [here](https://github.com/elasticpath/rest-resource-viz/blob/master/build.boot#L42-L92).

## License

Copyright © 2014 Micha Niskin and Alan Dipert

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.

[1]: https://github.com/boot-clj/boot
[2]: http://clojars.org/adzerk/bootlaces/latest-version.svg?cache=2
[3]: http://clojars.org/adzerk/bootlaces
[fork]: https://github.com/adzerk/bootlaces/fork
