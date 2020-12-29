(ns prometheus-api.client
  "Client library to the [Prometheus HTTP API](https://prometheus.io/docs/prometheus/latest/querying/api/).

  All methods take a connection `conn` as a first argument. It's just a simple map in the form:
  ```clojure
  {:url \"<prometheus_base_url>\"}
  ```
  The :url field value must contain the base URL of the Prometheus instance. It is not expected to contain the \"/api/v1\" part.

  E.g. to connect to a local Prometheus instance:
  ```clojure
  {:url \"http://localhost:9090\"}
  ```"
  (:require [clj-http.client :as http-client]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [jsonista.core :as json])
  (:use [slingshot.slingshot :only [try+]]))


(declare http-get-and-maybe-parse-data
         http-post-and-maybe-parse-data
         http-get-and-maybe-parse-ts-result
         with-only-non-nil

         convert-result-in-body
         convert-result-ts
         normalize-inst
         normalize-string
         normalize-point
         normalize-points)



;; DYNAMIC VARS

(def ^:dynamic content-level
  "Level of content returned for API response calls.
  Valid values:
  - `::http-client`: raw response from `clj-http.client`, good for debugging
  - `::body`: HTTP body parsing into a clojure data structure
  - `::data`: \"data\" part of the prometheus response
  - `::best`: only the most sensible data for each endpoint (default)"
  ::best)


(def ^:dynamic convert-result
  "If true, parse convert the results into more clojuresque data structures.
  Time series are converted into maps, their timestamps converted into inst and scalar values parsed.

  Default value is true.

  Gets ignored if [[content-level]] is `::http-client`"
  true)


(def ^:dynamic api-prefix
  "Constant part in the API url.
  Can be overridden e.g. in case of passing through a reverse proxy."
  "/api/v1")



;; QUERY: DATA

