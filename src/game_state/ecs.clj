(ns game-state.ecs
  (:require [clj-commons.pretty.repl :as p]
            [utils.core :refer [sort-by-order]]
            [core.component :refer [defcomponent] :as component]
            [api.context :as ctx]
            [api.graphics :as g]
            [api.entity :as entity :refer [map->Entity]]
            [api.tx :refer [transact!]]))

(defn ->state []
  {:uids-entities {}
   :entity-error nil})

(defn- uids-entities [ctx]
  (-> ctx
      :context/game
      deref
      :uids-entities))

(defn- entity-error [ctx]
  (-> ctx
      :context/game
      deref
      :entity-error))

(defmethod transact! :tx/setup-entity [[_ an-atom uid components] ctx]
  {:pre [(not (contains? components :entity/id))
         (not (contains? components :entity/uid))]}
  (let [entity* (-> components
                    (component/update-map entity/create-component components ctx)
                    map->Entity)]
    (reset! an-atom (assoc entity* :entity/id an-atom :entity/uid uid)))
  ;(println "tx/setup-entity" (class ctx))
  ctx)

(defmethod transact! :tx/assoc-uids->entities [[_ entity] ctx]
  {:pre [(number? (:entity/uid @entity))]}
  (swap! (:context/game ctx) update :uids-entities assoc (:entity/uid @entity) entity)
  ctx)

(defmethod transact! :tx/dissoc-uids->entities [[_ uid] ctx]
  {:pre [(contains? (uids-entities ctx) uid)]}
  (swap! (:context/game ctx) update :uids-entities dissoc uid)
  ctx)

(defcomponent :entity/uid {}
  (entity/create [_ {:keys [entity/id]} _ctx]
    [[:tx/assoc-uids->entities id]])
  (entity/destroy [[_ uid] _entity* _ctx]
    [[:tx/dissoc-uids->entities uid]]))

(let [cnt (atom 0)]
  (defn- unique-number! []
    (swap! cnt inc)))

(defn- apply-system-transact-all! [ctx system entity*]
  ;(println "apply-system-transact-all! " (class ctx))
  (let [result  (reduce (fn [ctx txs]
                          (if txs
                            (try (ctx/transact-all! ctx txs)
                                 (catch Throwable t
                                   (throw (ex-info "Error with ctx/transact-all!" {:txs txs} t))))
                            ctx))
                        ctx
                        (component/apply-system @system entity* ctx))]
    (if result
      result
      ctx
      )
    ))

(comment

 ; * entity/position obligatory
 ; * all components which use render systems depend on z-order

 [:map {:closed true}
  [:entity/position] ; required - because adds/removes from world with create/destroy
  [:entity/animation {:optional true}] ; :entity/image shouldn't be there because we assoc it
  [:entity/body {:optional true}]
  [:entity/clickable {:optional true}] ; depends on z-order (mouseover-entity filters) & body ( world-grid )

  [:entity/delete-after-animation-stopped? ] ; -> animation ofco.
  [:entity/delete-after-duration {:optional true}] ; no deps.

  [:entity/faction {:optional true}] ; TODO why projectiles have this ?!
  ; => context.potential-field/tiles->entities
  ; => projectile collision don't collide with same faction ...
  ; so projectile _needs_ it because projectile-collision depends on it ....
  ; dann noch 'shout' und die API fns friendly/enemy....
  ; might fix performance if all projectiles don't cause each a potential field ,....

  [:entity/flying?] ; used @ world.cell/cell-blocked? .... linked w. z-order .... move to body ?

  [:entity/hp] ; => body for render....

  [:entity/image] ; position, if body -> reotation angle ?!,
  ; all who use entity/render need z-order too !!
  ; I wonder if z-order belongs in body .....
  ; and body checks that collides? is off for z-order effect/debug/on-ground ?

  [:entity/inventory] ; TODO modifier ... that means if inventory need _all_ modifiable ethingyies or have to check if component is there ...

  [:entity/line-render] ; no deps

  [:entity/mana] ; no deps

  [:entity/mouseover] ; depends on :entity/body

  [:entity/movement] ; depends on :entity/body

  [:entity/plop] ; TODO fix - destroy just for removing data, not making game side effects ( ?!)

  [:entity/projectile-collision] ; depends on :entity/body, :entity/faction (no friendly fire)
  [:entity/reaction-time]
  [:entity/shout] ; depends on :entity/faction
  [:entity/skills] ; to be usable @ entity/state it depends on :entity/mana - specific skills also require specific components ?! e.g. strength for melee - attack ....

  [:entity/state] ; depends on the fsm used , @ creature -> creature properties ....
  [:entity/stats] ; what do effects do if armor-save is not there ??? or has to be there ???
  [:entity/string-effect] ; depends one entity/body
  [:entity/z-order]

  ; TODO components without namespace add ... see txt

  ]

 ; dependencies just (assert k) @ the create of that component ?!
 ; or put @ component metadata ... then can check @ creation before calling create-component - fail fast...
 )

(defmethod transact! :tx/create [[_ components] ctx]
  (let [entity (atom nil)
        ctx (ctx/transact-all! ctx [[:tx/setup-entity entity (unique-number!) components]])
        ;_ (println ":tx/create - result of ctx/transact-all! with tx/setup-entity: " (class ctx))
        ctx (apply-system-transact-all! ctx #'entity/create @entity)
        ]
    ;(println "Result of tx/create: " (class ctx))
    ctx))

(defmethod transact! :tx/destroy [[_ entity] ctx]
  (swap! entity assoc :entity/destroyed? true)
  ctx)

(defn- handle-entity-error! [ctx entity* throwable]
  (p/pretty-pst (ex-info "" (select-keys entity* [:entity/uid]) throwable))
  (swap! (:context/game ctx) assoc :entity-error throwable))

(defn- render-entity* [system entity* g ctx]
  (try
   (dorun (component/apply-system system entity* g ctx))
   (catch Throwable t
     (when-not (entity-error ctx)
       (handle-entity-error! ctx entity* t))
     (let [[x y] (:entity/position entity*)]
       (g/draw-text g
                    {:text (str "Error / entity uid: " (:entity/uid entity*))
                     :x x
                     :y y
                     :up? true})))))

; TODO similar with define-order --- same fns for z-order keyword ... same name make ?
(def ^:private render-systems [entity/render-below
                               entity/render-default
                               entity/render-above
                               entity/render-info])

(extend-type api.context.Context
  api.context/EntityComponentSystem
  (entity-error [ctx]
    (entity-error ctx))

  (all-entities [ctx]
    (vals (uids-entities ctx)))

  (get-entity [ctx uid]
    (get (uids-entities ctx) uid))

  (tick-entities! [ctx entities*]
    (reduce (fn [ctx entity*]
              (try
               (apply-system-transact-all! ctx #'entity/tick entity*)
               (catch Throwable t
                 (do (handle-entity-error! ctx entity* t)
                     ctx
                     )))
              )
            ctx
            entities*
            )
    )

  (render-entities! [context g entities*]
    (doseq [entities* (map second ; FIXME lazy seq
                           (sort-by-order (group-by :entity/z-order entities*)
                                          first
                                          entity/render-order))
            system render-systems
            entity* entities*]
      (render-entity* system entity* g context))
    (doseq [entity* entities*]
      (render-entity* entity/render-debug entity* g context)))

  (remove-destroyed-entities! [ctx]
    (reduce (fn [ctx entity]
              (apply-system-transact-all! ctx #'entity/destroy @entity)
              )
            ctx
            (filter (comp :entity/destroyed? deref) (ctx/all-entities ctx))
            )))
