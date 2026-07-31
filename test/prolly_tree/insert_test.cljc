(ns prolly-tree.insert-test
  "History independence is the whole contract.

  A prolly tree earns its place by producing the same CIDs for the same logical
  content no matter how that content was assembled. If an incremental insert
  can produce a tree a rebuild would not, then two replicas holding identical
  data can disagree about their root hash, and every equality check above this
  layer -- CAS, dedup, replication -- is quietly wrong. So the property is
  asserted directly, over randomized key sets and insertion orders, rather than
  spot-checked on a hand-picked example."
  (:require [clojure.test :refer [deftest is testing]]
            [prolly-tree.core :as pt]))

(defn- store []
  (let [blocks (atom {})]
    {:put! (fn [cid bytes] (swap! blocks assoc cid bytes) cid)
     :get-fn (fn [cid] (get @blocks cid))
     :blocks blocks}))

(defn- key-str
  "Fixed-width so string order matches numeric order. `format` is Clojure-only
  and this file is .cljc."
  [n]
  (let [d (str (Math/abs (long n)))
        pad (subs "000000" 0 (max 0 (- 6 (count d))))]
    (str "k" (when (neg? n) "-") pad d)))

(defn- build [put! pairs]
  (pt/build-tree put! (vec (sort-by first pairs))))

(defn- rebuild-root
  "Root CID of a full rebuild of `pairs`, in a throwaway store."
  [pairs]
  (let [{:keys [put!]} (store)]
    (build put! pairs)))

(defn- insert-root
  "Root CID after building `base` and then inserting `additions` one at a time."
  [base additions]
  (let [{:keys [put! get-fn]} (store)]
    (reduce (fn [root [k v]] (pt/insert put! get-fn root k v))
            (build put! base)
            additions)))

;; ── the property ────────────────────────────────────────────────────────────

(deftest insert-matches-rebuild-single
  (testing "one insert into trees of many sizes, at many positions"
    (doseq [size [1 2 5 50 300 1200]
            position [:front :middle :back]]
      (let [base (mapv (fn [n] [(key-str (* 2 n)) (str "v" n)]) (range size))
            k (case position
                :front (key-str -1)
                :middle (key-str (inc (* 2 (quot size 2))))
                :back (key-str (* 2 (inc size))))
            addition [k "new"]]
        (is (= (rebuild-root (conj base addition))
               (insert-root base [addition]))
            (str "size=" size " position=" position))))))

(deftest insert-matches-rebuild-many
  (testing "a run of inserts, in an order unrelated to key order"
    (doseq [[size adds] [[100 25] [600 60] [2000 40]]]
      (let [base (mapv (fn [n] [(key-str (* 3 n)) (str "v" n)]) (range size))
            additions (mapv (fn [n] [(key-str (inc (* 3 n))) (str "a" n)])
                            (take adds (iterate #(mod (+ % 37) size) 7)))
            expected (rebuild-root (concat base additions))]
        (is (= expected (insert-root base additions))
            (str "size=" size " adds=" (count additions)))))))

(deftest insert-replaces-existing-key
  (testing "an insert on an existing key replaces it, and still matches a rebuild"
    (let [base (mapv (fn [n] [(key-str n) (str "v" n)]) (range 200))
          k (key-str 137)
          expected (rebuild-root (assoc base 137 [k "replaced"]))]
      (is (= expected (insert-root base [[k "replaced"]]))))))

(deftest insert-into-empty
  (let [{:keys [put! get-fn]} (store)]
    (is (= (rebuild-root [["only" "v"]])
           (pt/insert put! get-fn nil "only" "v")))))

;; ── the point: bounded work ─────────────────────────────────────────────────

(deftest insert-writes-far-fewer-blocks-than-a-rebuild
  (testing "an insert touches the path, not the tree — this is the difference
            between a fold that fits in a Worker budget and one that does not"
    ;; A ratio against one tree's block count is not the property -- with a
    ;; ~1/256 boundary rate even 3,000 entries is only a handful of blocks, and
    ;; the ratio says more about the fan-out than about the algorithm. The
    ;; property is that insert cost does NOT grow with the tree.
    (let [written-for (fn [size]
                        (let [base (mapv (fn [n] [(key-str n) (str "v" n)])
                                         (range size))
                              {:keys [put! get-fn blocks]} (store)
                              root (build put! base)
                              before (count @blocks)]
                          (pt/insert put! get-fn root (key-str 999999) "new")
                          (- (count @blocks) before)))
          small (written-for 2000)
          large (written-for 40000)]
      (is (pos? small))
      (is (<= large (+ small 2))
          (str "insert wrote " small " blocks into a 2,000-entry tree and "
               large " into a 40,000-entry one; the cost must not scale with"
               " the tree")))))

(deftest lookup-sees-inserted-values
  (let [{:keys [put! get-fn]} (store)
        base (mapv (fn [n] [(key-str n) (str "v" n)]) (range 500))
        root (reduce (fn [r [k v]] (pt/insert put! get-fn r k v))
                     (build put! base)
                     [["zzz-new" "tail"] ["a-new" "head"]])]
    (is (= "tail" (pt/lookup get-fn root "zzz-new")))
    (is (= "head" (pt/lookup get-fn root "a-new")))
    (is (= "v250" (pt/lookup get-fn root (key-str 250))))
    (is (nil? (pt/lookup get-fn root "absent")))))
