(ns components.context.properties
  (:require [clojure.edn :as edn]
            [malli.core :as m]
            [malli.error :as me]
            [utils.core :refer [safe-get mapvals]]
            [core.component :refer [defcomponent] :as component]
            [core.context :as ctx]))

(defcomponent :property/id {:data [:qualified-keyword {}]
                            :optional? false})

(defn load-raw-properties [file]
  (let [values (-> file slurp edn/read-string)]
    (assert (apply distinct? (map :property/id values)))
    (zipmap (map :property/id values) values)))

(defn- of-type?
  ([property-type {:keys [property/id]}]
   (= (namespace id)
      (:id-namespace property-type)))
  ([types property type]
   (of-type? (type types) property)))

(defn- property->type [types property]
  {:post [%]}
  (some (fn [[type property-type]]
          (when (of-type? property-type property)
            type))
        types))

(defn- validation-error-message [schema property]
  (let [explained (m/explain schema property)]
    (str (me/humanize explained))))

(defn- validate [property types]
  (let [type (property->type types property)
        schema (:schema (type types))]
    (if (try (m/validate schema property)
             (catch Throwable t
               (throw (ex-info "m/validate fail" {:property property :type type} t))))
      property
      (throw (ex-info (validation-error-message schema property)
                      {:property property})))))

(defn- map-attribute-schema [[id-attribute attr-ks]]
  (let [schema-form (apply vector :map {:closed true} id-attribute
                           (component/attribute-schema attr-ks))]
    (try (m/schema schema-form)
         (catch Throwable t
           (throw (ex-info "" {:schema-form schema-form} t))))))

(defn- apply-kvs
  "Calls for every key in map (f k v) to calculate new value at k."
  [m f]
  (reduce (fn [m k]
            (assoc m k (f k (get m k)))) ; using assoc because non-destructive for records
          m
          (keys m)))

(defn- try-data [k]
  (try (component/k->data k) (catch Throwable t)))

(defn- edn->value [ctx]
  (fn [k v]
    (if-let [->value (:->value (component/k->data k))]
      (->value v ctx)
      v)))

(defn- value->edn [k v]
  (if-let [->edn (:->edn (try-data k))]
    (->edn v)
    v))

(defn- recur-value->edn [property]
  (apply-kvs property
             (fn [k v]
               (let [v (if (map? v)
                         (recur-value->edn v)
                         v)]
                 (value->edn k v)))))

(defn- recur-fetch-refs [property db]
  (apply-kvs property
             (fn [k v]
               (let [v (if (map? v)
                         (recur-fetch-refs v db)
                         v)]
                 (if-let [fetch (:fetch-references (try-data k))]
                   (fetch db v)
                   v)))))

(defn- edn->db [properties types ctx]
  (let [db (mapvals #(-> %
                         (validate types)
                         (apply-kvs (edn->value ctx)))
                    properties)]
    (mapvals #(recur-fetch-refs % db) db)))

(defn- sort-by-type [types properties]
  (sort-by #(property->type types %)
           properties))

(defn- recur-sort-map [m]
  (into (sorted-map)
        (zipmap (keys m)
                (map #(if (map? %)
                        (recur-sort-map %)
                        %)
                     (vals m)))))

(defn- db->edn [types db]
  (->> db
       vals
       (sort-by-type types)
       (map recur-value->edn)
       (map #(validate % types))
       (map recur-sort-map)))

(defcomponent :context/properties
  {:data :some
   :let {:keys [types file properties]}}
  (component/create [_ ctx]
    (let [types (component/ks->create-all types {})
          types (mapvals #(update % :schema map-attribute-schema) types)]
      {:file file
       :types types
       :db (edn->db properties types ctx)})))

(defn- async-pprint-spit [ctx file data]
  (.start
   (Thread.
    (fn []
      (try (binding [*print-level* nil]
             (->> data
                  clojure.pprint/pprint
                  with-out-str
                  (spit file)))
           (catch Throwable t
             (ctx/error-window! ctx t)))))))

(defn- validate-and-write-to-file! [{{:keys [types db file]} :context/properties :as ctx}]
  (->> db
       (db->edn types)
       doall
       (async-pprint-spit ctx file))
  ctx)

(extend-type core.context.Context
  core.context/PropertyStore
  (property [{{:keys [db]} :context/properties} id]
    (safe-get db id))

  (all-properties [{{:keys [db types]} :context/properties :as ctx} type]
    (->> (vals db)
         (filter #(of-type? types % type))))

  (overview [{{:keys [types]} :context/properties} property-type]
    (:overview (property-type types)))

  (property-types [{{:keys [types]} :context/properties}]
    (keys types))

  (update! [{{:keys [db types]} :context/properties :as ctx} {:keys [property/id] :as property}]
    {:pre [(contains? property :property/id)
           (contains? db id)]}
    (-> ctx
        (update-in [:context/properties :db] assoc id property)
        validate-and-write-to-file!))

  (delete! [{{:keys [db]} :context/properties :as ctx} property-id]
    {:pre [(contains? db property-id)]}
    (-> ctx
        (update-in [:context/properties :db] dissoc property-id)
        validate-and-write-to-file!)))


(comment

 ; now broken -> work directly on edn
 #_(require '[core.context :as ctx])

 #_(defn- migrate [property-type prop-fn]
     (let [ctx @app/state]
       (time
        (doseq [prop (map prop-fn (ctx/all-properties ctx property-type))]
          (println (:property/id prop) ", " (:property/pretty-name prop))
          (swap! app/state ctx/update! prop)))
       (validate-and-write-to-file! @app/state)
       nil))

 (migrate :properties/creature
          (fn [prop]
            (-> prop
                (dissoc :entity/reaction-time)
                (update :property/stats assoc :stats/reaction-time
                        (max (int (/ (:entity/reaction-time prop) 0.016))
                             2)))))
 )
