(ns tx.sound
  (:require [core.component :as component]
            [api.context :refer [play-sound! transact!]]
            [data.types :as attr]))

(component/def :tx/sound attr/sound
  file
  (transact! [_ ctx]
    (play-sound! ctx file)
    nil))