(defn query
  "Query data with `q` at an instant `:at`.
  When `:at` is omitted, last data point is returned.
  Optionally, a `:timeout` can be specified.

  If you want to query over a time range, use [[query-range]].

  This corresponds to the [/query endpoint](https://prometheus.io/docs/prometheus/latest/querying/api/#instant-queries)."
  [conn q & {:keys [at timeout]}]
  (http-get-and-maybe-parse-ts-result conn
                                      "/query"
                                      {"query"   q
                                       "time"    (normalize-inst at)
                                       "timeout" timeout}))


(defn query-range
  "Query data with `q` over a time range (from `start` to `end` with `step` duration between points).
  Optionally, a `:timeout` can be specified.

  If you want to query only at a given instant, use [[query]].

  This corresponds to the [/query_range endpoint](https://prometheus.io/docs/prometheus/latest/querying/api/#range-queries)."
  [conn q start end step & {:keys [timeout]}]
  (http-get-and-maybe-parse-ts-result conn
                                      "/query_range"
                                      {"query"   q
                                       "start"   (normalize-inst start)
                                       "end"     (normalize-inst end)
                                       "step"    step
                                       "timeout" timeout}))



;; QUERY: META-DATA

(defn series
  "Query series according to list of `series-matchers`.

  Optionally, we can filter on those present in a time range (with `:start` and `:end`).

  This corresponds to the [/series endpoint](https://prometheus.io/docs/prometheus/latest/querying/api/#finding-series-by-label-matchers)."
  [conn series-matchers & {:keys [start end]}]

  (http-get-and-maybe-parse-data conn
                                 "/series"
                                 {"match[]" series-matchers
                                  "start" (normalize-inst start)
                                  "end"   (normalize-inst end)}))


(defn labels
  "Get the list of label names.

  Optionally, we can filter on those present in a time range (with `:start` and `:end`).

  This corresponds to the [/labels endpoint](https://prometheus.io/docs/prometheus/latest/querying/api/#getting-label-names)."
  [conn & {:keys [start end]}]
  (http-get-and-maybe-parse-data conn
                                 "/labels"
                                 {"start" (normalize-inst start)
                                  "end"   (normalize-inst end)}))


(defn values-for-label
  "Get the list of values for a `label` name.

  Optionally, we can filter on those present in a time range (with `:start` and `:end`).

  This corresponds to the [/label/<label_name>/values endpoint](https://prometheus.io/docs/prometheus/latest/querying/api/#querying-label-values)."
  [conn label & {:keys [start end]}]
  (http-get-and-maybe-parse-data conn
                                 (str "/label/" label "/values")
                                 {"start" (normalize-inst start)
                                  "end"   (normalize-inst end)}))



;; TARGET DISCOVERY

(defn targets
  "Get the list of targets gathered through target discovery.

  Optionally, we can filter on the `:state` of the targets.
  Valid values are: \"active\", \"dropped\" & \"any\" (same as when left empty)

  This corresponds to the [/targets endpoint](https://prometheus.io/docs/prometheus/latest/querying/api/#targets)."
  [conn & {:keys [state]}]
  (http-get-and-maybe-parse-data conn
                                 "/targets"
                                 {"state" state}))


(defn targets-metadata
  "Get metadata about metrics scrapped from targets.

  Optionally, we can filter on a specific `:metric` and `:label-matcher` (\"match_target\" URL parameter).
  A `:limit` can also be optionally specified.

  This corresponds to the [/targets/metadata endpoint](https://prometheus.io/docs/prometheus/latest/querying/api/#querying-target-metadata)."
  [conn & {:keys [label-matcher metric limit]}]
  (http-get-and-maybe-parse-data conn
                                 "/targets/metadata"
                                 {"match_target" label-matcher
                                  "metric"       metric
                                  "limit"        limit}))


(defn metadata
  "Get metadata about metrics.

  Same as [[targets-metadata]] except doesn't contextualize with target names.

  Optionally, we can filter on a specific `:metric`.
  A `:limit` can also be optionally specified.

  This corresponds to the [/metadata endpoint](https://prometheus.io/docs/prometheus/latest/querying/api/#querying-metric-metadata)."
  [conn & {:keys [metric limit]}]
  (http-get-and-maybe-parse-data conn
                                 "/metadata"
                                 {"metric"       metric
                                  "limit"        limit}))



;; ALERTING & RECORDING RULES

(defn rules
  "Get the list of alerting and / or recording rules.

  Optionally, we can filter on the `:type` of rule.
  Valid values are: \"alert\" & \"record\"

  This corresponds to the [/rules endpoint](https://prometheus.io/docs/prometheus/latest/querying/api/#rules)."
  [conn & {:keys [type]}]
  (http-get-and-maybe-parse-data conn
                                 "/rules"
                                 {"type" type}))


(defn alerts
  "Get the list of active alerts.

  This corresponds to the [/alerts endpoint](https://prometheus.io/docs/prometheus/latest/querying/api/#alerts)."
  [conn]
  (http-get-and-maybe-parse-data conn
                                 "/alerts"))


(defn alertmanagers
  "Get an overview of the current state of the Prometheus alertmanager discovery.

  This corresponds to the [/alertmanagers endpoint](https://prometheus.io/docs/prometheus/latest/querying/api/#alertmanagers)."
  [conn]
  (http-get-and-maybe-parse-data conn
                                 "/alertmanagers"))



;; STATUS & CONFIG

(defn config
  "Get the currently loaded configuration file.

  This corresponds to the [/status/config endpoint](https://prometheus.io/docs/prometheus/latest/querying/api/#config)."
  [conn]
  (http-get-and-maybe-parse-data conn
                                 "/status/config"))


(defn flags
  "Get the instance configuration flags.

  This corresponds to the [/status/flags endpoint](https://prometheus.io/docs/prometheus/latest/querying/api/#flags)."
  [conn]
  (http-get-and-maybe-parse-data conn
                                 "/status/flags"))


(defn build-information
  "Get various build information about the instance.

  This corresponds to the [/status/buildinfo endpoint](https://prometheus.io/docs/prometheus/latest/querying/api/#build-information)."
  [conn]
  (http-get-and-maybe-parse-data conn
                                 "/status/buildinfo"))


(defn tsdb-stats
  "Get various various stats about the cardinality of the TSDB.

  This corresponds to the [/status/tsdb endpoint](https://prometheus.io/docs/prometheus/latest/querying/api/#tsdb-stats)."
  [conn]
  (http-get-and-maybe-parse-data conn
                                 "/status/tsdb"))



;; TSDB ADMIN API

(defn snapshot
  "Creates a snapshot of all current data into snapshots/<datetime>-<rand> under the TSDB's data directory.
  The created directory is returned as a response.

  Setting `:skip_head` to true allows skipping snapshotting data present only in the head block (not yet compacted on disk).

  This corresponds to the [/admin/tsdb/snapshot endpoint](https://prometheus.io/docs/prometheus/latest/querying/api/#snapshot)."
  [conn & {:keys [skip-head]}]
  (let [data (http-post-and-maybe-parse-data conn
                                             "/admin/tsdb/snapshot"
                                             {"skip_head" skip-head})]
    (if (= content-level ::best)
      (get data "name")
      data)))


(defn delete-series
  "Deletes data for a selection of series (matched by a list of `series-matchers`) in optional time range (bound from `:start` to `:end`).

  If fails, returns an exception ([Slingshot](https://github.com/scgilardi/slingshot) Stone to be precise) with :status 204.

  The actual data will still exist on disk and will be cleaned up in future compactions. This can be forced by calling the [[clean-tombstones]] API endpoint.

  This corresponds to the [/admin/tsdb/delete_series endpoint](https://prometheus.io/docs/prometheus/latest/querying/api/#delete-series)."
  [conn series-matchers & {:keys [start end]}]
  (binding [content-level ::http-client]
    (http-post-and-maybe-parse-data conn
                                    "/admin/tsdb/delete_series"
                                    {"match[]" series-matchers
                                     "start"   start
                                     "end"     end}))
  nil)


(defn clean-tombstones
  "creates a snapshot of all current data into snapshots/<datetime>-<rand> under the TSDB's data directory.
  The created directory is returned as a response.

  If fails, returns an exception ([Slingshot](https://github.com/scgilardi/slingshot) Stone to be precise) with :status 204.

  This corresponds to the [/admin/tsdb/clean_tombstones endpoint](https://prometheus.io/docs/prometheus/latest/querying/api/#clean-tombstones)."
  [conn]
  (binding [content-level ::http-client]
    (http-post-and-maybe-parse-data conn
                                    "/admin/tsdb/clean_tombstones"))
  nil)



;; UTILS: TIME

(defn- normalize-inst [i]
  (cond
    (inst? i)
    (float (/ (inst-ms i) 1000))

    :default
    i))


(defn- prom-timestamp->inst [ts]
  ;; REVIEW: `clojure.instant/parse-timestamp` might be a better fit for this but I struggle to find a working example for this use-case.
  (java.sql.Timestamp. (* ts 1000)))



;; UTILS: RESULT PARSING / CONVERSION

(defn- maybe-convert-result-in-body [body has-result]
  (if (and has-result
           convert-result)
    (convert-result-in-body body)
    body))


(defn- convert-result-in-body
  "Takes an HTTP response from the API endpoint and converts it to a Clojure data structure."
  [body]
  (let [data (get body "data")
        res-type (get data "resultType")
        res (get data "result")
        new-res (convert-result-ts res res-type)]
    (assoc-in body ["data" "result"] new-res)))


(defn- convert-result-ts [res res-type]
  (case res-type
    "matrix"
    (mapv (fn [series]
            (update series "values" normalize-points)) res)

    "vector"
    (mapv (fn [series]
            (update series "value" normalize-point)) res)

    "scalar"
    (normalize-point res)

    "string"
    (normalize-string res)

    (throw (ex-info "Unexpected \"data.resultType\" in HTTP body"
                    {:ex-type ::unexpected-prometheus-result-type,
                     :input res-type}))))


(defn- normalize-string [[ts s]]
  {(prom-timestamp->inst ts) s})


(defn- normalize-point [[ts v :as point]]
  {(prom-timestamp->inst ts) (edn/read-string v)})


(defn- normalize-points [points]
  (into
   {}
   (map normalize-point points)))



;; UTILS: MAP

(defn- with-only-non-nil [map]
  (into
   {}
   (remove #(nil? (second %)) map)))



;; UTILS: HTTP REQUESTS WRAPPERS

(defn- http-request-and-maybe-parse [http-method conn endpoint url-params has-result]
  (let [url (str (:url conn) api-prefix endpoint)
        url-params (with-only-non-nil url-params)
        raw-resp (http-method url
                              {:accept :json
                               :query-params url-params})]
    (if (= content-level ::http-client)
      raw-resp
      (let [body (-> raw-resp
                     :body
                     json/read-value
                     (maybe-convert-result-in-body has-result))]
        (case content-level
          ::body
          body

          ::data
          (get body "data")

          ::best
          (if has-result
            (get-in body ["data" "result"])
            (get body "data"))

          (throw (ex-info "Unexpected `content-level`" {:ex-type ::unexpected-content-level,
                                                        :input content-level})))))))


(defn- http-get-and-maybe-parse-data [conn endpoint & [params]]
  (http-request-and-maybe-parse http-client/get conn endpoint params false))


(defn- http-post-and-maybe-parse-data [conn endpoint & [url-params]]
  (http-request-and-maybe-parse http-client/post conn endpoint url-params false))


(defn- http-get-and-maybe-parse-ts-result [conn endpoint & [params]]
  (http-request-and-maybe-parse http-client/get conn endpoint params true))
