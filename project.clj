(defproject org.clojars.sremani/cljest "0.2.0"
  :description "cljest — mutation testing for Clojure"
  :url "https://github.com/sremani/cljest"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :scm {:name "git" :url "https://github.com/sremani/cljest"}
  :eval-in-leiningen true
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [rewrite-clj/rewrite-clj "1.1.47"]
                 [org.clojure/tools.namespace "1.5.0"]
                 [org.clojure/tools.cli "1.1.230"]]
  :deploy-repositories [["clojars" {:url "https://repo.clojars.org"
                                     :username :env/CLOJARS_USERNAME
                                     :password :env/CLOJARS_PASSWORD
                                     :sign-releases false}]]
  :profiles {:dev {:dependencies [[org.clojure/test.check "1.1.1"]]}})
