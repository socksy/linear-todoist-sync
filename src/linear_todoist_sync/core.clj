(ns linear-todoist-sync.core
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; Configuration
(defn secrets []
  (-> "secrets.edn" slurp read-string))

;; Linear GraphQL Integration
(defn query-linear! [api-key query]
  (let [response (http/post "https://api.linear.app/graphql"
                           {:headers {"Authorization" api-key
                                     "Content-Type" "application/json"}
                            :body (json/generate-string {:query query})})]
    (json/parse-string (:body response) true)))

(defn assigned-issues [api-key]
  (let [query "query {
                 viewer {
                   assignedIssues(first: 100) {
                     nodes {
                       id
                       title
                       description
                       url
                       createdAt
                       archivedAt
                       priority
                       priorityLabel
                       branchName
                       state {
                         name
                         type
                       }
                       cycle {
                         id
                         name
                         startsAt
                         endsAt
                       }
                       children {
                         nodes {
                           id
                           title
                           description
                           url
                           createdAt
                           archivedAt
                           priority
                           priorityLabel
                           branchName
                           state {
                             name
                             type
                           }
                           cycle {
                             id
                             name
                             startsAt
                             endsAt
                           }
                         }
                       }
                     }
                   }
                 }
               }"
        result (query-linear! api-key query)
        issues (get-in result [:data :viewer :assignedIssues :nodes])]
    (println "Linear query returned" (count issues) "issues")
    issues))

;; Todoist API Integration
(defn sync-todoist! [api-key & [{:keys [method body]}]]
  (let [response (http/request
                  {:uri "https://api.todoist.com/sync/v9/sync"
                   :method (or method :post)
                   :headers {"Authorization" (str "Bearer " api-key)
                             "Content-Type" "application/json"}
                   :body (when body (json/generate-string body))})]
    (json/parse-string (:body response) true)))

(defn fetch-rest-todoist! [api-key endpoint]
  (let [response (http/get (str "https://api.todoist.com/rest/v2/" endpoint)
                          {:headers {"Authorization" (str "Bearer " api-key)}})]
    (json/parse-string (:body response) true)))

(defn fetch-todoist-items! [api-key]
  (let [result (sync-todoist! api-key
                             {:body {:sync_token "*" :resource_types ["items"]}})
        items (get result :items)]
    (println "Todoist returned" (count items) "items")
    items))

(defn fetch-todoist-labels! [api-key]
  (fetch-rest-todoist! api-key "labels"))

;; Todoist command builders (pure functions)
(defn add-item-command [content description priority labels & [{:keys [parent-id]}]]
  {:type "item_add"
   :temp_id (str (random-uuid))
   :uuid (str (random-uuid))
   :args (cond-> {:content content
                  :labels (concat ["from-linear"] labels)
                  :priority priority}
           description (assoc :description description)
           parent-id (assoc :parent_id parent-id))})

(defn complete-item-command [item-id]
  {:type "item_complete"
   :uuid (str (random-uuid))
   :args {:id item-id}})

(defn update-item-command [item-id content]
  {:type "item_update"
   :uuid (str (random-uuid))
   :args {:id item-id :content content}})

;; Execute Todoist commands (side effect)
(defn execute-todoist-commands! [api-key commands]
  (when (seq commands)
    (sync-todoist! api-key {:body {:commands commands}})))

;; Issue predicates and utilities
(defn completed-issue? [issue]
  (or (= "completed" (get-in issue [:state :type]))
      (some? (get issue :archivedAt))))

(defn in-current-cycle? [issue]
  (let [cycle (get issue :cycle)]
    (and cycle
         (some? (get cycle :startsAt))
         (some? (get cycle :endsAt)))))

(defn linear-priority->todoist-priority [issue]
  (let [priority (get issue :priority)
        base-priority (case priority
                       0 1  ; No priority -> p4 (lowest)
                       1 2  ; Urgent -> p3
                       2 3  ; High -> p2
                       3 4  ; Medium -> p1 (highest in Todoist)
                       1)]  ; Default fallback
    (min 4 (+ base-priority (if (in-current-cycle? issue) 1 0)))))

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

;; Sync logic (pure functions)
(defn sync-commands [issues tasks config]
  (let [expanded-issues (expand-issues issues)
        additional-labels (get-in config [:config :additional-labels] [])]
    (mapcat
     (fn [issue]
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
                             (when parent-task {:parent-id (get parent-task :id)}))]
           
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
     expanded-issues)))

;; Main orchestration
(defn run-sync! []
  (let [config (secrets)
        {:keys [linear todoist]} config
        issues (assigned-issues (:api-key linear))
        tasks (fetch-todoist-items! (:api-key todoist))
        commands (sync-commands issues tasks config)]
    
    (println "Found" (count issues) "Linear issues")
    (println "Found" (count tasks) "Todoist tasks")
    (println "Generated" (count commands) "commands")
    
    (if (seq commands)
      (do
        (println "Executing" (count commands) "commands...")
        (execute-todoist-commands! (:api-key todoist) commands)
        (println "Sync completed!"))
      (println "No changes needed."))))

(defn -main [& args]
  (run-sync!))