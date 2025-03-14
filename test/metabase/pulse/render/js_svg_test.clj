(ns metabase.pulse.render.js-svg-test
  "Testing of the svgs produced by the graal js engine and the static-viz bundle. The model is

  query-results -> js engine with bundle -> svg-string -> svg png renderer

  the svg png renderer does not understand nested html elements so we ensure that there are no divs, spans, etc in the
  resulting svg."
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [metabase.public-settings :as public-settings]
            [metabase.pulse.render.js-engine :as js]
            [metabase.pulse.render.js-svg :as js-svg])
  (:import org.apache.batik.anim.dom.SVGOMDocument
           [org.graalvm.polyglot Context Value]
           [org.w3c.dom Element Node]))

(def parse-svg #'js-svg/parse-svg-string)

(use-fixtures :each
  (fn warn-possible-rebuild
    [thunk]
    (testing "[PRO TIP] If this test fails, you may need to rebuild the bundle with `yarn build-static-viz`\n"
      (thunk))))

(deftest post-process-test
  (let [svg   "<svg ><g><line/></g><g><rect/></g><g><circle/></g></svg>"
        nodes (atom [])]
    (#'js-svg/post-process (parse-svg svg)
                           (fn [^Node node] (swap! nodes conj (.getNodeName node))))
    (is (= ["svg" "g" "line" "g" "rect" "g" "circle"] @nodes))))

(deftest fix-fill-test
  (let [svg "<svg ><line x1=\"0\" y1=\"260\" x2=\"540\" y2=\"260\" fill=\"transparent\"></line></svg>"

        ^SVGOMDocument document (parse-svg svg)
        ^Element line           (..  document
                                     (getDocumentElement)
                                     (getChildNodes)
                                     (item 0))]
    (is (.hasAttribute line "fill"))
    (is (= (.getAttribute line "fill") "transparent"))
    ;; unfortunately these objects are mutable. It does return the line but want to emphasize that is works by
    ;; mutation
    (#'js-svg/fix-fill line)
    (is (not (.hasAttribute line "fill")))
    (is (.hasAttribute line "fill-opacity"))
    (is (= (.getAttribute line "fill-opacity") "0.0"))))

(def ^Context context (delay (#'js-svg/static-viz-context)))

(defn document-tag-seq [^SVGOMDocument document]
  (map #(.getNodeName ^Node %)
       (tree-seq #(instance? Element %)
                 (fn [^Node node]
                   (let [children (.getChildNodes node)]
                     (reduce (fn [cs i] (conj cs (.item children i)))
                             [] (range (.getLength children)))))
                 (.getDocumentElement document))))

(defn normal-svg-elements [tag-set]
  (set/subset? #{"svg" "g"} tag-set))

(defn no-html-elements [tag-set]
  (= #{} (set/intersection #{"div" "span" "p"} tag-set)))

(defn validate-svg-string [chart svg-string]
  (let [tag-seq    (-> svg-string parse-svg document-tag-seq)
        tag-set    (set tag-seq)]
    (testing (str chart " String is valid")
      (is (string? svg-string) "Svg did not return a string"))
    (testing (str " String contains normal svg elements")
      (is (normal-svg-elements tag-set) "Did not contain normal svg elements #{svg g line}"))
    (testing (str chart "String cannot contain html elements as svg renderer errors")
      (is (no-html-elements tag-set) (str "Contained html elements: "
                                          (set/intersection #{"div" "span" "p"}))))))

(defn document-tag-hiccup [^SVGOMDocument document]
  (letfn [(tree [^Node node]
            (into [(.getNodeName node)]
                  (if (instance? org.apache.batik.dom.GenericText node)
                    [(.getWholeText node)]
                    (map tree
                         (when (instance? Element node)
                           (let [children (.getChildNodes node)]
                             (reduce (fn [cs i] (conj cs (.item children i)))
                                     [] (range (.getLength children)))))))))]
    (tree (.getDocumentElement document))))

(defn text-node? [x]
  (and (vector? x) (= (first x) "#text")))

(deftest timelineseries-line-test
  (let [rows     [[#t "2020" 2]
                  [#t "2021" 3]]
        labels   {:left "count" :bottom "year"}
        settings (json/generate-string {:y {:prefix   "prefix"
                                            :decimals 2}})]
    (testing "It returns bytes"
      (let [svg-bytes (js-svg/timelineseries-line rows labels settings)]
        (is (bytes? svg-bytes))))
    (let [svg-string (.asString (js/execute-fn-name @context "timeseries_line" rows labels settings))
          svg-hiccup (-> svg-string parse-svg document-tag-hiccup)]
      (testing "it returns a valid svg string with no html"
        (validate-svg-string :timelineseries-line svg-string))
      (testing "The svg string has formatted axes"
        (let [spec (s/cat :y-axis-labels (s/+ (s/tuple
                                               #{"#text"}
                                               #(and (string? %)
                                                     ;; ["#text" "prefix0.00"]
                                                     (re-matches #"prefix\d+\.\d{2}" %))))
                          :x-axis-labels (s/+ (s/tuple
                                               #{"#text"}
                                               #(and (string? %)
                                                     ;; ["#text" "1/1/2020"]
                                                     (re-matches #"\d+/\d+/\d{4}" %)))))
              text-nodes (->> svg-hiccup (tree-seq vector? rest) (filter text-node?))]
          (is (= true (s/valid? spec text-nodes))
              text-nodes))))))

(deftest timelineseries-bar-test
  (let [rows     [[#t "2020" 2]
                  [#t "2021" 3]]
        labels   {:left "count" :bottom "year"}
        settings (json/generate-string {:y {:prefix   "prefix"
                                            :decimals 4}})]
    (testing "It returns bytes"
      (let [svg-bytes (js-svg/timelineseries-bar rows labels settings)]
        (is (bytes? svg-bytes))))
    (let [svg-string (.asString (js/execute-fn-name @context "timeseries_bar" rows labels settings))
          svg-hiccup (-> svg-string parse-svg document-tag-hiccup)]
      (testing "it returns a valid svg string (no html in it)"
        (validate-svg-string :timelineseries-bar svg-string))
      (testing "The svg string has formatted axes"
        (let [spec (s/cat :y-axis-labels (s/+ (s/tuple
                                               #{"#text"}
                                               #(and (string? %)
                                                     ;; ["#text" "prefix0.0000"]
                                                     (re-matches #"prefix\d+\.\d{4}" %))))
                          :x-axis-labels (s/+ (s/tuple
                                               #{"#text"}
                                               #(and (string? %)
                                                     ;; ["#text" "1/1/2020"]
                                                     (re-matches #"\d+/\d+/\d{4}" %)))))
              text-nodes (->> svg-hiccup (tree-seq vector? rest) (filter text-node?))]
          (is (= true (s/valid? spec text-nodes))
              text-nodes))))))

(deftest area-test
  (let [tl-rows    [[#t "2020" 2]
                    [#t "2021" 3]]
        cat-rows   [["bob" 2]
                    ["dobbs" 3]]
        tl-labels  {:left "count" :bottom "year"}
        cat-labels {:left "count" :bottom "string stuff"}
        settings   (json/generate-string {:y {:prefix   "prefix"
                                              :decimals 4}})]
    (testing "It returns bytes"
      (let [tl-svg-bytes  (js-svg/timelineseries-area tl-rows tl-labels settings)
            cat-svg-bytes (js-svg/categorical-area cat-rows cat-labels settings)]
        (is (bytes? tl-svg-bytes))
        (is (bytes? cat-svg-bytes))))
    (let [tl-svg-string  (.asString (js/execute-fn-name @context "timeseries_area" tl-rows tl-labels settings))
          cat-svg-string (.asString (js/execute-fn-name @context "categorical_area" cat-rows cat-labels settings))]
      (testing "it returns a valid svg string (no html in it)"
        (validate-svg-string :timelineseries-area tl-svg-string)
        (validate-svg-string :categorical-area cat-svg-string)))))

(deftest timelineseries-waterfall-test
  (let [rows     [[#t "2020" 2]
                  [#t "2021" 3]]
        labels   {:left "count" :bottom "year"}
        settings (json/generate-string {:y {:prefix   "prefix"
                                            :decimals 4}})]
    (testing "It returns bytes"
      (let [svg-bytes (js-svg/timelineseries-waterfall rows labels settings)]
        (is (bytes? svg-bytes))))
    (let [svg-string (.asString (js/execute-fn-name @context "timeseries_waterfall" rows labels settings (json/generate-string (public-settings/application-colors))))]
      (testing "it returns a valid svg string (no html in it)"
        (validate-svg-string :timelineseries-waterfall svg-string)))))

(deftest combo-test
  (let [rows1    [[#t "1998-03-01T00:00:00Z" 2]
                  [#t "1999-03-01T00:00:00Z" 3]]
        rows2    [[#t "2000-03-01T00:00:00Z" 3]
                  [#t "2002-03-01T00:00:00Z" 4]]
        ;; this one needs more stuff because of stricter ts types
        series   [{:name          "bob"
                   :color         "#cccccc"
                   :type          "area"
                   :data          rows1
                   :yAxisPosition "left"}
                  {:name          "bob2"
                   :color         "#cccccc"
                   :type          "line"
                   :data          rows2
                   :yAxisPosition "right"}]
        labels   {:left "count" :bottom "year" :right "something"}
        settings {:x {:type "timeseries"
                      :format {:date_style "YYYY"}}
                  :y {:type "linear"
                      :format {:number_style "decimal" :decimals 4}}
                  :colors {}
                  :labels labels}]
    (testing "It returns bytes"
      (let [svg-bytes (js-svg/combo-chart series settings)]
        (is (bytes? svg-bytes))))
    (let [svg-string (.asString (js/execute-fn-name @context "combo_chart"
                                                    (json/generate-string series)
                                                    (json/generate-string settings)
                                                    (json/generate-string {})))]
      (testing "it returns a valid svg string (no html in it)"
        (validate-svg-string :combo-chart svg-string)))))

(deftest categorical-donut-test
  (let [rows [["apples" 2]
              ["bananas" 3]]
        colors {"apples" "red" "bananas" "yellow"}]
    (testing "It returns bytes"
      (let [svg-bytes (js-svg/categorical-donut rows colors)]
        (is (bytes? svg-bytes))))
    (let [svg-string (.asString ^Value (js/execute-fn-name @context "categorical_donut" rows (seq colors)))]
      (validate-svg-string :categorical/donut svg-string))))

(deftest progress-test
  (let [value    1234
        goal     1337
        settings {:color "#333333"}]
    (testing "It returns bytes"
      (let [svg-bytes (js-svg/progress value goal settings)]
        (is (bytes? svg-bytes))))
    (let [svg-string (.asString ^Value
                                (js/execute-fn-name
                                  @context
                                  "progress"
                                  (json/generate-string {:value value :goal goal})
                                  (json/generate-string settings)))]
      (validate-svg-string :progress svg-string))))

(deftest categorical-waterfall-test
  (let [rows     [["apples" 2]
                  ["bananas" 3]]
        labels   {:left "bob" :right "dobbs"}
        settings (json/generate-string {})]
    (testing "It returns bytes"
      (let [svg-bytes (js-svg/categorical-waterfall rows labels {})]
        (is (bytes? svg-bytes))))
    (let [svg-string (.asString ^Value (js/execute-fn-name @context "categorical_waterfall" rows labels settings (json/generate-string (public-settings/application-colors))))]
      (validate-svg-string :categorical/waterfall svg-string))))
