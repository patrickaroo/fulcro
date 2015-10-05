(ns leiningen.i18n
  (:require [clojure.java.shell :refer [sh]]
            [leiningen.core.main :as lmain]
            [leiningen.cljsbuild :refer [cljsbuild]]
            [clojure.string :as str]
            [untangled.i18n.util :as u]
            [clojure.pprint :as pp]))

(def compiled-js-path "i18n/out/compiled.js")
(def msgs-dir-path "i18n/msgs")
(def messages-pot-path (str msgs-dir-path "/messages.pot"))

(defn cljs-output-dir [namespace]
  (let [path-from-namespace (str/replace (str namespace) #"\." "/")]
    (str "src/" path-from-namespace)))

(defn translation-namespace [project]
  (if-let [ns (get-in project [:untangled-i18n :translation-namespace])]
    ns
    (symbol 'untangled.translations)))

(defn default-locale [project]
  (if-let [locale (get-in project [:untangled-i18n :default-locale])]
    locale
    "en-US"))

(defn find-po-files [msgs-dir-path]
  (filter #(.endsWith % ".po")
          (clojure.string/split-lines
            (:out (sh "ls" msgs-dir-path)))))

(defn gettext-missing? []
  (let [xgettext (:exit (sh "which" "xgettext"))
        msgcat (:exit (sh "which" "msgcat"))]
    (or (> xgettext 0) (> msgcat 0))))

(defn dir-missing? [dir]
  (-> (sh "ls" "-d" dir)
      (get :exit)
      (> 0)))

(defn cljs-prod-build?
  [build]
  (if (= (:id build) "production") build false))

(defn get-cljsbuild [builds]
  (some #(cljs-prod-build? %)
        builds))

(defn configure-i18n-build [build]
  (let [compiler-config (assoc (:compiler build) :output-dir "i18n/out"
                                                 :optimizations :whitespace
                                                 :output-to compiled-js-path)]
    (assoc build :id "i18n" :compiler compiler-config)))

(defn- po-path [po-file] (str msgs-dir-path "/" po-file))

(defn clojure-ize-locale [po-filename]
  (-> po-filename
      (str/replace #"^([a-z]+_*[A-Z]*).po$" "$1")
      (str/replace #"_" "-")))

(defn- puke [msg]
  (lmain/warn msg)
  (lmain/abort))

(defn gen-default-locale-ns [ns locale]
  (let [def-lc-namespace (symbol (str ns ".default-locale"))
        translation-require (list :require (symbol (str ns "." locale)) ['untangled.i18n.core :as 'i18n])
        ns-decl (list 'ns def-lc-namespace translation-require)
        reset-decl (list 'reset! 'i18n/*current-locale* locale)
        swap-decl (list 'swap! 'i18n/*loaded-translations*
                        (symbol (str "#(assoc % :" locale " " ns "." locale "/translations)")))]
    (str/join "\n\n" [ns-decl reset-decl swap-decl])
    ))

(defn lookup-modules [project locales]
  (let [ns (translation-namespace project)
        build (get-cljsbuild (get-in project [:cljsbuild :builds]))
        ]
    (if (-> build :compiler (contains? :modules))
      nil
      (let [output-dir (:output-dir (:compiler build))
            js-file #(str output-dir "/" % ".js")
            name (:name project)
            main-name (str name ".main")
            main {:output-to (js-file name)
                  :entries   #{main-name}}
            modules (reduce #(assoc %1
                              (keyword %2) {:output-to (js-file %2)
                                            :entries   #{(str ns "." %2)}}) {} locales)
            modules-with-main (assoc modules :main main)]
        (-> build
            (update-in [:compiler] dissoc :main)
            (assoc-in [:compiler :modules] modules-with-main)
            (assoc-in [:compiler :optimizations] :advanced))
        ))))

(defn gen-locales-ns
  "
  Generates a code string that assists in dynamically loading translations when a user changes their locale. Uses the
  leiningen project map to configure the code string's namespace as well as the output directory for locale modules.

  Parameters:
  * `project`: A leiningen project map
  * `locales`: A list of locale strings

  Returns a string of cljs code.
  "
  [project locales]
  (let [locales-ns (-> project translation-namespace (str ".locales") symbol)
        ns-decl (pp/write (list 'ns locales-ns
                                (list :require
                                      'goog.module
                                      'goog.module.ModuleLoader
                                      '[goog.module.ModuleManager :as module-manager]
                                      '[untangled.i18n.core :as i18n])
                                (list :import 'goog.module.ModuleManager)) :stream nil)
        output-dir (:output-dir (:compiler (get-cljsbuild (get-in project [:cljsbuild :builds]))))
        abs-module-path (str/join (interleave (repeat "/") (drop 2 (str/split output-dir #"/"))))
        modules-map (reduce #(assoc %1 %2 (str abs-module-path "/" %2 ".js")) {} locales)
        modules-def (pp/write (list 'defonce 'modules (symbol (str "#js")) modules-map) :stream nil)
        mod-info-map (reduce #(assoc %1 %2 []) {} locales)
        mod-info-def (list 'defonce 'module-info (symbol (str "#js")) mod-info-map)
        loader-def (pp/write (list 'defonce (symbol "^:export")
                                   'loader (list 'let ['loader (list 'goog.module.ModuleLoader.)]
                                                 (list '.setLoader 'manager 'loader)
                                                 (list '.setAllModuleInfo 'manager 'module-info)
                                                 (list '.setModuleUris 'manager 'modules)
                                                 'loader)) :pretty false :stream nil)
        set-locale-def (list 'defn 'set-locale ['op 'l]
                             (list 'js/console.log (list 'str "LOADING ALTERNATE LOCALE: " 'l))
                             (list 'if (list 'exists? 'js/i18nDevMode)
                                   (list 'do (list 'js/console.log (list 'str "LOADED ALTERNATE LOCALE in dev mode: " 'l))
                                         (list 'reset! 'i18n/*current-locale* 'l)
                                         (list (list 'op (symbol "#(assoc % :application/locale l)"))))
                                   (list '.execOnLoad 'manager 'l
                                         (list 'fn 'after-locale-load []
                                               (list 'js/console.log (list 'str "LOADED ALTERNATE LOCALE: " 'l))
                                               (list 'reset! 'i18n/*current-locale* 'l)
                                               (list (list 'op (symbol "#(assoc % :application/locale l)")))))))]
    (str/join "\n\n" [ns-decl modules-def mod-info-def loader-def set-locale-def])))

(defn deploy-translations
  "This subtask converts translated .po files into locale-specific .cljs files for runtime string translation."
  [project]
  (let [replace-hyphen #(str/replace % #"-" "_")
        trans-ns (translation-namespace project)
        output-dir (cljs-output-dir trans-ns)
        po-files (find-po-files msgs-dir-path)
        default-lc (default-locale project)
        locales (map clojure-ize-locale po-files)
        locales-inc-default (conj locales default-lc)
        default-lc-translation-path (str output-dir "/" (replace-hyphen default-lc) ".cljs")
        default-lc-translations (u/wrap-with-swap :namespace trans-ns :locale default-lc :translation {})
        locales-code-string (gen-locales-ns project locales)
        locales-path (str output-dir "/locales.cljs")
        default-locale-code-string (gen-default-locale-ns trans-ns default-lc)
        default-locale-path (str output-dir "/default_locale.cljs")]
    (sh "mkdir" "-p" output-dir)
    (u/write-cljs-translation-file default-locale-path default-locale-code-string)
    (if (some #{default-lc} locales)
      (u/write-cljs-translation-file locales-path locales-code-string)
      (let [locales-code-string (gen-locales-ns project locales-inc-default)]
        (u/write-cljs-translation-file locales-path locales-code-string)
        (u/write-cljs-translation-file default-lc-translation-path default-lc-translations)))
    (lmain/warn "Configured project for default locale:" default-lc)

    (doseq [po po-files]
      (let [locale (clojure-ize-locale po)
            translation-map (u/map-translations (po-path po))
            cljs-translations (u/wrap-with-swap
                                :namespace trans-ns :locale locale :translation translation-map)
            cljs-trans-path (str output-dir "/" (replace-hyphen locale) ".cljs")]
        (u/write-cljs-translation-file cljs-trans-path cljs-translations)))

    (lmain/warn "Deployed translations for the following locales:" locales)

    (if-let [modules-map (lookup-modules project locales-inc-default)]
      (do (lmain/warn
            "
            No :modules configuration detected for dynamically loading translations!
            Your production cljsbuild should look something like this:
            ")
          (lmain/warn (pp/write modules-map :stream nil)
                      "
                      ")))))

(defn extract-i18n-strings
  "This subtask extracts strings from your cljs files that should be translated."
  [project]
  (if (gettext-missing?)
    (puke "The xgettext and msgcat commands are not installed, or not on your $PATH.")
    (if (dir-missing? msgs-dir-path)
      (puke "The i18n/msgs directory is missing in your project! Please create it.")
      (let [cljsbuilds-path [:cljsbuild :builds]
            builds (get-in project cljsbuilds-path)
            cljs-prod-build (get-cljsbuild builds)
            i18n-build (configure-i18n-build cljs-prod-build)
            i18n-project (assoc-in project cljsbuilds-path [i18n-build])
            po-files-to-merge (find-po-files msgs-dir-path)]

        (cljsbuild i18n-project "once" "i18n")
        (sh "xgettext" "--from-code=UTF-8" "--debug" "-k" "-ktr:1" "-ktrc:1c,2" "-ktrf:1" "-o" messages-pot-path
            compiled-js-path)
        (doseq [po po-files-to-merge]
          (sh "msgcat" "--no-wrap" messages-pot-path (po-path po) "-o" (po-path po)))))))

(defn i18n
  "A plugin which automates your i18n string translation workflow"
  {:subtasks [#'extract-i18n-strings #'deploy-translations]}
  ([project]
   (puke "Bad you!"))
  ([project subtask]
   (case subtask
     "extract-i18n-strings" (extract-i18n-strings project)
     "deploy-translations" (deploy-translations project)
     (puke (str "Unrecognized subtask: " subtask)))))


