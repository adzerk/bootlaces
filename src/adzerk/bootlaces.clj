(ns adzerk.bootlaces
  {:boot/export-tasks true}
  (:require
   [clojure.java.io    :as io]
   [boot.util          :as util]
   [boot.core          :refer :all]
   [boot.task.built-in :refer :all]
   [boot.git           :refer [last-commit]]
   [adzerk.bootlaces.template :as t]))

(def ^:private +gpg-config+
  (let [f (io/file "gpg.edn")]
    (when (.exists f) (read-string (slurp f)))))

(def ^:private +last-commit+
  (try (last-commit) (catch Throwable _)))

(defn bootlaces!
  [version]
  (set-env! :resource-paths #(into % (get-env :source-paths)))
  (task-options!
    push #(into % (merge {:repo "deploy-clojars" :ensure-version version}
                         (when +last-commit+ {:ensure-clean  true
                                              :ensure-branch "master"
                                              :ensure-tag    (last-commit)})))))

(defn- get-creds []
  (mapv #(System/getenv %) ["CLOJARS_USER" "CLOJARS_PASS"]))

(deftask ^:private collect-clojars-credentials
  "Collect CLOJARS_USER and CLOJARS_PASS from the user if they're not set."
  []
  (fn [next-handler]
    (fn [fileset]
      (let [[user pass] (get-creds), clojars-creds (atom {})]
        (if (and user pass)
          (swap! clojars-creds assoc :username user :password pass)
          (do (println "CLOJARS_USER and CLOJARS_PASS were not set; please enter your Clojars credentials.")
              (print "Username: ")
              (#(swap! clojars-creds assoc :username %) (read-line))
              (print "Password: ")
              (#(swap! clojars-creds assoc :password %)
               (apply str (.readPassword (System/console))))))
        (set-env! :repositories #(conj % ["deploy-clojars" (merge @clojars-creds {:url "https://clojars.org/repo"})]))
        (next-handler fileset)))))

(deftask ^:private update-readme-dependency
  "Update latest release version in README.md file."
  []
  (let [readme (io/file "README.md")]
    (if-not (.exists readme)
      identity
      (with-pre-wrap fileset
        (let [{:keys [project version]} (-> #'pom meta :task-options)
              old-readme (slurp readme)
              new-readme (t/update-dependency old-readme project version)]
          (when (not= old-readme new-readme)
            (util/info "Updating latest Clojars version in README.md...\n")
            (spit readme new-readme))
          fileset)))))

(deftask build-jar
  "Build jar and install to local repo."
  []
  (comp (pom) (jar) (install) (update-readme-dependency)))

(deftask push-snapshot
  "Deploy snapshot version to Clojars."
  [f file PATH str "The jar file to deploy."]
  (comp (collect-clojars-credentials)
        (push :file file :ensure-snapshot true)))

(deftask push-release
  "Deploy release version to Clojars."
  [f file PATH str "The jar file to deploy."]
  (comp
   (collect-clojars-credentials)
   (push
    :file           file
    :tag            (boolean +last-commit+)
    :gpg-sign       true
    :gpg-keyring    (:keyring +gpg-config+)
    :gpg-user-id    (:user-id +gpg-config+)
    :ensure-release true
    :repo           "deploy-clojars")))
