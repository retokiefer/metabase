(ns metabase.api.collection
  "/api/collection endpoints."
  (:require [compojure.core :refer [GET POST PUT]]
            [metabase.api
             [card :as card-api]
             [common :as api]]
            [metabase.models
             [card :refer [Card]]
             [collection :as collection :refer [Collection]]
             [dashboard :refer [Dashboard]]
             [interface :as mi]
             [pulse :as pulse :refer [Pulse]]]
            [metabase.util :as u]
            [metabase.util.schema :as su]
            [puppetlabs.i18n.core :refer [tru]]
            [schema.core :as s]
            [toucan
             [db :as db]
             [hydrate :refer [hydrate]]]))

(api/defendpoint GET "/"
  "Fetch a list of all Collections that the current user has read permissions for.
  This includes `:can_write`, which means whether the current user is allowed to add or remove Cards to this
  Collection; keep in mind that regardless of this status you must be a superuser to modify properties of Collections
  themselves.

  By default, this returns non-archived Collections, but instead you can show archived ones by passing
  `?archived=true`."
  [archived]
  {archived (s/maybe su/BooleanString)}
  (as-> (db/select Collection :archived (Boolean/parseBoolean archived)
                   {:order-by [[:%lower.name :asc]]}) collections
    (filter mi/can-read? collections)
    (hydrate collections :can_write)))


;;; --------------------------------- Fetching a single Collection & its 'children' ----------------------------------

(defn- collection-children
  "Fetch a map of the 'child' objects belonging to a Collection of type `model`, or of all available types if `model` is
  nil. `model->children-fn` should be a map of the different types of children that can be included to a function used
  to fetch them. Optional `children-fn-params` will be passed to each children-fetching fn.

      (collection-children :cards model->collection-children-fn 1)
      ;; -> {:cards [...cards for Collection 1...]}

      (collection-children nil model->collection-children-fn  1)
      ;; -> {:cards [...], :dashboards [...], :pulses [...]}"
  [model model->children-fn & children-fn-params]
  (into {} (for [[a-model children-fn] model->children-fn
                 ;; only fetch models that are specified by the `model` param; or everything if it's `nil`
                 :when (or (nil? model)
                           (= (name model) (name a-model)))]
             ;; return the results like {:card <results-of-card-children-fn>}
             {a-model (apply children-fn children-fn-params)})))

(def ^:private model->collection-children-fn
  "Functions for fetching the 'children' of a Collection."
  {:cards      #(db/select [Card :name :id :collection_position],      :collection_id %, :archived false)
   :dashboards #(db/select [Dashboard :name :id :collection_position], :collection_id %, :archived false)
   :pulses     #(db/select [Pulse :name :id :collection_position],     :collection_id %)})

(def ^:private model->root-collection-children-fn
  "Functions for fetching the 'children' of the root Collection."
  (let [basic-item-info (fn [items]
                             (for [item items]
                               (select-keys item [:name :id :collection_position])))]
    {:cards      #(->> (db/select [Card :name :id :public_uuid :read_permissions :dataset_query :collection_position]
                         :collection_id nil, :archived false)
                       (filter mi/can-read?)
                       basic-item-info)
     :dashboards #(->> (db/select [Dashboard :name :id :public_uuid :collection_position]
                         :collection_id nil, :archived false)
                       (filter mi/can-read?)
                       basic-item-info)
     :pulses     #(->> (db/select [Pulse :name :id :collection_position]
                         :collection_id nil)
                       (filter mi/can-read?)
                       basic-item-info)}))

(api/defendpoint GET "/:id"
  "Fetch a specific (non-archived) Collection, including objects of a specific `model` that belong to it. If `model` is
  unspecified, it will return objects of all types."
  [id model]
  {model (s/maybe (s/enum "cards" "dashboards" "pulses"))}
  (-> (api/read-check Collection id, :archived false)
      (hydrate :effective_location :effective_children :effective_ancestors :can_write)
      (merge (collection-children model model->collection-children-fn id))))


