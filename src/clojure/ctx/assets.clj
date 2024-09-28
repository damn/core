(in-ns 'clojure.ctx)

(defn- recursively-search [folder extensions]
  (loop [[^FileHandle file & remaining] (.list (internal-file folder))
         result []]
    (cond (nil? file)
          result

          (.isDirectory file)
          (recur (concat remaining (.list file)) result)

          (extensions (.extension file))
          (recur remaining (conj result (.path file)))

          :else
          (recur remaining result))))

(defn- search-files [folder file-extensions]
  (map #(str/replace-first % folder "")
       (recursively-search folder file-extensions)))

(def ctx-assets :context/assets)

(defn- get-asset [ctx file]
  (get (:manager (ctx-assets ctx)) file))

(defn play-sound!
  "Sound is already loaded from file, this will perform only a lookup for the sound and play it.
Returns ctx."
  [ctx file]
  (.play ^Sound (get-asset ctx file))
  ctx)

(defn texture "Is already cached and loaded." [ctx file]
  (get-asset ctx file))
