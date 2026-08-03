(ns prolly-tree.diff-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing] :include-macros true])
            [prolly-tree.core :as pt]
            [prolly-tree.diff :as d]))

(defn- mem-store []
  (let [store (atom {})]
    {:put! (fn [cid bytes] (swap! store assoc cid bytes))
     :get-fn (fn [cid] (get @store cid))
     :store store}))

(defn- key-str [i]
  (let [s (str i)]
    (str "repo/" (apply str (repeat (- 5 (count s)) "0")) s)))

(defn- fleet
  "n entries shaped like the real manifest: key = repo/NNNNN, value = a pin."
  [n f]
  (mapv (fn [i] [(key-str i) (f i)]) (range n)))

(deftest equal-roots-cost-nothing
  (testing "the cheap case the whole design rests on: same root, zero blocks read"
    (let [{:keys [put! get-fn]} (mem-store)
          root (pt/build-tree put! (fleet 500 #(str "pin-" %)))
          r (d/diff* get-fn root root)]
      (is (= [] (:added r) (:removed r)))
      (is (= [] (:changed r)))
      (is (zero? (:blocks-read r))
          "equal CIDs must short-circuit before any block is fetched"))))

(deftest detects-change-add-remove
  (let [{:keys [put! get-fn]} (mem-store)
        base (fleet 200 #(str "pin-" %))
        a (pt/build-tree put! base)
        b (pt/build-tree put!
                         (sort-by first
                                  (-> (into [] (remove #(= (key-str 7) (first %))) base)
                                      (conj [(key-str 999) "pin-new"])
                                      (->> (mapv (fn [[k v]]
                                                   (if (= k (key-str 42)) [k "pin-CHANGED"] [k v])))))))
        {:keys [added removed changed]} (d/diff get-fn a b)]
    (is (= [[(key-str 999) "pin-new"]] added))
    (is (= [[(key-str 7) "pin-7"]] removed))
    (is (= [[(key-str 42) "pin-42" "pin-CHANGED"]] changed))))

(deftest skips-shared-subtrees
  (testing "a one-key change in a 4,000-entry tree reads far fewer blocks than the tree has"
    (let [{:keys [put! get-fn store]} (mem-store)
          base (fleet 4000 #(str "pin-" %))
          a (pt/build-tree put! base)
          total-a (count @store)
          b (pt/build-tree put! (mapv (fn [[k v]] (if (= k (key-str 2000)) [k "pin-MOVED"] [k v])) base))
          r (d/diff* get-fn a b)]
      (is (= [[(key-str 2000) "pin-2000" "pin-MOVED"]] (:changed r)))
      (is (< (:blocks-read r) (/ total-a 4))
          (str "structural skip did not happen: read " (:blocks-read r)
               " of " total-a " blocks in the first tree")))))

(deftest nil-roots
  (let [{:keys [put! get-fn]} (mem-store)
        root (pt/build-tree put! (fleet 20 #(str "pin-" %)))]
    (is (= 20 (count (:added (d/diff get-fn nil root)))))
    (is (= 20 (count (:removed (d/diff get-fn root nil)))))
    (is (= {:added [] :removed [] :changed []} (d/diff get-fn nil nil)))))

(deftest changed-keys-is-the-sync-planner-shape
  (let [{:keys [put! get-fn]} (mem-store)
        base (fleet 100 #(str "pin-" %))
        a (pt/build-tree put! base)
        b (pt/build-tree put! (mapv (fn [[k v]]
                                      (if (#{(key-str 3) (key-str 77)} k) [k "moved"] [k v]))
                                    base))]
    (is (= [(key-str 3) (key-str 77)] (d/changed-keys get-fn a b)))))

(deftest insert-path-produces-the-same-diff-as-rebuild
  (testing "core/insert and build-tree must agree, or two nodes comparing roots diverge invisibly"
    (let [{:keys [put! get-fn]} (mem-store)
          base (fleet 300 #(str "pin-" %))
          a (pt/build-tree put! base)
          rebuilt (pt/build-tree put! (mapv (fn [[k v]] (if (= k (key-str 150)) [k "x"] [k v])) base))
          inserted (pt/insert put! get-fn a (key-str 150) "x")]
      (is (= rebuilt inserted) "insert must be root-CID-identical to a full rebuild")
      (is (= (d/diff get-fn a rebuilt) (d/diff get-fn a inserted))))))
