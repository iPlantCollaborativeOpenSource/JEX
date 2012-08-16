(ns jex.incoming-xforms
  (:require [clojure.string :as string]
            [jex.argescape :as ae]
            [clojure.tools.logging :as log]
            [clojure-commons.file-utils :as ut]))

(def filetool-path (atom ""))
(def icommands-path (atom ""))
(def condor-log-path (atom ""))
(def nfs-base (atom ""))
(def irods-base (atom ""))
(def filter-files (atom ""))
(def run-on-nfs (atom false))

(def replacer
  "Params: [regex replace-str str-to-modify]."
  #(.replaceAll (re-matcher %1 %3) %2))

(def replace-at
  "Replaces @ sign in a string. [replace-str str-to-modify" 
   (partial replacer #"@"))

(def at-underscore 
  "Replaces @ sign with _. [str-to-modify]"
  (partial replace-at "_"))

(def replace-space 
  "Replaces space. [replace-str str-to-modify]" 
   (partial replacer #"\s"))

(def space-underscore 
  "Replaces _. [replace-str str-to-modify" 
   (partial replace-space "_"))

(def now-fmt 
  "Date format used in directory and file names."
   "yyyy-MM-dd-HH-mm-ss.SSS")

(defn fmt-date
  "Translates date-obj into the format specified by format-str."
  [format-str date-obj]
  (. (java.text.SimpleDateFormat. format-str) format date-obj))

(defn date
  "Returns the current date as a java.util.Date instance."
  [] 
  (java.util.Date.))

(defn filetool-env
  "Creates the filetool environment variables."
  [] 
  (str "PATH=" @icommands-path))

(defn analysis-dirname
  "Creates a directory name for an analysis. Used when the submission
   doesn't specify an output directory.  Some types of jobs, for example
   Foundational API jobs, include a timestamp in the job name, so a timestamp
   will not be appended to teh directory name in those cases."
  [analysis-name date-str]
  (if-not (re-find #"-\d{4}(?:-\d{2}){5}\.\d+$" analysis-name)
    (str analysis-name "-" date-str)
    analysis-name))

(defn now-date
  "Adds a key to condor-map called :now_date that's formatted like now-fmt."
  ([condor-map]
    (now-date condor-map date))
  ([condor-map date-func]
    (assoc condor-map :now_date (fmt-date now-fmt (date-func)))))

(defn pathize
  "Makes a string safe for inclusion in a path by replacing @ and spaces with
   underscores."
  [p]
  (-> p at-underscore space-underscore))

(defn analysis-attrs
  "Adds some basic top-level keys to condor-map that are needed for subsequent
   tranformations."
  [condor-map]
  (assoc 
    condor-map
    :run-on-nfs @run-on-nfs
    :type (or (:type condor-map) "analysis")
    :username (pathize (:username condor-map))
    :nfs_base @nfs-base
    :irods_base @irods-base
    :submission_date (.getTime (date))))

(defn output-directory
  "Returns a string containing iRODS output directory based on settings
   condor-map. Does not actually associate the value with :output_dir in condor-map."
  [condor-map]
  (let [output-dir    (:output_dir condor-map)
        create-subdir (:create_output_subdir condor-map)
        irods-base    (:irods_base condor-map)
        username      (:username condor-map)
        analysis-dir  (analysis-dirname
                       (pathize (:name condor-map))
                       (:now_date condor-map))]
    (cond      
     (or (nil? output-dir)
         (nil? create-subdir))
     (ut/rm-last-slash
      (ut/path-join irods-base username "analyses" analysis-dir))
      
     (and
      (string/blank? output-dir)
      create-subdir)
     (ut/rm-last-slash
      (ut/path-join irods-base username "analyses" analysis-dir))
      
     (and
      (string/blank? output-dir)
      (false? create-subdir))
     (ut/rm-last-slash
      (ut/path-join irods-base username "analyses" analysis-dir))
      
     (and
      (not (string/blank? output-dir))
      create-subdir)
     (ut/rm-last-slash
      (ut/path-join output-dir analysis-dir))
      
      (and (not (string/blank? output-dir)) (false? create-subdir))
      (ut/rm-last-slash output-dir)
      
      :else
      (ut/rm-last-slash
       (ut/path-join irods-base username "analyses" analysis-dir)))))

(defn context-dirs
  "Adds the :output_dir :working_dir and :condor-log-dir keys to the condor-map.
   These values are calculated using values that were added by (analysis-attrs)."
  [condor-map]
  (let [username     (:username condor-map)
        nfs-base     (:nfs_base condor-map)
        analysis-dir (analysis-dirname
                      (pathize (:name condor-map))
                      (:now_date condor-map))
        log-dir-path (ut/path-join @condor-log-path username analysis-dir)
        log-dir      (ut/add-trailing-slash log-dir-path)
        output-dir   (output-directory condor-map)
        working-dir  (ut/add-trailing-slash
                      (ut/path-join nfs-base username analysis-dir))]
    (assoc condor-map 
           :output_dir output-dir
           :working_dir working-dir
           :condor-log-dir log-dir)))

(defn param-maps
  "This looks goofy, but it filters out unneeded crap from the params."
  [params]
  (for [param params]
    {:name  (:name param)
     :value (:value param)
     :order (:order param)}))

(defn naively-quote
  "Naievely single-quotes a string that will be placed on the command line
   using plain string substitution.  This works, but may leave extra pairs
   of leading or trailing quotes if there was a leading or trailing quote
   in the original string, which is valid, but may be confusing to human
   readers."
  [value]
  (str \' (string/replace value "'" "'\\''") \'))

(defn quote-value
  "Quotes and escapes a string that is supposed to be passed in to a tool on
   the command line."
  [value]
  (-> value
    naively-quote
    (string/replace #"^''|''$" "")))

(defn escape-params
  "Escapes the spaces in the params list."
  [params]
  (string/join " "
    (flatten 
      (map 
        #(vector (:name %1) (quote-value (:value %1))) 
        (sort-by :order params)))))

(defn format-env-variables
  "Formats and escapes environment variables that are passed to it."
  [env-map]
  (string/join 
    " " 
    (mapv 
      #(str (name (first %1)) "=" (str "\"" (last %1) "\"")) 
      (seq env-map))))

(defn executable
  [step-map]
  (ut/path-join
   (get-in step-map [:component :location])
   (get-in step-map [:component :name])))

(defn arguments
  [step-map]
  (-> (get-in step-map [:config :params]) param-maps escape-params))

(defn stdin
  [step-map]
  (if (contains? step-map :stdin)
    (quote-value (:stdin step-map))
    nil))

(defn stdout
  [step-map index]
  (if (contains? step-map :stdout)
    (quote-value (:stdout step-map))
    (str "logs/" "condor-stdout-" index)))

(defn stderr
  [step-map index]
  (if (contains? step-map :stderr)
    (quote-value (:stderr step-map))
    (str "logs/" "condor-stderr-" index)))

(defn environment
  [step-map]
  (if (contains? step-map :environment)
    (format-env-variables (:environment step-map))
    nil))

(defn log-file
  [step-map index condor-log]
  (if (contains? step-map :log-file)
    (ut/path-join condor-log (:log-file step-map))
    (ut/path-join condor-log "logs" (str "condor-log-" index))))

(defn step-iterator-vec
  [condor-map]
  (map vector (iterate inc 0) (:steps condor-map)))

(defn process-steps
  [condor-map]
  (for [[step-idx step] (step-iterator-vec condor-map)]
    (assoc step 
      :id (str "condor-" step-idx)
      :type "condor"
      :submission_date (:submission_date condor-map)
      :status "Submitted"
      :environment (environment step)
      :executable (executable step)
      :arguments (arguments step)
      :stdout (stdout step step-idx)
      :stderr (stderr step step-idx)
      :log-file (log-file step step-idx (:condor-log-dir condor-map)))))

(defn steps
  "Processes the steps in a map into a saner format."
  [condor-map]
  (assoc condor-map :steps (process-steps condor-map)))

(defn- handle-source-path
  "Takes in a source path and a multiplicity and adds a trailing slash if
   needed."
  [source-path multiplicity]
  (if (= multiplicity "collection")
    (ut/add-trailing-slash source-path)
    source-path))

(defn input-id-str
  [step-index input-index]
  (str "condor-" step-index "-input-" input-index))

(defn input-stdout
  [step-index input-index]
  (str "logs/" (input-id-str step-index input-index) "-stdout"))

(defn input-stderr
  [step-index input-index]
  (str "logs/" (input-id-str step-index input-index) "-stderr"))

(defn input-log-file
  [condor-log step-index input-index]
  (ut/path-join
   condor-log
   "logs"
   (str (input-id-str step-index input-index) "-log")))

(defn input-arguments
  [user source input-map]
  (str "get --user " user
       " --source " (quote-value
                     (handle-source-path source (:multiplicity input-map)))))

(defn input-iterator-vec
  [step-map]
  (map vector (iterate inc 0) (get-in step-map [:config :input])))

(defn process-step-inputs
  [condor-map [step-idx step-map]]
  (for [[input-idx input] (input-iterator-vec step-map)]
    {:id              (input-id-str step-idx input-idx)
     :submission_date (:submission_date condor-map)
     :type            "condor"
     :status          "Submitted"
     :retain          (:retain input)
     :multi           (:multiplicity input)
     :source          (:value input)
     :executable      @filetool-path
     :environment     (filetool-env)
     :arguments       (input-arguments
                       (:username condor-map)
                       (:value input)
                       input)
     :stdout          (input-stdout step-idx input-idx)
     :stderr          (input-stderr step-idx input-idx)
     :log-file        (input-log-file
                       (:condor-log-dir condor-map)
                       step-idx
                       input-idx)}))

(defn process-inputs
  [condor-map]
  (for [step-iter (step-iterator-vec condor-map)]
    (assoc (last step-iter)
      :input-jobs (process-step-inputs condor-map step-iter))))

(defn input-jobs
  "Adds output job definitions to the incoming analysis map."
  [condor-map]
  (assoc condor-map :steps (process-inputs condor-map)))

(defn output-jobs
  "Adds output job definitions to the incoming analysis map.

   condor-map must have the following key-values before calling:
         :output_dir :working_dir

   The result of this function is a map in each step called :output-jobs
   with the following format:
       {:id String
        :source String
        :dest   String}
  "
  [condor-map]
  (assoc condor-map 
         :steps
         (let [stepv (map vector (iterate inc 0) (:steps condor-map))] 
           (for [[step-idx step] stepv]
             (assoc step :output-jobs
                    (let [config  (:config step)
                          user    (:username condor-map)
                          outputs (:output config)
                          outputs-len (count outputs)]
                      (let [outputv (map vector (iterate inc 0) outputs)] 
                        (for [[output-idx output] outputv]
                          (let [source      (:name output)]
                            (let [dest (:output_dir condor-map)]
                              {:id              (str "condor-" step-idx "-output-" output-idx)
                               :type            "condor"
                               :status          "Submitted"
                               :submission_date (:submission_date condor-map)
                               :retain          (:retain output)
                               :multi           (:multiplicity output)
                               :environment     (filetool-env)
                               :executable      @filetool-path
                               :arguments       (str "put --user " user " --source " source " --destination " (quote-value dest))
                               :source          source
                               :dest            dest}))))))))))

(defn all-input-jobs
  "Adds the :all-input-jobs key to condor-map. It's a list of all of the input jobs
   in the submission, extracted from the :steps list."
  [condor-map]
  (assoc condor-map :all-input-jobs
         (apply concat (map :input-jobs (:steps condor-map)))))

(defn all-output-jobs 
  "Adds the :all-output-jobs key to condor-map. It's a list of all of the output jobs
   in the submission, extracted from the :steps list."
  [condor-map]
  (assoc condor-map :all-output-jobs
         (apply concat (map :output-jobs (:steps condor-map)))))

(defn- input-coll [jdef]
  "Examines an input job definition and returns the path to file or directory."
  (quote-value
    (let [multi (:multi jdef)
          fpath (ut/basename (:source jdef))]
      (if (= multi "collection") (ut/add-trailing-slash fpath) fpath))))

(defn- make-abs-output
  "Takes in an output path and makes it absolute if it's not. Note that
   this is intended for use in a bash script and will get executed on
   the Condor cluster."
  [out-path]
  (if (not (. out-path startsWith "/"))
    (str "$(pwd)/" (quote-value out-path))
    (quote-value out-path)))

(defn- output-coll
  "Examines an output job definition and returns the path to the file or directory."
  [jdef]
  (let [multi (:multi jdef)
        fpath (:source jdef)]
    (if (= multi "collection") 
      (make-abs-output (ut/add-trailing-slash fpath)) 
      fpath)))

(defn- parse-filter-files
  "Parses the filter-files configuration option into a list."
  []
  (into [] (filter #(not (string/blank? %)) (string/split @filter-files #","))))

(defn exclude-arg
  "Formats the -exclude option for the filetool jobs based on the input and output
   job definitions."
  [inputs outputs]
  (log/info "exclude-arg")
  (log/info (str "COUNT INPUTS: " (count inputs)))
  (log/info (str "COUNT OUTPUTS: " (count outputs)))
  (let [not-retain   (comp not :retain)
        input-paths  (map input-coll (filter not-retain inputs))
        output-paths (map output-coll (filter not-retain outputs))
        all-paths    (flatten (conj input-paths output-paths (parse-filter-files)))]
    (if (> (count all-paths) 0) 
      (str "--exclude " (string/join "," all-paths)) 
      "")))

(defn imkdir-job-map
  "Formats a job definition for the imkdir job, which is run first
   and creates the iRODS output directory."
  [output-dir condor-log username]
  {:id "imkdir"
   :status "Submitted"
   :environment (filetool-env)
   :executable @filetool-path
   :stderr "logs/imkdir-stderr"
   :stdout "logs/imkdir-stdout"
   :log-file (ut/path-join condor-log "logs" "imkdir-log")
   :arguments (str "mkdir --user " username " --destination " (quote-value output-dir))})

(defn shotgun-job-map
  "Formats a job definition for the output job that transfers
   all of the files back into iRODS after the analysis is complete."
  [output-dir condor-log cinput-jobs coutput-jobs username]
  (log/info "shotgun-job-map")
  {:id          "output-last"
   :status      "Submitted"
   :executable  @filetool-path
   :environment (filetool-env)
   :stderr      "logs/output-last-stderr"
   :stdout      "logs/output-last-stdout"
   :log-file    (ut/path-join condor-log "logs" "output-last-log")
   :arguments   (str "put --user " username " --destination " 
                     (quote-value output-dir) 
                     " " 
                     (exclude-arg cinput-jobs coutput-jobs))})

(defn extra-jobs
  "Associates the :final-output-job and :imkdir-job definitions
   with condor-map. Returns a new version of condor-map."
  [condor-map]
  (let [output-dir   (:output_dir condor-map)
        condor-log   (:condor-log-dir condor-map)
        cinput-jobs  (:all-input-jobs condor-map)
        coutput-jobs (:all-output-jobs condor-map)]
    (log/info (str "COUNT ALL-INPUTS: " (count cinput-jobs)))
    (log/info (str "COUNT ALL-OUTPUTS: " (count coutput-jobs)))
    (assoc condor-map 
           :final-output-job
           (shotgun-job-map output-dir condor-log cinput-jobs coutput-jobs (:username condor-map))
           
           :imkdir-job
           (imkdir-job-map output-dir condor-log (:username condor-map)))))

(defn rm-step-component
  "Removes the :component key-value pair from each step in condor-map.
   Returns a new version of condor-map."
  [condor-map]
  (assoc condor-map :steps
         (for [step (:steps condor-map)]
           (dissoc step :component))))

(defn rm-step-config
  "Removes the :config key-value pair from each step in condor-map.
   Returns a new version of condor-map."
  [condor-map]
  (assoc condor-map :steps
         (for [step (:steps condor-map)]
           (dissoc step :config))))

(defn transform
  "Transforms the condor-map that's passed in into something more useable."
  [condor-map]
  (-> condor-map
    now-date
    analysis-attrs
    context-dirs
    steps
    input-jobs
    output-jobs
    all-input-jobs
    all-output-jobs
    extra-jobs
    rm-step-component
    rm-step-config))

