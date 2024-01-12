(ns tasks
  (:require
   [babashka.process :as p]
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]))

(require '[babashka.pods :as pods])
(pods/load-pod 'org.babashka/filewatcher "0.0.1")
(require '[pod.babashka.filewatcher :as fw])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn replace-ext [p ext]
  (let [old-ext (fs/extension p)]
    (string/replace (str p) old-ext ext)))

(defn ext-match? [p ext]
  (= (fs/extension p) ext))

(defn cwd []
  (.getCanonicalPath (io/file ".")))

(defn expand
  [path & parts]
  (let [path (apply str path parts)]
    (->
      @(p/process (str "zsh -c 'echo -n " path "'")
                  {:out :string})
      :out)))

(defn is-mac? []
  (string/includes? (expand "$OSTYPE") "darwin21"))

(comment
  (is-mac?))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Aseprite
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn aseprite-bin-path []
  (if (is-mac?)
    "/Applications/Aseprite.app/Contents/MacOS/aseprite"
    "aseprite"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Watch and export sprite sheets
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn export-pixels-sheet [path]
  (if (ext-match? path "aseprite")
    (do
      (println "Processing aseprite file" (str path))
      (let [result
            (->
              ^{:out :string}
              (p/$ ~(aseprite-bin-path) -b ~(str path)
                   --format json-array
                   --sheet
                   ~(-> path (replace-ext "png")
                        (string/replace ".png" "_sheet.png"))
                   --sheet-type horizontal
                   --list-tags
                   --list-slices
                   --list-layers)
              p/check :out)]
        (when false #_verbose? (println result))))
    (println "Skipping path without aseprite extension" path)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn watch
  "Defaults to watching the current working directory."
  [& _args]
  (-> (Runtime/getRuntime)
      (.addShutdownHook (Thread. #(println "\nShut down watcher."))))
  (println "watching dir:" (cwd))
  (fw/watch (cwd) (fn [event]
                    (let [ext (-> event :path fs/extension)]
                      (when (#{"aseprite"} ext)
                        (if (re-seq #"_sheet" (:path event))
                          (println "Change event for" (:path event) "[bb] Ignoring.")
                          (do
                            (println "Change event for" (:path event) "[bb] Processing.")
                            (export-pixels-sheet (:path event)))))))
            {:delay-ms 100})
  @(promise))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; steam box art
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def boxart-base-logo "assets/boxart/base_logo.aseprite")
(def boxart-base-logo-wide "assets/boxart/base_logo_wide.aseprite")
(def boxart-base-bg-no-logo "assets/boxart/base_bg_no_logo.aseprite")
(def boxart-base-logo-no-bg "assets/boxart/base_logo_no_bg.aseprite")

(def boxart-defs
  (->>
    {:header-capsule   {:width 460 :height 215 :base boxart-base-logo-wide}
     :small-capsule    {:width 231 :height 87 :base boxart-base-logo-wide}
     :main-capsule     {:width 616 :height 353}
     :vertical-capsule {:width 374 :height 448}
     :page-background  {:width 1438 :height 810}
     :library-capsule  {:width 600 :height 900}
     :library-header   {:width 460 :height 215 :base boxart-base-logo-wide}
     :library-hero     {:width 3840 :height 1240 :base boxart-base-bg-no-logo}
     :library-logo     {:width 1280 :height 720 :base boxart-base-logo-no-bg}
     :client-icon      {:width 16 :height 16 :skip-generate true :export-ext ".jpg"}
     :community-icon   {:width 184 :height 184}}
    (map (fn [[label opts]] [label (assoc opts :label label)]))
    (into {})))

(def boxart-dir "assets/boxart/")

(defn boxart->path
  ([b-opts] (boxart->path b-opts ".aseprite"))
  ([{:keys [label]} ext]
   (str boxart-dir (name label) ext)))

(defn create-resized-file
  [{:keys [base-path overwrite verbose?]}
   {:keys [width height base] :as opts}]
  (let [new-path  (boxart->path opts)
        exists?   (fs/exists? new-path)
        base-path (or base base-path)]
    (when (and exists? overwrite)
      (fs/delete new-path))
    (if (and (not overwrite) exists?)
      (println "Skipping existing new-path " new-path)
      (do
        (println (str "Creating aseprite file: " (str new-path)))
        (let [result (-> ^{:out :string}
                         (p/$ ~(aseprite-bin-path) -b ;; 'batch' mode, don't open the UI
                              ~base-path
                              ;; pass script-params BEFORE --script arg
                              --script-param ~(str "filename=" new-path)
                              --script-param ~(str "width=" width)
                              --script-param ~(str "height=" height)
                              --script "scripts/resize_canvas.lua")
                         p/check :out)]
          (when verbose? (println result)))))))

(comment
  (name :main-capsule)
  (create-resized-file
    {:base-path boxart-base-logo :overwrite true :verbose? true}
    {:width 616 :height 353 :label :main-capsule}))

(defn generate-boxart-files []
  (-> (p/$ mkdir -p ~boxart-dir) p/check)
  (->> boxart-defs vals (remove #(-> % :skip-generate))
       (map (partial create-resized-file
                     {:base-path boxart-base-logo
                      :verbose?  true
                      :overwrite true}))))

(defn aseprite-export-boxart-png [b-opts]
  (let [path     (boxart->path b-opts)
        png-path (boxart->path b-opts (:export-ext b-opts ".png"))]
    (println "Exporting" path "as" png-path)
    (-> (p/$ ~(aseprite-bin-path) -b ~path --save-as ~png-path)
        p/check :out)))

(defn export-all-boxart []
  (->> boxart-defs vals (map aseprite-export-boxart-png) doall))

(comment
  (generate-boxart-files)
  (export-all-boxart))
