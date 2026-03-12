(ns linear-todoist-sync.core
  (:require [babashka.http-client :as http]
            [babashka.cli :as cli]
            [babashka.pods :as pods]
            [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.server :as server]))

(def ^:private bold "\033[1m")
(def ^:private red "\033[31m")
(def ^:private green "\033[32m")
(def ^:private cyan "\033[36m")
(def ^:private grey "\033[90m")
(def ^:private reset "\033[0m")

(defn- err! [& args]
  (.println *err* (str red (apply str args) reset)))

(defn- send-telegram! [message]
  (when-let [bot-token (not-empty (System/getenv "telegram_bot_token"))]
    (when-let [chat-id (not-empty (System/getenv "telegram_chat_id"))]
      (try
        (let [resp (http/post (str "https://api.telegram.org/bot" bot-token "/sendMessage")
                              {:headers {"Content-Type" "application/json"}
                               :body (json/generate-string {:chat_id (parse-long chat-id) :text message})
                               :throw false})]
          (when (>= (:status resp) 400)
            (err! "Telegram notification failed: " (:status resp) " " (:body resp))))
        (catch Exception e
          (err! "Telegram notification failed: " (.getMessage e)))))))

(defn- parse-query-params [query-string]
  (->> (str/split (or query-string "") #"&")
       (remove str/blank?)
       (map #(str/split % #"=" 2))
       (filter #(= 2 (count %)))
       (into {})))

(defn- exchange-code! [{:keys [token-url client-id client-secret extra-params token-key]} code redirect-uri]
  (let [body (merge {:client_id client-id
                     :client_secret client-secret
                     :code code
                     :redirect_uri redirect-uri}
                    extra-params)
        response (http/post token-url
                            {:headers {"Content-Type" "application/x-www-form-urlencoded"}
                             :body (->> body
                                        (map (fn [[k v]] (str (name k) "=" (java.net.URLEncoder/encode (str v) "UTF-8"))))
                                        (str/join "&"))
                             :throw false})
        token (when (= 200 (:status response))
                (get (json/parse-string (:body response) true) token-key))]
    (when-not token
      (err! "Token exchange failed (" (:status response) "): " (:body response))
      (System/exit 1))
    token))

(defn- oauth! [{:keys [name auth-url scope client-id client-secret] :as provider}]
  (let [state (str (random-uuid))
        result (promise)
        port (parse-long (or (System/getenv "PORT") "8910"))
        redirect-uri (if-let [hostname (System/getenv "TOWER__HOSTNAME")]
                       (str "https://" hostname ".apps.tower.dev/callback")
                       (str "http://localhost:" port "/callback"))
        handler (fn [{:keys [uri query-string]}]
                  (if-not (str/starts-with? uri "/callback")
                    {:status 404 :body "Not found"}
                    (let [params (parse-query-params query-string)
                          error (or (get params "error")
                                    (when (not= (get params "state") state) "State mismatch"))]
                      (if error
                        (do (deliver result {:error error})
                            {:status 400 :body "Authorization failed. You can close this tab."})
                        (do (deliver result {:code (get params "code")})
                            {:status 200 :body "Authorization successful! You can close this tab."})))))
        srv (server/run-server handler {:port port})
        auth-link (str auth-url
                       "?client_id=" client-id
                       "&scope=" scope
                       "&state=" state
                       "&response_type=code"
                       "&redirect_uri=" redirect-uri)]
    (println (str "Open this URL to authorize " name ":"))
    (println (str bold auth-link reset))
    (send-telegram! (str name " token expired. Re-authenticate:\n" auth-link))
    (println "\nWaiting for authorization...")
    (let [cb (deref result 300000 nil)]
      (srv)
      (when-not cb
        (err! "OAuth timed out after 5 minutes.")
        (System/exit 1))
      (when (:error cb)
        (err! "OAuth failed: " (:error cb))
        (System/exit 1))
      (exchange-code! provider (:code cb) redirect-uri))))


(defn- persist-token! [secret-name env-var token]
  (try
    (pods/load-pod ["python3" "pod_tower.py"])
    (require '[pod.tower :as tower])
    (let [describe-key (resolve 'pod.tower/describe-secrets-key)
          encrypt (resolve 'pod.tower/encrypt-secret)
          preview-fn (resolve 'pod.tower/secret-preview)
          create (resolve 'pod.tower/create-secret)
          update (resolve 'pod.tower/update-secret)
          key-info (describe-key :format "spki" :environment "default")
          public-key (:public-key key-info)
          encrypted (encrypt public-key token)
          preview (preview-fn token)
          secret-opts {:name secret-name :encrypted-value encrypted :preview preview :environment "default"}]
      (try
        (create secret-opts)
        (catch Exception _
          (update secret-opts)))
      (println "Token persisted to Tower secrets."))
    (catch Exception e
      (err! "Warning: Could not persist token to Tower: " (.getMessage e))
      (err! "Set $" env-var " manually for future runs."))))

(def ^:private todoist-provider
  {:name "Todoist"
   :auth-url "https://todoist.com/oauth/authorize"
   :scope "data:read_write"
   :token-url "https://todoist.com/oauth/access_token"
   :token-key :access_token
   :secret-name "todoist_api_key"
   :api-key-env "todoist_api_key"
   :client-id-env "todoist_client_id"
   :client-secret-env "todoist_client_secret"})

(def ^:private linear-provider
  {:name "Linear"
   :auth-url "https://linear.app/oauth/authorize"
   :scope "read"
   :token-url "https://api.linear.app/oauth/token"
   :token-key :access_token
   :extra-params {:grant_type "authorization_code"}
   :secret-name "linear_api_key"
   :api-key-env "linear_api_key"
   :client-id-env "linear_client_id"
   :client-secret-env "linear_client_secret"})

(defn- do-oauth! [{:keys [client-id-env client-secret-env secret-name api-key-env] :as provider}]
  (let [client-id (not-empty (System/getenv client-id-env))
        client-secret (not-empty (System/getenv client-secret-env))]
    (when (and client-id client-secret)
      (let [token (oauth! (assoc provider :client-id client-id :client-secret client-secret))]
        (persist-token! secret-name api-key-env token)
        token))))

(defn- get-or-oauth! [{:keys [api-key-env] :as provider}]
  (or (not-empty (System/getenv api-key-env))
      (do-oauth! provider)))

(defn secrets [& [_work-dir]]
  (let [todoist-key (get-or-oauth! todoist-provider)
        linear-key (get-or-oauth! linear-provider)]
    (when-not (and todoist-key linear-key)
      (err! "Missing credentials.")
      (err! "Set $todoist_api_key and $linear_api_key env vars,")
      (err! "or set OAuth client credentials ($todoist_client_id/$todoist_client_secret, $linear_client_id/$linear_client_secret).")
      (System/exit 1))
    {:todoist {:api-key todoist-key}
     :linear {:api-key linear-key}}))

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
    (let [payload (merge {:stream false
                          :model model
                          :messages [{:role "user" :content prompt}]}
                         opts)
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

(defn evaluate-task-with-llm [task issue {:keys [base-url model prompt] :as llm-config}]
  (let [sub-issue-count (count (get-in issue [:children :nodes]))
        context-note (when (pos? sub-issue-count)
                       (str "IMPORTANT: This is a parent issue with " sub-issue-count " sub-issues - it's an epic/project, not an individual task.\n\n"))
        filled-prompt (-> prompt
                        (str/replace "{task-content}" (:content task ""))
                        (str/replace "{task-description}" (str (or context-note "") (:description task ""))))]
    (let [reasoning (llm-request! base-url model filled-prompt
                               {:max_tokens 200 :temperature 0.1})
        json-prompt (str reasoning "\n\nAnswer with JSON: {\"answer_is_yes\": true/false, \"reason\": \"brief explanation\"}")
        json-schema {:type "object"
                     :properties {:answer_is_yes {:type "boolean"}
                                  :reason {:type "string"}}
                     :required ["answer_is_yes"]}
        result (llm-request! base-url model json-prompt
                            {:max_tokens 100
                             :temperature 0.0
                             :response_format {:type "json_schema"
                                              :json_schema {:name "task_evaluation"
                                                           :strict true
                                                           :schema json-schema}}})]

      (when (and reasoning result)
        (try
          (let [parsed (json/parse-string result true)]
            {:is-labelled (:answer_is_yes parsed)
             :reason (:reason parsed "")})
          (catch Exception _
            {:is-labelled (str/includes? (str/lower-case result) "true")
             :reason ""}))))))

(defn- auth-error? [status]
  (contains? #{401 403} status))

;; Linear GraphQL Integration
(defn query-linear! [api-key query]
  (try
    (let [response (http/post "https://api.linear.app/graphql"
                             {:headers {"Authorization" api-key
                                       "Content-Type" "application/json"}
                              :body (json/generate-string {:query query})})]
      (if (= 200 (:status response))
        (json/parse-string (:body response) true)
        (if (auth-error? (:status response))
          (throw (ex-info "Linear auth expired" {:type :auth-expired :provider :linear :status (:status response)}))
          (do (err! "Linear API error: " (:status response) " " (:body response))
              (System/exit 1)))))
    (catch clojure.lang.ExceptionInfo e (throw e))
    (catch Exception e
      (err! "Failed to connect to Linear API: " (.getMessage e))
      (System/exit 1))))

(def assigned-issues-query
  (slurp (clojure.java.io/resource "assigned-issues.graphql")))

(defn assigned-issues [api-key]
  (let [result (query-linear! api-key assigned-issues-query)
        issues (get-in result [:data :viewer :assignedIssues :nodes])]
    issues))

(defn sync-todoist! [api-key & [{:keys [params]}]]
  (try
    (let [encoded (when params
                    (reduce-kv (fn [m k v]
                                 (assoc m k (if (or (sequential? v) (map? v))
                                              (json/generate-string v)
                                              v)))
                               {} params))
          response (http/post "https://api.todoist.com/api/v1/sync"
                              {:headers {"Authorization" (str "Bearer " api-key)}
                               :form encoded})]
      (if (< (:status response) 400)
        (json/parse-string (:body response) true)
        (if (auth-error? (:status response))
          (throw (ex-info "Todoist auth expired" {:type :auth-expired :provider :todoist :status (:status response)}))
          (do (err! "Todoist API error: " (:status response) " " (:body response))
              (System/exit 1)))))
    (catch clojure.lang.ExceptionInfo e (throw e))
    (catch Exception e
      (err! "Failed to connect to Todoist API: " (.getMessage e))
      (System/exit 1))))

(defn fetch-rest-todoist! [api-key endpoint]
  (let [response (http/get (str "https://api.todoist.com/api/v1/" endpoint)
                          {:headers {"Authorization" (str "Bearer " api-key)}
                           :throw false})
        status (:status response)]
    (cond
      (auth-error? status)
      (throw (ex-info "Todoist auth expired" {:type :auth-expired :provider :todoist :status status}))
      (>= status 400)
      (do (err! "Todoist API error: " status " " (:body response))
          (System/exit 1))
      :else
      (json/parse-string (:body response) true))))

(defn fetch-todoist-items! [api-key]
  (let [result (sync-todoist! api-key {:params {:sync_token "*" :resource_types ["items"]}})
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
    (sync-todoist! api-key {:params {:commands commands}})))

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
  (let [additional-labels (get-in config [:todoist :additional-labels] [])
        issue-cmds (mapcat #(issue-sync-commands % tasks additional-labels project-id) issues)
        reassignment-cmds (reassignment-commands issues tasks)]
    (concat issue-cmds reassignment-cmds)))

(defn format-evaluation-result [is-labelled? labels reason]
  (let [status (if is-labelled?
                 (str green "✓ " (str/join "/" labels) reset)
                 (str red "✗ skip" reset))
        reason-str (when (seq reason)
                     (str "\n  " grey reason reset))]
    (str status reason-str)))

(defn llm-label-commands [tasks issues raw-issues {:keys [llm] :as config} & [verbose? work-dir]]
  (if-not (:enabled llm)
    []
    (let [{:keys [labels-to-add]} llm
          issues-by-id (zipmap (map :id issues) issues)
          raw-issues-by-id (zipmap (map :id raw-issues) raw-issues)
          llm-cache (load-llm-cache work-dir)
          eligible-tasks (->> tasks
                             (filter (complement :checked))
                             (filter (comp extract-issue-id :description))
                             (remove (fn [task]
                                       (some-> task :description extract-issue-id issues-by-id completed-issue?)))
                             (remove #(some (set (:labels %)) labels-to-add))
                             (remove #(contains? llm-cache (:id %)))
                             (group-by (comp extract-issue-id :description))
                             vals
                             (map first))]
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
                issue-id (extract-issue-id (:description task))
                raw-issue (get raw-issues-by-id issue-id)
                task-title (subs (:content task) 0 (min 50 (count (:content task))))
                progress-str (str cyan "[" (inc processed) "/" (count eligible-tasks) "]" reset)
                evaluation (evaluate-task-with-llm task raw-issue llm)]
            (print (str progress-str " " task-title "... "))
            (flush)
            (println (format-evaluation-result (:is-labelled evaluation) labels-to-add (:reason evaluation)))
            (if (:is-labelled evaluation)
              (recur (rest remaining)
                     (inc processed)
                     (conj commands (add-labels-command task-id
                                                       (vec (concat (:labels task) labels-to-add))))
                     (conj cache task-id))
              (recur (rest remaining) (inc processed) commands (conj cache task-id)))))))))

(defn show-help
  [spec]
  (cli/format-opts (merge spec {:order (vec (keys (:spec spec)))})))

(defn- reauth-provider! [provider-key]
  (let [provider (case provider-key
                   :todoist todoist-provider
                   :linear linear-provider)]
    (println (str "\n" (:name provider) " token expired. Re-authenticating..."))
    (or (do-oauth! provider)
        (do (err! (:name provider) " token expired and no OAuth credentials available to re-authenticate.")
            (err! "Set $" (:client-id-env provider) " and $" (:client-secret-env provider) ", or run `bb auth`.")
            (System/exit 1)))))

(defn- with-reauth [secrets-config body-fn]
  (loop [current-secrets secrets-config
         retried #{}]
    (let [result (try
                   {:ok (body-fn current-secrets)}
                   (catch clojure.lang.ExceptionInfo e
                     (if (= :auth-expired (:type (ex-data e)))
                       {:auth-expired (:provider (ex-data e))}
                       (throw e))))]
      (if-let [provider-key (:auth-expired result)]
        (if (contains? retried provider-key)
          (throw (ex-info (str "Re-authentication failed for " (name provider-key)) {:provider provider-key}))
          (let [new-token (reauth-provider! provider-key)]
            (recur (assoc-in current-secrets [provider-key :api-key] new-token)
                   (conj retried provider-key))))
        (:ok result)))))

(defn run-sync! [opts]
  (let [skip-llm? (:skip-llm opts)
        dry-run? (:dry-run opts)
        verbose? (:verbose opts)
        work-dir (:work-dir opts)
        secrets-config (secrets work-dir)
        app-config (config work-dir)]
    (with-reauth secrets-config
      (fn [{:keys [linear todoist]}]
        (let [raw-issues (assigned-issues (:api-key linear))
              issues (expand-issues raw-issues)
              tasks (fetch-todoist-items! (:api-key todoist))
              projects (fetch-todoist-projects! (:api-key todoist))
              project-id (find-project-id projects (get-in app-config [:todoist :project-name]))
              sync-cmds (sync-commands issues tasks app-config project-id)
              llm-cmds (if (or skip-llm? dry-run?) [] (llm-label-commands tasks issues raw-issues app-config verbose? work-dir))
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
            (println "No changes needed.")))))))

(defn move-tagged-tasks-to-project! [opts]
  (let [dry-run? (:dry-run opts)
        verbose? (:verbose opts)
        work-dir (:work-dir opts)
        secrets-config (secrets work-dir)
        app-config (config work-dir)]
    (with-reauth secrets-config
      (fn [{:keys [todoist]}]
        (let [tasks (fetch-todoist-items! (:api-key todoist))
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
                (println "No tagged tasks found to move.")))))))))

(defn run-auth! [& _args]
  (let [providers (->> [todoist-provider linear-provider]
                       (filter (fn [{:keys [client-id-env client-secret-env]}]
                                 (and (not-empty (System/getenv client-id-env))
                                      (not-empty (System/getenv client-secret-env))))))]
    (when (empty? providers)
      (err! "No OAuth credentials found.")
      (err! "Set $todoist_client_id/$todoist_client_secret or $linear_client_id/$linear_client_secret.")
      (System/exit 1))
    (doseq [{:keys [client-id-env client-secret-env secret-name api-key-env] :as provider} providers]
      (let [token (oauth! (assoc provider
                                 :client-id (System/getenv client-id-env)
                                 :client-secret (System/getenv client-secret-env)))]
        (persist-token! secret-name api-key-env token)))))

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
