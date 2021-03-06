(ns querymanager.execution
  (:use [querymanager.transform :only [java2cljmap]]
        [disk.tablemanager :only [read-table attr? val-accessor
                                  create-table insert drop-table
                                  create-view drop-view]]
        [clojure.contrib.combinatorics :only [cartesian-product]]
        [clojure.contrib.seq-utils :only [separate positions]])
  (:require [clojure.string :only [replace]]
            [clojure.set :only [difference intersection union]])
  (:import [querymanager.lexical Yylex]
           [querymanager.syntax DBParser sym]
           [java.io FileInputStream StringReader]
           [java_cup.runtime Symbol]
           [java.util.regex Pattern]
           [disk.tablemanager Table]))

(defn lex [file-name]
  (with-open [fis (FileInputStream. file-name)]
    (let [lex (Yylex. fis)]
      (loop [tok (.next_token lex)]
        (when-not (= (.sym tok) sym/EOF)
          (println (.value tok) " " (.sym tok))
          (recur (.next_token lex)))))))

(defn parse [expr]
  (let [parse-tree (-> expr
                       StringReader.
                       Yylex.
                       DBParser.
                       .parse)]
    (java2cljmap (.value parse-tree))))

(defn table-product [tables]
  (let [[headers types] (map #(map % tables) [:header :type])
        tuples  (map (fn [t] (apply concat t))
                     (apply cartesian-product
                            (map #(:tuples %) tables)))]
    (Table. nil
            (apply concat headers)
            (apply concat types)
            nil
            tuples)))

(defn- build-regex
  "Builds a regex from SQL pattern string s"
  [s]
  (-> s
      Pattern/quote
      (clojure.string/replace "''" "'")
      (clojure.string/replace "_" "\\E.\\Q")
      (clojure.string/replace "%" "\\E.*\\Q")
      re-pattern))

(defn- check-get-val
  "Check whether tuples contain only one tuple of a single attribute.
If so, return the value of the attribute.
Ohterwise, throw an exception"
  [tuples]
  (if (or (> (count tuples) 1)
          (> (count (first tuples)) 1))
    (throw (Exception. "Invalid subquery."))
    (ffirst tuples)))

(defn- extend-env [[header tuple] new-header new-tuple]
  [(concat (if header header []) new-header)
   (concat (if tuple tuple []) new-tuple)])

(def comp-ops
  {:EQ =,:NEQ not=,
   :LT #(< (compare %1 %2) 0),
   :GT #(> (compare %1 %2) 0),
   :LE #(<= (compare %1 %2) 0),
   :GE #(>= (compare %1 %2) 0)})
(def set-ops
  {:EXCEPT clojure.set/difference,
   :INTERSECT clojure.set/intersection,
   :UNION clojure.set/union})
(declare exec)
(defmulti selection-test
  (fn [[test-type & _] tuples header outer-env]
    (if (comp-ops test-type)
      :COMP
      test-type)))
(defmethod selection-test :COMP
  [[test-type & args] tuples header outer-env]
  (let [[acc1 acc2] (map #(val-accessor % header outer-env) args)
        op (test-type comp-ops)]
    (separate #(op (acc1 %) (acc2 %)) tuples)))
(defmethod selection-test :RANGE
  [[_ f attr lower upper] tuples header outer-env]
  (let [op (comp-ops :LT)
        [acc accl accu] (map #(val-accessor % header outer-env)
                             [attr lower upper])]
    (separate #(f (and (op (accl lower) (acc %))
                       (op (acc %) (accu upper))))
              tuples)))
(defmethod selection-test :IN
  [[_ f attr valueset] tuples header outer-env]
  (let [acc (val-accessor attr header outer-env)]
    (separate #(f (valueset (acc %)))
              tuples)))
(defmethod selection-test :LIKE
  [[_ f attr pattern] tuples header outer-env]
  (let [acc (val-accessor attr header outer-env)]
    (separate #(f (re-matches (build-regex pattern)
                              (acc %)))
              tuples)))
(defmethod selection-test :QUERY
  [[_ test-type & args] tuples header outer-env]
  (cond (comp-ops test-type)
        (let [[attr qualifier query] args
              qualifier-f (cond (nil? qualifier)
                                (fn [op val ts]
                                  (op val (check-get-val ts)))
                                (= qualifier :ALL)
                                (fn [op val ts]
                                  (every? #(op val %) (apply concat ts)))
                                (= qualifier :ANY)
                                (fn [op val ts]
                                  (some #(op val %) (apply concat ts))))
              acc (val-accessor attr header outer-env)
              op (comp-ops test-type)]
          (separate #(qualifier-f op
                                  (acc %)
                                  (:tuples (exec query
                                                 (extend-env outer-env
                                                             header %))))
                    tuples))
        (= test-type :RANGE)
        (throw (Exception. "RANGE subquery not implemented yet"))
        (= test-type :IN)
        (let [[f attr query] args
              acc (val-accessor attr header outer-env)
              ]
          (separate #(f ((set (map first
                                   (:tuples (exec query
                                                  (extend-env outer-env
                                                              header %)))))
                         (acc %)))
                    tuples))))
(defmethod selection-test :default
  [_ _ _ _]
  (println "not implemented yet"))

(defn- selection-and [and-conds tuples header outer-env]
  (reduce (fn [res c]
            (let [[p f] (selection-test c (first res) header outer-env)]
              [p (concat (last res) f)]))
          [tuples []]
          and-conds))

(defn- grouping [group-attr tuples header outer-env]
  (if (nil? group-attr)
    [tuples]
    (let [attr-accessor (val-accessor group-attr header outer-env)]
      (vals (group-by attr-accessor tuples)))))
(def aggr-ops
  {:AVG #(/ (reduce + %) (count %)),
   :SUM #(reduce + %),
   :MIN #(apply min %),
   :MAX #(apply max %),
   :COUNT count})
(defn- projecting [attr tuples header outer-env]
  (cond (= (first attr) :AGGR)
        (let [[op qualifier attr] (second attr)
              acc (val-accessor attr header outer-env)]
          [((aggr-ops op) (map acc tuples))])
        (attr? attr)
        (let [acc (val-accessor attr header outer-env)]
          (map acc tuples))))
(defmulti exec (fn [exp outer-env] (first exp)))
(defmethod exec :from [[_ & ts] outer-env]
  (let [tables
        (apply map (fn [t]
                     (cond (instance? Table t) t
                           (t :table)
                           (let [tmp (read-table (t :table) (t :alias))]
                             (if (= (first tmp) :query)
                               (read-table (exec tmp outer-env) (t :alias))
                               tmp))
                           (t :query)
                           (read-table (exec (t :query) outer-env)
                                         (t :alias))))
               ts)]
    (table-product tables)))
(defmethod exec :where [[_ conditions table] outer-env]
  (let [{:keys [tuples header type] :as table} (exec table outer-env)]
    (if conditions
      (let [[tuples]
            (reduce (fn [res and-c]
                      (let [[p f]
                            (selection-and and-c (last res) header outer-env)]
                        [(concat (first res) p) f]))
                    [[] tuples]
                    conditions)]
        (assoc table :tuples tuples))
      table)))
(defmethod exec :query [[_ {:keys [select from where groupby]}] outer-env]
  (let [{:keys [header tuples] :as table}
        (exec [:where where [:from from]] outer-env)]
    (if (some #(= % :STAR) select)
      table
      (let [[attrs aliases] (reduce (fn [[r1 r2] [a1 a2]]
                                       [(conj r1 a1)
                                        (conj r2 (if a2 [nil a2] a1))])
                                     [[] []]
                                     select)]
        (loop [groups (grouping (first groupby) tuples header outer-env)
               res []]
          (if (seq groups)
            (let [tuples (first groups)]
              (recur (rest groups)
                     (conj res (apply map (fn [& args] (vec args))
                                      (map #(projecting % tuples header outer-env)
                                           attrs)))))
            (Table. nil aliases nil nil (apply concat res))))))))
(defmethod exec :SET [[_ set-op q1 q2] outer-env]
  (let [{h1 :header t1 :tuples type1 :type} (exec q1 outer-env)
        {h2 :header t2 :tuples} (exec q2 outer-env)]
    (if (= (vec h1) (vec h2))
      (Table. nil h1 type1 nil (vec ((set-ops set-op) (set t1) (set t2))))
      (throw (Exception. "Invalid set operation")))))

(defmethod exec :createtable [[_ name attrs] _]
  (create-table name attrs))

(defn- valid-type? [[type num] value]
  (cond (or (= type :CHAR) (= type :VARCHAR)) (<= (count value) num)
        (= type :SMALLINT) (and (< value 32767) (> value -32768))
        (= type :INT) (and (< value (dec (bit-shift-left 1 31)))
                           (> value (- (bit-shift-left 1 31))))
        :else true))
(defn- valid-insertion? [{:keys [header type constraint tuples]} attrs values]
  (let [header (map second header)
        attr-col (fn [attr]
                   (first (positions #(= attr %) header)))
        valid-key? (cond (constraint :PRIMARYKEY)
                         (fn [v vs] (and (not (nil? v))
                                         (every? #(not= v %) vs)))
                         (constraint :UNIQUE)
                         (fn [v vs] (every? #(not= v %) vs)))
        keycons (or (constraint :PRIMARYKEY) (constraint :UNIQUE))]
    (when (or (= (count attrs) (count values))
              (= (count header) (count values)))
      (loop [attrs (if (nil? attrs) header attrs) values values]
        (let [[a & as] attrs
              [v & vs] values
              col (attr-col a)]
          (cond (nil? attrs)
                true
                (and (valid-type? (nth type col) v)
                     (if (= a keycons)
                       (valid-key? v (map #(nth % col) tuples))
                       true))
                (recur as vs)
                :else nil))))))

(defmethod exec :insert [[_ name attrs values] _]
  (let [table (read-table name name)]
    (if (valid-insertion? table attrs values)
      (insert table attrs values)
      (throw (Exception. "Invalid Insertion")))))

(defmethod exec :drop [[_ name] _]
  (drop-table name))

(defmethod exec :createview [[_ name query] _]
  (create-view name query))

(defmethod exec :dropview [[_ name] _]
  (drop-view name))