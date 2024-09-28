(in-ns 'clojure.ctx)

(defn- set-first-screen [context]
  (->> context
       :context/screens
       :first-screen
       (change-screen context)))

(defn create-into
  "For every component `[k v]`  `(->mk [k v] ctx)` is non-nil
  or false, assoc's at ctx k v"
  [ctx components]
  (assert (map? ctx))
  (reduce (fn [ctx [k v]]
            (if-let [v (->mk [k v] ctx)]
              (assoc ctx k v)
              ctx))
          ctx
          components))

(defsystem ^:private destroy! "Side effect destroy resources. Default do nothing." [_])
(defmethod destroy! :default [_])

(defn- ->app-listener [ctx]
  (reify clojure.gdx/AppListener
    (on-create [_]
      (->> ctx
           ; screens require vis-ui / properties (map-editor, property editor uses properties)
           (sort-by (fn [[k _]] (if (= k :context/screens) 1 0)))
           (create-into ctx)
           set-first-screen
           (reset! app-state)))

    (on-dispose [_]
      (run! destroy! @app-state))

    (on-render [_]
      (clear-screen!)
      (screen-render! (current-screen @app-state)))

    (on-resize [_ dim]
      ; TODO fix mac screen resize bug again
      (update-viewports! @app-state dim))))

(defrecord Context [])

(defn start-app!
  "Validates all properties, then creates the context record and starts a libgdx application with the desktop (lwjgl3) backend.
Sets [[app-state]] atom to the context."
  [properties-edn-file]
  (let [ctx (map->Context (->ctx-properties properties-edn-file))
        app (build-property ctx :app/core)]
    (->lwjgl3-app (->app-listener (safe-merge ctx (:app/context app)))
                  (:app/lwjgl3 app))))
