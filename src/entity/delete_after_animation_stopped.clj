(ns entity.delete-after-animation-stopped
  (:require [core.component :as component]
            [data.animation :as animation]
            [api.entity :as entity]))

(component/def :entity/delete-after-animation-stopped? {}
  _
  (entity/create [_ entity* _ctx]
    (-> entity* :entity/animation :looping? not assert))
  (entity/tick [_ {:keys [entity/id entity/animation]} _ctx]
    (when (animation/stopped? animation)
      [[:tx/destroy id]])))
