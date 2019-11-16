(ns metabase.sync.analyze.fingerprint.fingerprinters
  "Non-identifying fingerprinters for various field types."
  (:require [bigml.histogram.core :as hist]
            [cheshire.core :as json]
            [clj-time.coerce :as t.coerce]
            [java-time :as t]
            [kixi.stats
             [core :as stats]
             [math :as math]]
            [metabase.models.field :as field]
            [metabase.query-processor.timezone :as qp.timezone]
            [metabase.sync.analyze.classifiers.name :as classify.name]
            [metabase.sync.util :as sync-util]
            [metabase.util :as u]
            [metabase.util
             [date :as du]
             [i18n :refer [trs]]]
            [redux.core :as redux])
  (:import com.bigml.histogram.Histogram
           com.clearspring.analytics.stream.cardinality.HyperLogLogPlus))

(defn col-wise
  "Apply reducing functinons `rfs` coll-wise to a seq of seqs."
  [& rfs]
  (fn
    ([] (mapv (fn [rf] (rf)) rfs))
    ([accs] (mapv (fn [rf acc] (rf (unreduced acc))) rfs accs))
    ([accs row]
     (let [all-reduced? (volatile! true)
           results      (mapv (fn [rf acc x]
                                (if-not (reduced? acc)
                                  (do (vreset! all-reduced? false)
                                      (rf acc x))
                                  acc))
                              rfs accs row)]
       (if @all-reduced?
         (reduced results)
         results)))))

(defn constant-fingerprinter
  "Constantly return `init`."
  [init]
  (fn
    ([] (reduced init))
    ([_] init)
    ([_ _] (reduced init))))

(defn- cardinality
  "Transducer that sketches cardinality using HyperLogLog++.
   https://research.google.com/pubs/pub40671.html"
  ([] (HyperLogLogPlus. 14 25))
  ([^HyperLogLogPlus acc] (.cardinality acc))
  ([^HyperLogLogPlus acc x]
   (.offer acc x)
   acc))

