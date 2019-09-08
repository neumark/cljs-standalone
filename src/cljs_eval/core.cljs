(ns cljs-eval.core
  (:require [cljs.js :as cjs]
            [cljs.pprint :refer [pprint]]
            [goog.array :as garray]
            [clojure.string :as str :refer [split-lines, replace]]))
; based on http://nbeloglazov.com/2016/03/11/getting-started-with-self-hosted-cljs-part-3.html

;; define your app data so that it doesn't get over-written on reload
(defonce compiler-state (atom (cjs/empty-state)))
(defonce output-cache (atom {}))

(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
  (println "js reloaded"))

(defn ns-to-cache-key [name macros]
  (symbol (str name (if macros "$macros" ""))))

(defn get-cached-compiled-ns [cache-key]
  (get @output-cache cache-key))

(defn invoke-source-loader [source-loader {:keys [name macros path] :as ns-id} cb]
  (if-let [dep-source (source-loader (clj->js ns-id))]
    (cb {:lang :clj :source dep-source})
    ; returning nil will result in an exception.
    ; its also possible to throw immediately:
    ;(throw (js/Error. (str "Unknown namespace " ns-id)))
    (cb nil)))

(defn get-loader [source-loader]
  (fn [{:keys [name macros] :as ns-id} cb]
    (println "Loading dependency" ns-id)
    (let [cached-compiled-ns (get-cached-compiled-ns (ns-to-cache-key name macros))]
      (if cached-compiled-ns
        (do
          (println "got cached compiled namespace for " ns-id cached-compiled-ns)
          (cb cached-compiled-ns))
        (do
          (println "no cached compiler output for " ns-id " fallback to source-loader")
          (invoke-source-loader source-loader ns-id cb))))))

(defn set-cached-compiled-ns! [cache-key compiled-ns]
  (swap! output-cache assoc cache-key compiled-ns))

(defn update-cache-handler [compiled-ns cb]
  (do
    (js/console.log "updating cache")
    (set-cached-compiled-ns! (str (get-in compiled-ns [:cache :name])) compiled-ns)
    (cb {:value nil})))

(defn noop [& _args] nil)

(def goog-provide-re #"^goog\.provide\(\'([^\s]+)\'\);$")

(defn extract-provided-ns [compiled-js-line]
  (if-let [match (re-matches goog-provide-re compiled-js-line)]
    (symbol (second match))
    nil))

(defn get-defined-namespaces [compiled-js]
  (->> (str/split-lines compiled-js)
      (map extract-provided-ns)
      (filter identity)))

(defn make-compiled-ns [ns compiled-js]
  {:lang :js
   :name ns
   :path (str/replace (str ns) #"\." "/") ; TODO: get rid of occasional $macros ending
   :source compiled-js
   ; read analysis output from compiler's state
   :cache (get (:cljs.analyzer/namespaces (deref @compiler-state)) ns)
  })

(defn write-output-cache! [compiled-js]
  (if-let [defined-ns (get-defined-namespaces compiled-js)]
    (do
      (println "found definitions for namespaces" defined-ns)
      ; I assume any namespace goog.provided-ed by the output JS is present in the compiler's analysis cache
      (let [new-cache-entries (apply hash-map (mapcat (fn [ns] [ns (make-compiled-ns ns compiled-js)]) defined-ns))]
        (println "cache entry for" defined-ns new-cache-entries)
        (swap! output-cache merge new-cache-entries)))
  nil))

(defn make-compile-cb [on-success on-failure]
  (fn [compiler-result] (if (:value compiler-result)
                          (let [compiled-js (:value compiler-result)]
                            (do
                              (println "compiler output" compiled-js)
                              (write-output-cache! compiled-js)
                              (on-success compiled-js)))
                          (let [error (:error compiler-result)]
                            (on-failure (js-obj
                                         "message" (.-message error)
                                         "data" (clj->js (.-data error))
                                         "cause" (.-cause error)))))))

(defn get-js-evaluator [js-eval]
  (fn [{:keys [name source] :as compiled-ns}]
    (do
      (println (str "evaluating macro in " name))
      (js-eval source))))

(defn do-compile [cljs-source {:keys [name logger source-loader on-success on-failure js-eval] :as opts}]
  (let [cb (make-compile-cb on-success on-failure)
        compiler-opts {; eval is necessary because the compiler needs to evaluate macros to compile source
                       :eval (get-js-evaluator js-eval)
                       :verbose false
                       :load (get-loader source-loader)
                       ; note: cache-source fn is only called by the compiler when macros are
                       ; compiled and evaluted in order to compile code which refer-macros them.
                       ; normally, the compiler will not call this method when code is compiled, this
                       ; must be done manually in the callback fn passed to compile-str
                       :cache-source update-cache-handler
                       :source-map true}]
    (binding [cljs.core/*print-newline* false
              cljs.core/*print-fn* (fn []
                                     (let [xs (js-arguments)]
                                       (.apply (.-log logger) logger (garray/clone xs))))
              cljs.core/*print-err-fn* (fn []
                                         (let [xs (js-arguments)]
                                           (.apply (.-error logger) logger (garray/clone xs))))]
      (cjs/compile-str @compiler-state cljs-source name compiler-opts cb))))

(defn ^:export dump-output-cache []
  (pprint @output-cache))

(defn ^:export dump-compiler-state []
  (pprint @compiler-state))

(defn ^:export has-compiled-ns [ns-name]
  (contains? @output-cache (symbol ns-name)))

(defn ^:export eval-compiled-ns [ns-name js-opts]
  (let [{:keys [name logger source-loader on-success on-failure js-eval] :as opts} (parse-js-opts js-opts)]
    ( ; TODO: fetch cached compiled ns, feed it to js-eval function
     )))

(defn parse-js-opts [js-opts]
  {:on-success (or (. js-opts -on-success) noop)
   :on-failure (or (. js-opts -on-failure) noop)
   :name (or (. js-opts -name) "unknown")
   :logger (or (. js-opts -logger) js/console)
   :source-loader (or (. js-opts -source-loader) noop)
   :js-eval (or (. js-opts -js-eval) js/eval)
   })

(defn ^:export compile [cljs-source js-opts]
  (let [options (parse-js-opts js-opts)]
    (do-compile cljs-source options)))
