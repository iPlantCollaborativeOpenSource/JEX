(ns jex.core
  (:gen-class)
  (:use compojure.core)
  (:use [ring.middleware
         params
         keyword-params
         nested-params
         multipart-params
         cookies
         session]
        [clojure-commons.props]
        [clojure.java.classpath])
  (:require [compojure.route :as route]
            [compojure.handler :as handler]
            [ring.util.response :as rsp-utils]
            [ring.adapter.jetty :as jetty]
            [clojure-commons.clavin-client :as cl]
            [jex.process :as jp]
            [jex.json-body :as jb]
            [clojure.java.io :as ds]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]))

(def jex-props (atom nil))

(defn listen-port
  []
  (Integer/parseInt (get @jex-props "jex.app.listen-port")))

(defn format-exception
  "Formats a raised exception as a JSON object. Returns a response map."
  [exception]
  (log/debug "format-exception")
  (let [string-writer (java.io.StringWriter.)
        print-writer  (java.io.PrintWriter. string-writer)]
    (. exception printStackTrace print-writer)
    (let [localized-message (. exception getLocalizedMessage)
          stack-trace       (. string-writer toString)]
      (log/warn (str localized-message stack-trace))
      {:status 500
       :body (json/json-str {:message     (. exception getLocalizedMessage)
                             :stack-trace (. string-writer toString)})})))

(defn do-submission
  [request]
  (let [body (:body request)]
    (log/info "Received job request:")
    (log/info body)
    
    (if (jp/validate-submission body)
      (let [[exit-code dag-id doc-id] (jp/submit body)]
        (cond
          (not= exit-code 0)
          {:status 400 :body "Submission failed with non-zero status.\n"}
          
          :else
          {:status 200 :body (str "Analysis submitted.\nDAG ID: " dag-id "\nOSM ID: " doc-id "\n")})))))

(defroutes jex-routes
  (GET "/" [] "Welcome to the JEX.")
  
  (POST "/" request
        (try
          (do-submission request)
          (catch java.lang.Exception e
            (format-exception e)))))

(defn site-handler [routes]
  (-> routes
    jb/parse-json-body))

(defn -main
  [& args]
  (def zkprops (parse-properties "jex.properties"))
  (def zkurl (get zkprops "zookeeper"))
  
  (cl/with-zk
    zkurl
    (when (not (cl/can-run?))
      (log/warn "THIS APPLICATION CANNOT RUN ON THIS MACHINE. SO SAYETH ZOOKEEPER.")
      (log/warn "THIS APPLICATION WILL NOT EXECUTE CORRECTLY.")
      (System/exit 1))
    
    (reset! jex-props (cl/properties "jex")))
  
  (jp/init @jex-props)
  (jetty/run-jetty (site-handler jex-routes) {:port (listen-port)}))