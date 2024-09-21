(ns core.widgets.debug-window
  (:require [gdx.graphics.camera :as camera]
            utils.core
            [core.context :as ctx :refer [mouse-on-stage-actor?]]
            [core.entity.player :as player]
            [core.g :as g]
            [core.graphics.views :refer [world-mouse-position world-camera gui-mouse-position gui-viewport-height]]
            [core.time :as time]
            [gdx.scene2d.group :refer [add-actor!]]
            [gdx.scene2d.ui :as ui])
  (:import com.badlogic.gdx.Gdx))

(defn- skill-info [{:keys [entity/skills]}]
  (clojure.string/join "\n"
                       (for [{:keys [property/id skill/cooling-down?]} (vals skills)
                             :when cooling-down? ]
                         [id [:cooling-down? (boolean cooling-down?)]])))

; TODO component to info-text move to the component itself.....
(defn- debug-infos ^String [ctx]
  (let [world-mouse (world-mouse-position ctx)]
    (str
     "logic-frame: " (time/logic-frame ctx) "\n"
     "FPS: " (.getFramesPerSecond Gdx/graphics)  "\n"
     "Zoom: " (camera/zoom (world-camera ctx)) "\n"
     "World: "(mapv int world-mouse) "\n"
     "X:" (world-mouse 0) "\n"
     "Y:" (world-mouse 1) "\n"
     "GUI: " (gui-mouse-position ctx) "\n"
     "paused? " (:context/paused? ctx) "\n"
     "elapsed-time " (utils.core/readable-number (time/elapsed-time ctx)) " seconds \n"
     (skill-info (player/entity* ctx))
     (when-let [entity* (ctx/mouseover-entity* ctx)]
       (str "Mouseover-entity uid: " (:entity/uid entity*)))
     ;"\nMouseover-Actor:\n"
     #_(when-let [actor (mouse-on-stage-actor? ctx)]
         (str "TRUE - name:" (.getName actor)
              "id: " (gdx.scene2d.actor/id actor)
              )))))

(defn create [context]
  (let [label (ui/->label "")
        window (ui/->window {:title "Debug"
                             :id :debug-window
                             :visible? false
                             :position [0 (gui-viewport-height context)]
                             :rows [[label]]})]
    (add-actor! window (ui/->actor context {:act #(do
                                                   (.setText label (debug-infos %))
                                                   (.pack window))}))
    window))