(defmulti fingerprinter
  "Return a fingerprinter transducer for a given field based on the field's type."
  {:arglists '([field])}
  (fn [{:keys [base_type special_type unit] :as field}]
    [(cond
       (du/date-extract-units unit)    :type/Integer
       (field/unix-timestamp? field)   :type/DateTime
       ;; for historical reasons the Temporal fingerprinter is still called `:type/DateTime` so anything that derives
       ;; from `Temporal` (such as DATEs and TIMEs) should still use the `:type/DateTime` fingerprinter
       (isa? base_type :type/Temporal) :type/DateTime
       :else                           base_type)
     (or special_type :type/*)]))

(def ^:private global-fingerprinter
  (redux/post-complete
   (redux/fuse {:distinct-count cardinality
                :nil%           (stats/share nil?)})
   (partial hash-map :global)))

(defmethod fingerprinter :default
  [_]
  global-fingerprinter)

(defmethod fingerprinter [:type/* :type/FK]
  [_]
  global-fingerprinter)

(defmethod fingerprinter [:type/* :type/PK]
  [_]
  (constant-fingerprinter nil))

(prefer-method fingerprinter [:type/* :type/FK] [:type/Number :type/*])
(prefer-method fingerprinter [:type/* :type/FK] [:type/Text :type/*])
(prefer-method fingerprinter [:type/* :type/PK] [:type/Number :type/*])
(prefer-method fingerprinter [:type/* :type/PK] [:type/Text :type/*])
(prefer-method fingerprinter [:type/DateTime :type/*] [:type/* :type/PK])
(prefer-method fingerprinter [:type/DateTime :type/*] [:type/* :type/FK])

(defn- with-global-fingerprinter
  [fingerprinter]
  (redux/post-complete
   (redux/juxt
    fingerprinter
    global-fingerprinter)
   (fn [[type-fingerprint global-fingerprint]]
     (merge global-fingerprint
            type-fingerprint))))

(defmacro ^:private with-reduced-error
  [msg & body]
  `(let [result# (sync-util/with-error-handling ~msg ~@body)]
     (if (instance? Throwable result#)
       (reduced result#)
       result#)))

(defn- with-error-handling
  [rf msg]
  (fn
    ([] (with-reduced-error msg (rf)))
    ([acc]
     (unreduced
      (if (or (reduced? acc)
              (instance? Throwable acc))
        acc
        (with-reduced-error msg (rf acc)))))
    ([acc e] (with-reduced-error msg (rf acc e)))))

(defmacro ^:private deffingerprinter
  [field-type transducer]
  (let [field-type (if (vector? field-type)
                     field-type
                     [field-type :type/*])]
    `(defmethod fingerprinter ~field-type
       [field#]
       (with-error-handling
         (with-global-fingerprinter
           (redux/post-complete
            ~transducer
            (fn [fingerprint#]
              {:type {~(first field-type) fingerprint#}})))
         (trs "Error generating fingerprint for {0}" (sync-util/name-for-logging field#))))))

(defn- earliest
  ([] (java.util.Date. Long/MAX_VALUE))
  ([acc]
   (when (not= acc (earliest))
     (du/date->iso-8601 acc)))
  ([^java.util.Date acc dt]
   (if dt
     (if (.before ^java.util.Date dt acc)
       dt
       acc)
     acc)))

(defn- latest
  ([] (java.util.Date. 0))
  ([acc]
   (when (not= acc (latest))
     (du/date->iso-8601 acc)))
  ([^java.util.Date acc dt]
   (if dt
     (if (.after ^java.util.Date dt acc)
       dt
       acc)
     acc)))

(defprotocol IDateCoercible
  "Protocol for converting objects in resultset to `java.util.Date`"
  (->date ^java.util.Date [this]
    "Coerce object to a `java.util.Date`."))

(defn- date-coercion-timezone-id []
  ;; TODO - if `database-timezone-id` isn't bound we should probably throw an Exception or at the very least log a
  ;; warning
  (t/zone-id (or (qp.timezone/database-timezone-id) "UTC")))

(extend-protocol IDateCoercible
  nil                         (->date [_] nil)
  String                      (->date [this] (-> this du/str->date-time t.coerce/to-date))
  ;; TIMEZONE FIXME — update the fingerprint code to use new `java.time` classes directly, and remove support for
  ;; `java.util.Date` and Joda-Time types
  java.util.Date              (->date [this] this)
  org.joda.time.DateTime      (->date [this] (t.coerce/to-date this))
  Long                        (->date [^Long this] (java.util.Date. this))
  Integer                     (->date [^Integer this] (java.util.Date. (long this)))
  java.time.temporal.Temporal (->date [this] (t/to-java-date this))
  java.time.LocalDate         (->date [this] (t/to-java-date (t/zoned-date-time this (t/local-time 0) (date-coercion-timezone-id))))
  java.time.LocalTime         (->date [this] (t/to-java-date (t/zoned-date-time (t/local-date 1970 1 1) this (date-coercion-timezone-id))))
  java.time.LocalDateTime     (->date [this] (t/to-java-date (t/zoned-date-time this (date-coercion-timezone-id)))))

(deffingerprinter :type/DateTime
  ((map ->date)
   (redux/fuse {:earliest earliest
                :latest   latest})))

(defn- histogram
  "Transducer that summarizes numerical data with a histogram."
  ([] (hist/create))
  ([^Histogram histogram] histogram)
  ([^Histogram histogram x] (hist/insert-simple! histogram x)))

(deffingerprinter :type/Number
  (redux/post-complete
   histogram
   (fn [h]
     (let [{q1 0.25 q3 0.75} (hist/percentiles h 0.25 0.75)]
       {:min (hist/minimum h)
        :max (hist/maximum h)
        :avg (hist/mean h)
        :sd  (some-> h hist/variance math/sqrt)
        :q1  q1
        :q3  q3}))))

(defn- valid-serialized-json?
  "Is x a serialized JSON dictionary or array."
  [x]
  (u/ignore-exceptions
    ((some-fn map? sequential?) (json/parse-string x))))

(deffingerprinter :type/Text
  ((map (comp str u/jdbc-clob->str)) ; we cast to str to support `field-literal` type overwriting:
                                     ; `[:field-literal "A_NUMBER" :type/Text]` (which still
                                     ; returns numbers in the result set)
   (redux/fuse {:percent-json   (stats/share valid-serialized-json?)
                :percent-url    (stats/share u/url?)
                :percent-email  (stats/share u/email?)
                :average-length ((map count) stats/mean)})))

(defn fingerprint-fields
  "Return a transducer for fingerprinting a resultset with fields `fields`."
  [fields]
  (apply col-wise (for [field fields]
                    (fingerprinter
                     (cond-> field
                       ;; Try to get a better guestimate of what we're dealing with on first sync
                       (every? nil? ((juxt :special_type :last_analyzed) field))
                       (assoc :special_type (classify.name/infer-special-type field)))))))
