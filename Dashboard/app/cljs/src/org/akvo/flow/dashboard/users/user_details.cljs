(ns org.akvo.flow.dashboard.users.user-details
  (:require [clojure.string :as str]
            [org.akvo.flow.dashboard.components.bootstrap :as b]
            [org.akvo.flow.dashboard.users.store :as store]
            [org.akvo.flow.dashboard.projects.store :as projects-store]
            [org.akvo.flow.dashboard.user-auth.store :as user-auth-store]
            [org.akvo.flow.dashboard.components.grid :refer (grid)]
            [org.akvo.flow.dashboard.dispatcher :refer (dispatch)]
            [org.akvo.flow.dashboard.ajax-helpers :refer (default-ajax-config)]
            [om.core :as om :include-macros true]
            [sablono.core :as html :refer-macros (html)]
            [ajax.core :refer (ajax-request GET POST PUT DELETE)]))

(defn panel-header-section [{:keys [user close!]} owner]
  (om/component
   (html
    [:div.row.panelHeader
     [:div.col-xs-9.text-left.panelTitle
      [:h4
       (b/icon :pencil) " Editing " [:span.usrNm (get user "userName")]]]
     [:div.col-xs-3.text-right
      (b/btn-primary {:on-click #(close!)} :circle-arrow-left "Go back")]])))

(defn target-value [event]
  (-> event .-target .-value))

(defn update-input! [owner key]
  (fn [event]
    (om/set-state! owner key (-> event .-target .-value))))

(defn user-edit-section [{:keys [on-save user]} owner]
  (reify
    om/IInitState
    (init-state [this] user)

    om/IWillReceiveProps
    (will-receive-props [this {:keys [user]}]
      (om/set-state! owner user))

    om/IRenderState
    (render-state [this {:strs [userName emailAddress] :as state}]
      (html
       [:div.userEditSection.topMargin
        [:h2 "User info:"]
        [:form
         [:div.form-group
          [:label.control-label.text-left {:for "username"} "Name"]
          [:input.form-control {:value userName
                                :placeholder "Enter full name"
                                :on-change (update-input! owner "userName")}]]
         [:div.form-group
          [:label.control-label.text-left {:for "email"} "Email"]
          [:input.form-control {:value emailAddress
                                :placeholder "example@gmail.com"
                                :on-change (update-input! owner "emailAddress")}]]
         [:div.form-group
          (b/btn-primary {:class (when (= state user) "disabled")
                          :on-click #(do (.preventDefault %)
                                         (on-save state))}
                         :floppy-disk "Save user info")]]]))))


(defn actions [user-auth owner]
  (om/component
   (html
    [:a {:on-click #(dispatch :user-auth/delete user-auth)} (b/icon :remove) " Delete"])))

(defn role-label [{:strs [name]} owner]
  (om/component
   (html
    [:span name])))

(defn roles-and-permissions [{:keys [user roles-store projects-store user-auth-store]} owner]
  (reify
    om/IInitState
    (init-state [this]
      {:selected-role nil
       :selected-folders []})

    om/IRenderState
    (render-state [this {:keys [selected-role selected-folders]}]
      (html [:div.userRolesPerm.well.topMargin
             [:h2 "Roles and permissions:"]
             [:div.form-inline.text-left.paddingTop.roleEditSelect {:role "name"}
              [:div.form-group
               (om/build b/select
                         {:placeholder "Select a role"
                          :selected selected-role
                          :data (store/get-roles roles-store)
                          :label-fn #(get % "name")
                          :key-fn #(str (get % "keyId"))
                          :on-select #(om/set-state! owner :selected-role %)})]
              (for [selected-folder selected-folders]
                [:div.form-group
                 (om/build b/select
                           {:data [selected-folder]
                            :selected selected-folder
                            :label-fn #(get % "name")
                            :key-fn #(str (get % "keyId"))})])
              (when (or (empty? selected-folders)
                        (= (get (projects-store/get-by-id projects-store
                                                          (get (peek selected-folders) "keyId"))
                                "projectType")
                           "PROJECT_FOLDER"))
                [:div.form-group
                 (let [projects (if (empty? selected-folders)
                                  (cons {"name" "All folders" "keyId" 0}
                                        (projects-store/get-projects projects-store nil))
                                  (projects-store/get-projects projects-store (get (peek selected-folders) "keyId")))]

                   (om/build b/select
                           {:placeholder "Select a project(folder)"
                            :data projects
                            :label-fn #(get % "name")
                            :key-fn #(str (get % "keyId"))
                            :on-select #(om/set-state! owner :selected-folders
                                                       (conj selected-folders %))}))])
              [:div.form-group
               (b/btn-primary {:class (if (or (nil? selected-role)
                                              (empty? selected-folders))
                                        "disabled")
                               :on-click (fn [evt]
                                           (.preventDefault evt)
                                           (om/set-state! owner {:selected-role nil :selected-folders []})
                                           (dispatch :user-auth/create
                                                     {:user (get user "keyId")
                                                      :role (get selected-role "keyId")
                                                      :object-path (if (zero? (get (first selected-folders) "keyId"))
                                                                     "/"
                                                                     (str "/"
                                                                          (->> selected-folders
                                                                               (map #(get % "name"))
                                                                               (str/join "/"))))}))}
                              :plus "Add")]]
             (om/build grid
                       {:data (when-let [user-id (get user "keyId")]
                                (user-auth-store/get-by-user-id user-auth-store user-id))
                        :columns [{:title "Role"
                                   :cell-fn (fn [user-auth]
                                              (let [role-id (get user-auth "roleId")
                                                    role (store/get-role roles-store role-id)
                                                    name (get role "name")]
                                                name))}
                                  {:title "Resource"
                                   :cell-fn #(get % "objectPath")}
                                  {:title "Actions"
                                   :component actions}]})]))))

(defn generate-apikeys [owner user]
  (POST (str "/rest/users/" (get user "keyId") "/apikeys")
        (merge default-ajax-config
               {:handler (fn [response]
                           (let [access-key (get-in response ["apikeys" "accessKey"])
                                 secret (get-in response ["apikeys" "secret"])]
                             (om/set-state! owner {:access-key access-key
                                                   :secret secret})
                             (dispatch :new-access-key {:access-key access-key
                                                        :user user})))})))

(defn revoke-apikeys [owner user]
  (DELETE (str "/rest/users/" (get user "keyId") "/apikeys")
          (merge default-ajax-config
                 {:handler (fn [response]
                             (om/set-state! owner {:access-key nil :secret nil})
                             (dispatch :new-access-key {:access-key nil :user user}))})))

(defn api-keys-section [{:keys [user]} owner]
  (reify
    om/IInitState
    (init-state [this]
      {:access-key (get user "accessKey")
       :secret nil})
    om/IWillReceiveProps
    (will-receive-props [this {:keys [user]}]
      (om/set-state! owner :access-key (get user "accessKey")))
    om/IRenderState
    (render-state [this {:keys [access-key secret]}]
      (html
       [:div.apiKeySection.topMargin
        [:h2 "Manage API key:"]
        [:p "You can (re)generate or revoke an api key for this user"]
        (when secret
           [:div.alert.alert-success {:role "alert"}
            (b/icon :ok) " The secret key will only be shown once. If it gets lost you will need to generate a new one."])
        [:form
         [:div.form-group
          [:label.control-label.text-left "Access key"]
          [:input.form-control {:type "text"
                                :value access-key}]]
         (when secret
           [:div.form-group
            [:label.control-label.text-left "Secret"]
            [:input.form-control {:type "text"
                                  :value secret}]])


         [:div.btn-group
          [:button.btn.btn-default {:on-click #(do (.preventDefault %)
                                                   (generate-apikeys owner user))}
           (b/icon :refresh) " (Re)generate"]
          [:button.btn.btn-default {:on-click #(do (.preventDefault %)
                                                   (revoke-apikeys owner user))}
           (b/icon :ban-circle) " Revoke"]]]]))))

(defn user-details [{:keys [close! user projects-store roles-store user-auth-store]} owner]
  (reify
    om/IRender
    (render [this]
      (html
       [:div
        (om/build panel-header-section {:user user
                                        :close! close!})
        (om/build user-edit-section {:user user
                                     :on-save #(if (integer? (get % "keyId"))
                                                 (dispatch :edit-user %)
                                                 (do
                                                   (dispatch :new-user %)
                                                   (close!)))})
        (when (get user "keyId")
          [:div
           (om/build roles-and-permissions {:user user
                                           :projects-store projects-store
                                           :roles-store roles-store
                                           :user-auth-store user-auth-store})
          (om/build api-keys-section {:user user})])]))))
