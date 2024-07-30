(ns tx.stun
  (:require [core.component :as component]
            [utils.core :refer [readable-number]]
            [api.context :refer [transact!]]
            [api.effect :as effect]
            [data.types :as attr]))

(component/def :tx/stun attr/pos-attr
  duration
  (effect/text [_ _ctx]
    (str "Stuns for " (readable-number duration) " seconds"))

  (effect/valid-params? [_ {:keys [effect/source effect/target]}]
    (and target))

  (transact! [_ {:keys [effect/target]}]
    [[:tx/event target :stun duration]]))