(api/defendpoint GET "/root"
  "Fetch objects in the 'root' Collection. (The 'root' Collection doesn't actually exist at this point, so this just
  returns objects that aren't in *any* Collection."
  [model]
  {model (s/maybe (s/enum "cards" "dashboards" "pulses"))}
  (merge
   {:name                (tru "Root Collection")
    :id                  "root"
    :can_write           api/*is-superuser?* ; temporary until Root Collection perms are merged !
    :effective_location  "/"
    :effective_children  (collection/effective-children collection/root-collection)
    :effective_ancestors []}
   (collection-children model model->root-collection-children-fn)))


;;; ----------------------------------------- Creating/Editing a Collection ------------------------------------------

(defn- write-check-collection-or-root-collection
  "Check that you're allowed to write Collection with `collection-id`; if `collection-id` is `nil`, check that you have
  Root Collection perms."
  [collection-id]
  (if collection-id
    (api/write-check Collection collection-id)
    ;; if the Collection is going to go in the Root Collection, for the time being we'll just check that you're a
    ;; superuser. Once we merge in Root Collection permissions we'll need to change this !
    (api/check-superuser)))

(api/defendpoint POST "/"
  "Create a new Collection."
  [:as {{:keys [name color description parent_id]} :body}]
  {name        su/NonBlankString
   color       collection/hex-color-regex
   description (s/maybe su/NonBlankString)
   parent_id   (s/maybe su/IntGreaterThanZero)}
  ;; To create a new collection, you need write perms for the location you are going to be putting it in...
  (write-check-collection-or-root-collection parent_id)
  ;; Now create the new Collection :)
  (db/insert! Collection
    (merge
     {:name        name
      :color       color
      :description description}
     (when parent_id
       {:location (collection/children-location (db/select-one [Collection :location :id] :id parent_id))}))))

(defn- move-collection-if-needed! [collection-before-update collection-updates]
  ;; is a [new] parent_id update specified in the PUT request?
  (when (contains? collection-updates :parent_id)
    (let [orig-location (:location collection-before-update)
          new-parent-id (:parent_id collection-updates)
          new-location  (collection/children-location (if new-parent-id
                                                        (db/select-one [Collection :location :id] :id new-parent-id)
                                                        collection/root-collection))]
      ;; check and make sure we're actually supposed to be moving something
      (when (not= orig-location new-location)
        ;; ok, make sure we have perms to move something out of the original parent Collection
        (write-check-collection-or-root-collection (collection/location-path->parent-id orig-location))
        ;; now make sure we have perms to move something into the new parent Collection
        (write-check-collection-or-root-collection new-parent-id)
        ;; ok, we're good to move!
        (collection/move-collection! collection-before-update new-location)))))

(api/defendpoint PUT "/:id"
  "Modify an existing Collection, including archiving or unarchiving it, or moving it."
  [id, :as {{:keys [name color description archived parent_id], :as collection-updates} :body}]
  {name        (s/maybe su/NonBlankString)
   color       (s/maybe collection/hex-color-regex)
   description (s/maybe su/NonBlankString)
   archived    (s/maybe s/Bool)
   parent_id   (s/maybe su/IntGreaterThanZero)}
  ;; do we have perms to edit this Collection?
  (let [collection-before-update (api/write-check Collection id)]
    ;; ok, go ahead and update it! Only update keys that were specified in the `body`
    (let [updates (u/select-keys-when collection-updates :present [:name :color :description :archived])]
      (when (seq updates)
        (db/update! Collection id updates)))
    ;; if we're trying to *move* the Collection (instead or as well) go ahead and do that
    (move-collection-if-needed! collection-before-update collection-updates)
    ;; Check and see if if the Collection is switiching to archived
    (when (and (not (:archived collection-before-update))
               archived)
      (when-let [alerts (seq (apply pulse/retrieve-alerts-for-cards (db/select-ids Card, :collection_id id)))]
        ;; When a collection is archived, all of it's cards are also marked as archived, but this is down in the model
        ;; layer which will not cause the archive notification code to fire. This will delete the relevant alerts and
        ;; notify the users just as if they had be archived individually via the card API
        (card-api/delete-alert-and-notify-archived! alerts))))
  ;; return the updated object
  (Collection id))


;;; ------------------------------------------------ GRAPH ENDPOINTS -------------------------------------------------

(api/defendpoint GET "/graph"
  "Fetch a graph of all Collection Permissions."
  []
  (api/check-superuser)
  (collection/graph))


(defn- ->int [id] (Integer/parseInt (name id)))

(defn- dejsonify-collections [collections]
  (into {} (for [[collection-id perms] collections]
             {(->int collection-id) (keyword perms)})))

(defn- dejsonify-groups [groups]
  (into {} (for [[group-id collections] groups]
             {(->int group-id) (dejsonify-collections collections)})))

(defn- dejsonify-graph
  "Fix the types in the graph when it comes in from the API, e.g. converting things like `\"none\"` to `:none` and
  parsing object keys as integers."
  [graph]
  (update graph :groups dejsonify-groups))

(api/defendpoint PUT "/graph"
  "Do a batch update of Collections Permissions by passing in a modified graph."
  [:as {body :body}]
  {body su/Map}
  (api/check-superuser)
  (collection/update-graph! (dejsonify-graph body))
  (collection/graph))


(api/define-routes)
