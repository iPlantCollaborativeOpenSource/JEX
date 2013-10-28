(defproject jex/jex "0.3.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/tools.logging "0.2.3"]
                 [org.clojure/java.classpath "0.1.0"]
                 [cheshire "5.0.1"]
                 [compojure "1.0.1"]
                 [ring/ring-jetty-adapter "1.0.1"]
                 [org.iplantc/clojure-commons "1.4.1-SNAPSHOT"]
                 [slingshot "0.10.3"]]
  :iplant-rpm {:summary "jex",
               :runuser "condor"
               :dependencies ["iplant-service-config >= 0.1.0-5" "iplant-clavin"],
               :config-files ["log4j.properties"],
               :config-path "conf"}
  :profiles {:dev {:dependencies [[midje "1.4.0"]
                                  [lein-midje "2.0.0-SNAPSHOT"]]}}
  :aot [jex.core]
  :main jex.core
  :min-lein-version "2.0.0"
  :repositories {"iplantCollaborative"
                 "http://projects.iplantcollaborative.org/archiva/repository/internal/",
                 "renci.repository"
                 "http://ci-dev.renci.org/nexus/content/repositories/snapshots/"}
  :plugins [[org.iplantc/lein-iplant-rpm "1.4.3-SNAPSHOT"]]
  :description "A backend job execution service that submits jobs to Condor.")

