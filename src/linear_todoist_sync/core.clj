(ns linear-todoist-sync.core
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [com.wsscode.pathom3-graphql.connect :as p.gql]
            [clojure.string :as str]))

;; Configuration
(defn secrets []
  (-> "secrets.edn" slurp read-string))

;; Linear GraphQL Integration
(defn linear-request-fn [api-key]
  (fn [_env query]
    (let [response (http/post "https://api.linear.app/graphql"
                             {:headers {"Authorization" (str "Bearer " api-key)
                                       "Content-Type" "application/json"}
                              :body (json/generate-string {:query query})})]
      (json/parse-string (:body response) true))))

(defn linear-env [api-key]
  (-> {} (p.gql/connect-graphql {::p.gql/namespace "linear"} (linear-request-fn api-key))))

(defn assigned-issues [env]
  (let [data (p.eql/process env
                           [{:linear.Query/viewer
                             [{:assignedIssues
                               [:id :title :description :url :createdAt :archivedAt
                                {:state [:name :type]}
                                {:children [:id :title :description :url :createdAt :archivedAt
                                           {:state [:name :type]}]}]}]}])]
    (get-in data [:linear.Query/viewer :assignedIssues])))

;; Todoist API Integration
(defn sync-todoist! [api-key endpoint & [{:keys [method body]}]]
  (let [response (http/request
                  {:url (str "https://api.todoist.com/api/v1/" endpoint)
                   :method (or method :get)
                   :headers {"Authorization" (str "Bearer " api-key)
                             "Content-Type" "application/json"}
                   :body (when body (json/generate-string body))})]
    (json/parse-string (:body response) true)))

(defn fetch-todoist-items! [api-key]
  (:items (sync-todoist! api-key "sync"
                        {:method :post
                         :body {:sync_token "*" :resource_types ["items"]}})))

(defn fetch-todoist-labels! [api-key]
  (sync-todoist! api-key "labels"))

;; Todoist command builders (pure functions)
(defn from-linear-label-id [labels]
  (:id (first (filter #(= "@from-linear" (:name %)) labels))))

(defn add-item-command [content labels & [{:keys [parent-id]}]]
  (let [label-id (from-linear-label-id labels)]
    {:type "item_add"
     :temp_id (str (random-uuid))
     :uuid (str (random-uuid))
     :args (cond-> {:content content}
             label-id (assoc :labels [label-id])
             parent-id (assoc :parent_id parent-id))}))

(defn complete-item-command [item-id]
  {:type "item_complete"
   :uuid (str (random-uuid))
   :args {:id item-id}})

(defn update-item-command [item-id content]
  {:type "item_update"
   :uuid (str (random-uuid))
   :args {:id item-id :content content}})

(defn add-label-command [name]
  {:type "label_add"
   :temp_id (str (random-uuid))
   :uuid (str (random-uuid))
   :args {:name name}})

;; Execute Todoist commands (side effect)
(defn execute-todoist-commands! [api-key commands]
  (when (seq commands)
    (sync-todoist! api-key "sync"
                  {:method :post
                   :body {:commands commands}})))

(defn ensure-from-linear-label! [api-key]
  (let [labels (fetch-todoist-labels! api-key)
        has-label? (some #(= "@from-linear" (:name %)) labels)]
    (when-not has-label?
      (execute-todoist-commands! api-key [(add-label-command "@from-linear")]))
    (fetch-todoist-labels! api-key)))

;; Issue predicates and utilities
(defn completed-issue? [issue]
  (or (= "completed" (get-in issue [:state :type]))
      (some? (:archivedAt issue))))

(defn task-content [issue]
  (str (:title issue) " [" (:id issue) "]"))

(defn extract-issue-id [task-content]
  (when-let [match (re-find #"\[([^\]]+)\]$" task-content)]
    (second match)))

(defn matching-task [issue tasks]
  (let [issue-id (:id issue)]
    (first (filter #(= issue-id (extract-issue-id (:content %))) tasks))))

;; Issue expansion to include sub-issues
(defn expand-issues [issues]
  (mapcat
   (fn [issue]
     (let [sub-issues (:children issue)
           main-issue (dissoc issue :children)]
       (if (seq sub-issues)
         (cons main-issue 
               (map #(assoc % :parent-issue main-issue) sub-issues))
         [main-issue])))
   issues))

;; Sync logic (pure functions)
(defn sync-commands [issues tasks labels]
  (let [expanded-issues (expand-issues issues)]
    (mapcat
     (fn [issue]
       (let [existing-task (matching-task issue tasks)
             issue-completed? (completed-issue? issue)
             expected-content (task-content issue)
             parent-task (when (:parent-issue issue)
                          (matching-task (:parent-issue issue) tasks))]
         (cond
           ;; Create new task
           (nil? existing-task)
           [(add-item-command expected-content labels 
                             (when parent-task {:parent-id (:id parent-task)}))]
           
           ;; Complete task if issue is completed but task isn't
           (and issue-completed? (not (:checked existing-task)))
           [(complete-item-command (:id existing-task))]
           
           ;; Update task content if changed
           (not= expected-content (:content existing-task))
           [(update-item-command (:id existing-task) expected-content)]
           
           ;; No changes needed
           :else
           [])))
     expanded-issues)))

;; Main orchestration
(defn run-sync! []
  (let [{:keys [linear todoist]} (secrets)
        env (linear-env (:api-key linear))
        issues (assigned-issues env)
        tasks (fetch-todoist-items! (:api-key todoist))
        labels (ensure-from-linear-label! (:api-key todoist))
        commands (sync-commands issues tasks labels)]
    
    (if (seq commands)
      (do
        (println "Executing" (count commands) "commands...")
        (execute-todoist-commands! (:api-key todoist) commands)
        (println "Sync completed!"))
      (println "No changes needed."))))

(defn -main [& args]
  (run-sync!))