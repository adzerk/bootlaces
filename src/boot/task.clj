(ns boot.task
  "Task utilities"
  {:boot/export-tasks true}
  (:require [clojure.spec :as s]
            [boot.core :as boot]
            [boot.util :as util]))

(defn set-system-properties!
  "Set a system property for each entry in the map m."
  [m]
  (doseq [kv m]
    (System/setProperty (-> kv key str) (-> kv val str))))

(defn- apply-conf!
  "Calls boot.core/set-env! with the content of the :env key and
  System/setProperty for all the key/value pairs in the :props map."
  [conf]
  (util/dbug "Applying conf:\n%s\n" (util/pp-str conf))
  (let [env (:env conf)
        props (:props conf)]
    (apply boot/set-env! (reduce #(into %2 %1) [] env))
    ;; TODO replace with a spec!
    (assert (or (nil? props) (map? props))
            (format "Option :props should be a map, was %s." (pr-str props)))
    (assert (every? #(and (string? (key %)) (string? (val %))) props)
            (format "Option :props does not contain only strings, was %s" (pr-str props)))
    (set-system-properties! props)))

;; Spec for our little DSL

(s/def ::env map?)
(s/def ::task-sym symbol?)
(s/def ::task-form (s/coll-of ::task-sym :kind list?))
(s/def ::pipeline (s/alt :task-form ::task-sym
                         :comp-of-task-forms (s/cat :comp #{'comp} :tasks (s/* ::task-form))))
(s/def ::conf (s/keys :req-un [::env ::pipeline]))

(comment
  (s/explain :boot.task/pipeline '(arst)) ;; val: () fails spec: :boot.task/pipeline predicate: (alt :task-form :boot.task/task-sym :comp-of-task-forms (cat :comp #{(quote comp)} :tasks (* :boot.task/task-form))),  Insufficient input
  (s/explain :boot.task/pipeline '(arst)) ;; val: () fails spec: :boot.task/task-form at: [:tasks] predicate: :boot.task/task-form,  Insufficient input
  (s/explain :boot.task/pipeline '(comp (arst))))

(defmacro resolve-boot-tasks!
  "Resolve the symbol to a boot task (checking meta as well)"
  [syms]
  `(->> ~syms
        (mapv (fn [sym#]
                (let [resolved-task# (resolve sym#)
                      resolved-meta# (meta resolved-task#)]
                  (when (>= @boot.util/*verbosity* 3)
                    (boot.util/dbug* "Resolved %s (meta follows)\n%s\n" sym# resolved-meta#))
                  (when (:boot.core/task resolved-meta#)
                    resolved-task#))))
        (remove nil?)))

(defmacro task-symbols
  "Return a vector of task namespace/task-name

  The user-provided namespace will be first in the vector, followed by
  boot.task.built-in and boot.user. Typically you should try resolving
  in this order."
  [user-ns task-name]
  `(mapv (fn [ns#] (symbol (str ns#) (str ~task-name)))
         ;; order counts here
         (->> (conj '(boot.task.built-in boot.user) ~user-ns)
              (remove nil?))))

(defn middlewares
  "Produce boot middleware from configuration data"
  [conf]
  (do (boot.task/apply-conf! conf)
      (boot.util/dbug* "Spec Validation: %s\n" (boot.util/pp-str
                                                (or (clojure.spec/explain-data :boot.task/conf conf) ::success)))
      (clojure.spec/assert* :boot.task/conf conf)
      (let [normalized-tasks# (->> conf
                                   :pipeline
                                   flatten
                                   (remove (comp #{"comp"} name)) ;; odd (= 'comp %) works in the repl not from cmd line
                                   (mapv #(vector (-> % name symbol) (some-> % namespace symbol))))]
        (boot.util/dbug* "Normalized tasks: %s\n" (boot.util/pp-str normalized-tasks#))
        ;;
        ;; Phase 1: require namespace and throw if cannot resolve
        (doseq [[task-name# ns#] normalized-tasks#]
          (when ns#
            (boot.util/info "Requiring %s...\n" (boot.util/pp-str ns#))
            (require ns#)))
        ;; Phase 2: compose middlewares
        (reduce (fn [acc# [task-name# ns#]]
                  (let [task-syms# (boot.task/task-symbols ns# task-name#)
                        resolved-vars# (boot.task/resolve-boot-tasks! task-syms#)]
                    (boot.util/dbug* "Resolution order: %s\n" (boot.util/pp-str task-syms#))
                    (if (seq resolved-vars#)
                      (comp acc# (apply (first resolved-vars#)
                                        (mapcat identity (->> task-name# name keyword (get conf)))))
                      (throw (ex-info (str "Cannot resolve either " (clojure.string/join " or " task-syms#) ", is the task spelled correctly and its dependency on the classpath?")
                                      {:task-syms task-syms#})))))
                identity
                normalized-tasks#))))

(defmacro deftask-edn
  "Create boot tasks based on an edn conf.

  The body of this macro will be evaluated and the expected result has
  to match the following example:

      {:env {:resource-paths #{\"resources\"}
             :source-paths #{\"src/web\" \"src/shared\"}
             :dependencies '[[org.clojure/clojure \"1.9.0-alpha14\"]
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
       :cljs {:source-map true
              :optimizations :advanced
              :compiler-options {:closure-defines {\"goog.DEBUG\" false}
                                 :verbose true}}
       :cljs-devtools {...}}

  As in any declarative approach, we need to establish some convention:

   - the `:env` key will be passed with no modification to `set-env!`
   - the `:props` key will need to contain a map of `string` keys to
     `string` values that will set Java System Properties
   - the keys that match a task name will provide options to that task
   - the `:pipeline` key will contain the `comp` typically used in
     `boot` tasks (order counts)
   - if a tasks has full namespace in `:pipeline`, the namespace will be
     required and the task resolved. If not, the task will be resolved in
     `boot.task.built-in` first and finally in `boot.user`"
  [sym & forms]
  (let [[heads [bindings & tails]] (split-with (complement vector?) forms)
        new-forms (reverse (-> (into `(~sym boot.core/deftask) heads)
                               (conj bindings `(boot.task/middlewares (do ~@tails)))))]
    (boot.util/dbug* "deftask-edn generated:\n%s\n" (boot.util/pp-str new-forms))
    `(~@new-forms)))
