(ns clsql.core
  (:require [clsql.config :as config]
            [clsql.errors :refer [throw-if]]
            [clsql.grammars.queries :as queries]
            [clsql.codegen :as codegen]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.set :refer [difference]]))

(defn- get-env []
  (System/getenv))

(defn- keywordize [k]
  (keyword (str/replace k #"_" "-")))

(defn- normalize-database-uri [coll]
  (if-let [uri (:connection-uri coll)]
    (assoc coll :connection-uri
                (str (when-not (str/starts-with? uri "jdbc:") "jdbc:") uri))
    coll))

(defn- find-env-keys []
  (let [vals (->> (get-env)
                  (map (fn [[k v]] [(str/lower-case k) v]))
                  (filter (fn [[k _]] (str/starts-with? k "clsql")))
                  (map (fn [[k v]] [(str/replace k #"clsql_" "") v]))
                  (map (fn [[k v]] [(keywordize k) v]))
                  (into {}))]
    (if (empty? vals) nil
                      (normalize-database-uri vals))))

(defn detect-database-config []
  (or @config/database-configuration
      (reset! config/database-configuration (find-env-keys))))

(defn detect-database-config! []
  (or (detect-database-config)
      (throw (ex-info "clsql: Can't find database configuration"
                      {:error   "No database configuration"
                       :details "https://github.com/victorgama/clsql/wiki/errors"}))))

(defn- read-queries [name]
  (let [final-name (str name
                        (when-not (str/ends-with? name ".sql") ".sql"))
        query-path (io/file @config/queries-directory final-name)]
    (queries/parse-queries (.getCanonicalPath query-path))))

(defmacro defquery-refer* [name refers]
  (throw-if (not (vector? refers))
            "expected references to be a vector")
  (let [specs (read-queries name)
        references (set (map keyword refers))
        get-name #(get-in % [:header :name])
        specs-to-load (filter #(references (get-name %)) specs)
        found-specs (set (map get-name specs-to-load))
        unknown-refs (difference references found-specs)]
    (throw-if (seq unknown-refs)
              (apply str
                     (apply str "Could not find the following definition(s) in "
                            (symbol name) ": "
                            (interpose \, unknown-refs))
                     (apply str "\nAvailable definition(s): "
                            (interpose \, (doall (map get-name specs))))))
    (let [queries (map (fn [spec] `(codegen/create-query ~spec)) specs-to-load)]
      `(do ~@queries))))

(defmacro defquery-as* [name as]
  (let [specs (read-queries name)
        ns-name (gensym (str "clsql-query-container-" name "-"))
        ns (create-ns ns-name)
        queries (map (fn [spec] `(codegen/create-query-in-ns ~spec ~ns)) specs)]
    `(do ~@queries
         (alias (symbol (quote ~as)) (quote ~ns-name)))))

(defmacro require-query
  "Finds, parses, and imports queries from a given filename during compilation
  time.

  Options

  Options are provided through a hashmap. It must be used to instruct whether
  queries are being imported directly into this file, or to an isolated
  namespace. Both options were designed to work like the :require option
  provided to the `ns` form.
  Recognized options: :as, :refer

  :as: accepts a symbol indicating in which namespace to load functions. This
  will load all available queries into the provided namespace.
  :refer: accepts a vector of symbols indicating which queries will be loaded
  in the current namespace.

  Example:

  The following would load 'user-queries' into a namespace 'user':
  (require-query user-queries :as user)

  The following would load a query named 'deactivated-users' into the current
  namespace:
  (require-query user-queries :refer [deactivated-users])"
  [name & {:keys [as refer]}]
  (throw-if (and (nil? as)
                 (nil? refer))
            "require-query must be used with either :as or :refer")
  (throw-if (and (some? refer)
                 (not (vector? refer)))
            ":refer must be a vector")

  (if-not (nil? as)
    `(defquery-as* ~name ~as)
    `(defquery-refer* ~name ~refer)))

(defmacro require-queries
  "Finds, parses, and imports one or more queries from files, using provided
  rules on whether to isolate them in another namespace or just load it
  into the current one.

  Options
  Options are provided through vectors that must contain the filename in which
  the queries were defined, and either an :as or :refer option to indicate where
  to load them.
  Recognized options: :as, :refer

  :as: accepts a symbol indicating in which namespace to load functions. This
  will load all available queries into the provided namespace.
  :refer: accepts a vector of symbols indicating which queries will be loaded
  in the current namespace.

  Example:

  The following would load 'user-queries' into a namespace 'user', and a
  'deactivated-users' query into the current namespace:
  (require-queries [user-queries :as user]
                   [user-queries :refer [deactivated-users])"
  [& params]
  (throw-if (some #(not (vector? %)) params)
            "require-queries require vectors as arguments")
  (let [requires (map (fn [param]
                        `(require-query ~@param)) params)]
    `(do ~@requires)))
