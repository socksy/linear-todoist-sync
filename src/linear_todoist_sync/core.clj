(ns linear-todoist-sync.core
  (:require [babashka.http-client :as http]
            [babashka.cli :as cli]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn secrets [& [work-dir]]
  (let [todoist-api-key (not-empty (System/getenv "todoist_api_key"))
        linear-api-key (not-empty (System/getenv "linear_api_key"))
        secrets (cond->
                    (try
                      (-> (str (or work-dir ".") "/secrets.edn") slurp edn/read-string)
                      (catch java.io.FileNotFoundException _
                        {}))
                  todoist-api-key (assoc-in [:todoist :api-key] todoist-api-key)
                  linear-api-key (assoc-in [:linear :api-key] linear-api-key))]
    (when-not (and (get-in secrets [:todoist :api-key])
                 (get-in secrets [:linear :api-key]))
      (.println *err* "Missing secrets, either copy secrets.edn.example to secrets.edn,")
      (.println *err* "or set the env vars $todoist_api_key and $linear_api_key.")
      (.println *err* (str work-dir "/secrets.edn"))
      (System/exit 1))
    secrets))

(defn config [& [work-dir]]
  (if-let [found-path (first (filter #(.exists (java.io.File. %)) 
                                     (if work-dir
                                       [(str work-dir "/config.edn") 
                                        (str work-dir "/config.edn.example")]
                                       ["config.edn" "config.edn.example"])))]
    (try
      (-> found-path slurp read-string)
      (catch Exception e
        (println "Error reading" found-path ":" (.getMessage e))
        {:llm {:enabled false}}))
    {:llm {:enabled false}}))

(defn load-llm-cache [& [work-dir]]
  (let [path (if work-dir (str work-dir "/.llm-cache.edn") ".llm-cache.edn")]
    (try
      (-> path slurp read-string)
      (catch java.io.FileNotFoundException _
        #{}))))

(defn save-llm-cache! [cache & [work-dir]]
  (spit (if work-dir (str work-dir "/.llm-cache.edn") ".llm-cache.edn")
        (pr-str cache)))

(defn llm-request! [base-url model prompt opts]
  (try
    (let [payload {:stream false
                   :model model
                   :messages [{:role "user" :content prompt}]}
          url (str base-url "/chat/completions")
          payload-json (json/generate-string payload)
          result (babashka.process/shell {:out :string}
                                        "curl" "-s" "-X" "POST" url
                                        "-H" "Content-Type: application/json"
                                        "-d" payload-json)]
      (if (= 0 (:exit result))
        (-> result
            :out 
            (json/parse-string true)
            (get-in [:choices 0 :message :content]))
        (do (println "Curl failed with exit code:" (:exit result))
            (println "Error:" (:err result))
            nil)))
    (catch Exception e
      (println "LLM request failed:" (.getMessage e))
      nil)))

(defn evaluate-task-with-llm [task {:keys [base-url model prompt] :as llm-config}]
  (let [filled-prompt (-> prompt
                        (str/replace "{task-content}" (:content task ""))
                        (str/replace "{task-description}" (:description task "")))
        reasoning (llm-request! base-url model filled-prompt 
                               {:max_tokens 200 :temperature 0.1})
        json-prompt (str reasoning "\n\nAnswer with JSON: {\"answer_is_yes\": true/false}")
        result (llm-request! base-url model json-prompt 
                            {:max_tokens 50 :temperature 0.0})]
    
    (when (and reasoning result)
      (try
        (:answer_is_yes (json/parse-string result true))
        (catch Exception _
          (str/includes? (str/lower-case result) "true"))))))

;; Linear GraphQL Integration
(defn query-linear! [api-key query]
  (try
    (let [response (http/post "https://api.linear.app/graphql"
                             {:headers {"Authorization" api-key
                                       "Content-Type" "application/json"}
                              :body (json/generate-string {:query query})})]
      (if (= 200 (:status response))
        (json/parse-string (:body response) true)
        (do (println "Linear API error:" (:status response) (:body response))
            (System/exit 1))))
    (catch Exception e
      (println "Failed to connect to Linear API:" (.getMessage e))
      (System/exit 1))))

(def assigned-issues-query
  (slurp (clojure.java.io/resource "assigned-issues.graphql")))

(defn assigned-issues [api-key]
  (let [result (query-linear! api-key assigned-issues-query)
        issues (get-in result [:data :viewer :assignedIssues :nodes])]
    issues))

(defn sync-todoist! [api-key & [{:keys [method body]}]]
  (try
    (let [response (http/request
                    {:uri "https://api.todoist.com/sync/v9/sync"
                     :method (or method :post)
                     :headers {"Authorization" (str "Bearer " api-key)
                               "Content-Type" "application/json"}
                     :body (when body (json/generate-string body))})]
      (if (< (:status response) 400)
        (json/parse-string (:body response) true)
        (do (println "Todoist API error:" (:status response) (:body response))
            (System/exit 1))))
    (catch Exception e
      (println "Failed to connect to Todoist API:" (.getMessage e))
      (System/exit 1))))

(defn fetch-rest-todoist! [api-key endpoint]
  (let [response (http/get (str "https://api.todoist.com/rest/v2/" endpoint)
                          {:headers {"Authorization" (str "Bearer " api-key)}})]
    (json/parse-string (:body response) true)))

(defn fetch-todoist-items! [api-key]
  (let [result (sync-todoist! api-key
                             {:body {:sync_token "*" :resource_types ["items"]}})
        items (get result :items)]
    items))

(defn fetch-todoist-labels! [api-key]
  (fetch-rest-todoist! api-key "labels"))

(defn fetch-todoist-projects! [api-key]
  (fetch-rest-todoist! api-key "projects"))

(defn find-project-id [projects project-name]
  (when project-name
    (:id (first (filter #(= (:name %) project-name) projects)))))

(defn add-item-command [content description priority labels & [{:keys [parent-id project-id]}]]
  {:type "item_add"
   :temp_id (str (random-uuid))
   :uuid (str (random-uuid))
   :args (cond-> {:content content
                  :labels (concat ["from-linear"] labels)
                  :priority priority}
           description (assoc :description description)
           parent-id (assoc :parent_id parent-id)
           project-id (assoc :project_id project-id))})

(defn complete-item-command [item-id]
  {:type "item_complete"
   :uuid (str (random-uuid))
   :args {:id item-id}})

(defn update-item-command [item-id content]
  {:type "item_update"
   :uuid (str (random-uuid))
   :args {:id item-id :content content}})

(defn add-labels-command [item-id labels]
  {:type "item_update"
   :uuid (str (random-uuid))
   :args {:id item-id :labels labels}})

(defn move-item-command [item-id project-id]
  {:type "item_move"
   :uuid (str (random-uuid))
   :args {:id item-id :project_id project-id}})

(defn execute-todoist-commands! [api-key commands]
  (when (seq commands)
    (sync-todoist! api-key {:body {:commands commands}})))

(defn completed-issue? [issue]
  (let [state-type (get-in issue [:state :type])
        archived-at (get issue :archivedAt)]
    (or (case state-type
          ("completed" "canceled") true
          false)
        (some? archived-at))))

(defn in-current-cycle? [issue]
  (let [cycle (get issue :cycle)]
    (and cycle
         (some? (get cycle :startsAt))
         (some? (get cycle :endsAt)))))

(defn linear-priority->todoist-priority [issue]
  (let [priority (get issue :priority)
        base-priority (case priority
                       0 1  ; None -> p4 (normal/default)
                       1 4  ; Urgent -> p1 (very urgent) 
                       2 3  ; High -> p2
                       3 2  ; Normal -> p3
                       4 1  ; Low -> p4
                       1)]  ; Default fallback
    (min 4
         (if (in-current-cycle? issue)
           (inc base-priority)
           base-priority))))

(defn task-content [issue]
  (get issue :title))

(defn task-description [issue]
  (let [description (get issue :description)
        url (get issue :url)
        branch-name (get issue :branchName)
        linear-id (get issue :id)
        priority-label (get issue :priorityLabel)
        cycle (get issue :cycle)
        
        linear-info [(when url (str "Linear Link: " url))
                     (when branch-name (str "Linear Branch: " branch-name))
                     (when priority-label (str "Linear Priority: " priority-label))
                     (when cycle (str "Linear Cycle: " (get cycle :name)))
                     (str "Linear ID: " linear-id)]
        
        metadata-section (->> linear-info
                             (filter some?)
                             (str/join "\n"))]
    
    (if description
      (str description "\n\n" metadata-section)
      metadata-section)))

(defn extract-issue-id [task-description]
  (when-let [match (re-find #"Linear ID: ([a-f0-9-]+)" (or task-description ""))]
    (second match)))

(defn matching-task [issue tasks]
  (let [issue-id (get issue :id)]
    (first (filter #(= issue-id (extract-issue-id (get % :description))) tasks))))

;; Issue expansion to include sub-issues
(defn expand-issues [issues]
  (mapcat
   (fn [issue]
     (let [sub-issues (get-in issue [:children :nodes])
           main-issue (dissoc issue :children)]
       (if (seq sub-issues)
         (cons main-issue 
               (map #(assoc % :parent-issue main-issue) sub-issues))
         [main-issue])))
   issues))

(defn issue-sync-commands [issue tasks additional-labels project-id]
  (let [existing-task (matching-task issue tasks)
        issue-completed? (completed-issue? issue)
        expected-content (task-content issue)
        priority (linear-priority->todoist-priority issue)
        parent-task (when (get issue :parent-issue)
                     (matching-task (get issue :parent-issue) tasks))]
    (cond
      ;; Create new task (only if issue is not completed)
      (and (nil? existing-task) (not issue-completed?))
      [(add-item-command expected-content 
                        (task-description issue)
                        priority
                        additional-labels
                        (cond-> {}
                          parent-task (assoc :parent-id (get parent-task :id))
                          project-id (assoc :project-id project-id)))]
      
      ;; Skip if task doesn't exist but issue is already completed
      (and (nil? existing-task) issue-completed?)
      []
      
      ;; Complete task if issue is completed but task isn't
      (and issue-completed? (not (get existing-task :checked)))
      [(complete-item-command (get existing-task :id))]
      
      ;; Update task content if changed
      (not= expected-content (get existing-task :content))
      [(update-item-command (get existing-task :id) expected-content)]
      
      ;; No changes needed
      :else
      [])))

(defn reassignment-commands [issues tasks]
  (let [current-issue-ids (set (map :id issues))
        reassigned-tasks (->> tasks
                             (filter #(extract-issue-id (:description %)))
                             (remove #(contains? current-issue-ids 
                                               (extract-issue-id (:description %))))
                             (remove :checked))]
    (mapcat (fn [task]
              [(update-item-command (:id task) 
                                  (str "REASSIGNED: " (:content task)))
               (complete-item-command (:id task))])
            reassigned-tasks)))

;; Sync logic (pure functions)
(defn sync-commands [issues tasks config project-id]
  (let [expanded-issues (expand-issues issues)
        additional-labels (get-in config [:todoist :additional-labels] [])
        issue-cmds (mapcat #(issue-sync-commands % tasks additional-labels project-id) expanded-issues)
        reassignment-cmds (reassignment-commands issues tasks)]
    (concat issue-cmds reassignment-cmds)))

(defn llm-label-commands [tasks issues {:keys [llm] :as config} & [verbose? work-dir]]
  (if-not (:enabled llm)
    []
    (let [{:keys [labels-to-add]} llm
          issues-by-id (zipmap (map :id issues) issues)
          llm-cache (load-llm-cache work-dir)
          eligible-tasks (->> tasks
                             (filter #(not (:checked %)))
                             (filter #(extract-issue-id (:description %)))
                             (remove #(when-let [issue-id (extract-issue-id (:description %))]
                                       (when-let [issue (get issues-by-id issue-id)]
                                         (completed-issue? issue))))
                             (remove #(some (set (:labels %)) labels-to-add))
                             (remove #(contains? llm-cache (:id %))))]
      (when verbose? (println "Evaluating" (count eligible-tasks) "tasks with LLM..."))
      (loop [remaining eligible-tasks
             processed 0
             commands []
             cache llm-cache]
        (if (empty? remaining)
          (do (save-llm-cache! cache work-dir)
              commands)
          (let [task (first remaining)
                task-id (:id task)
                task-title (subs (:content task) 0 (min 50 (count (:content task))))
                progress-str (str "[" (inc processed) "/" (count eligible-tasks) "]")]
            (print (str progress-str " " task-title "... "))
            (flush)
            (if (evaluate-task-with-llm task llm)
              (do (println "✓" (str/join "/" labels-to-add))
                  (recur (rest remaining) 
                         (inc processed)
                         (conj commands (add-labels-command task-id 
                                                           (vec (concat (:labels task) labels-to-add))))
                         (conj cache task-id)))
              (do (println "✗ skip")
                  (recur (rest remaining) (inc processed) commands (conj cache task-id))))))))))

(defn show-help
  [spec]
  (cli/format-opts (merge spec {:order (vec (keys (:spec spec)))})))

(defn run-sync! [opts]
  (let [skip-llm? (:skip-llm opts)
        dry-run? (:dry-run opts)
        verbose? (:verbose opts)
        work-dir (:work-dir opts)
        secrets-config (secrets work-dir)
        app-config (config work-dir)
        {:keys [linear todoist]} secrets-config
        issues (assigned-issues (:api-key linear))
        tasks (fetch-todoist-items! (:api-key todoist))
        projects (fetch-todoist-projects! (:api-key todoist))
        project-id (find-project-id projects (get-in app-config [:todoist :project-name]))
        sync-cmds (sync-commands issues tasks app-config project-id)
        llm-cmds (if (or skip-llm? dry-run?) [] (llm-label-commands tasks issues app-config verbose? work-dir))
        commands (concat sync-cmds llm-cmds)]
    
    (when verbose?
      (println "Found" (count issues) "Linear issues")
      (println "Found" (count tasks) "Todoist tasks"))
    (if skip-llm?
      (println "Generated" (count commands) "sync commands (LLM skipped)")
      (println "Generated" (count commands) "total commands"))
    
    (if (seq commands)
      (if dry-run?
        (do (println "\nDry run - would execute" (count commands) "commands:")
            (doseq [cmd commands]
              (println " " (:type cmd) (:args cmd))))
        (do (execute-todoist-commands! (:api-key todoist) commands)
            (println "Sync completed!")))
      (println "No changes needed."))))

(defn move-tagged-tasks-to-project! [opts]
  (let [dry-run? (:dry-run opts)
        verbose? (:verbose opts)
        work-dir (:work-dir opts)
        secrets-config (secrets work-dir)
        app-config (config work-dir)
        {:keys [todoist]} secrets-config
        tasks (fetch-todoist-items! (:api-key todoist))
        projects (fetch-todoist-projects! (:api-key todoist))
        project-name (get-in app-config [:todoist :project-name])
        project-id (find-project-id projects project-name)]
    
    (if-not project-id
      (println "Error: Project" (str "\"" project-name "\"") "not found in Todoist")
      (let [tagged-tasks (->> tasks
                             (filter #(some #{"from-linear"} (:labels %)))
                             (remove :checked))
            move-commands (map #(move-item-command (:id %) project-id) tagged-tasks)]
        
        (when verbose?
          (println "Found" (count tagged-tasks) "tasks with 'from-linear' label")
          (println "Target project:" project-name "(" project-id ")"))
        
        (if (seq move-commands)
          (if dry-run?
            (do (println "Dry run - would move" (count move-commands) "tasks to" project-name)
                (when verbose?
                  (doseq [task tagged-tasks]
                    (println " " (:content task)))))
            (do (execute-todoist-commands! (:api-key todoist) move-commands)
                (println "Moved" (count move-commands) "tasks to" project-name)))
          (println "No tagged tasks found to move."))))))

(defn -main [& args]
  (let [spec {:spec {:skip-llm {:desc "Skip LLM processing"}
                     :dry-run {:desc "Show what would be done without executing"}
                     :verbose {:desc "Show detailed output"}
                     :work-dir {:desc "Working directory for config files"}
                     :help {:desc "Show this help"}}}
        opts (if (and (= 1 (count args)) (map? (first args)))
               (first args) ; Already parsed by babashka
               (cli/parse-opts args spec))]
    (if (:help opts)
      (do (println "Linear to Todoist Sync")
          (println)
          (println "Usage: bb sync [options]")
          (println)
          (println (show-help spec)))
      (run-sync! opts))))
