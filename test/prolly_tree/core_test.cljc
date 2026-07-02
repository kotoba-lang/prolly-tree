(ns prolly-tree.core-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing] :include-macros true])
            [prolly-tree.core :as pt]
            [ipld.core :as ipld]))

(defn- mem-store []
  (let [store (atom {})]
    {:put! (fn [cid bytes] (swap! store assoc cid bytes))
     :get-fn (fn [cid] (get @store cid))
     :store store}))

(defn- key-str
  "Zero-padded \"key-NNNN\", portable (no `format`, which is :clj-only)."
  [i]
  (let [s (str i)]
    (str "key-" (apply str (repeat (- 4 (count s)) "0")) s)))

(deftest round-trip-small
  (let [{:keys [put! get-fn]} (mem-store)
        entries (sort-by first [["a" 1] ["b" 2] ["c" 3]])
        root (pt/build-tree put! entries)]
    (is (some? root))
    (doseq [[k v] entries]
      (is (= v (pt/lookup get-fn root k))))
    (is (nil? (pt/lookup get-fn root "zzz-missing")))))

(deftest round-trip-many-multi-level
  (let [{:keys [put! get-fn store]} (mem-store)
        entries (sort-by first (map (fn [i] [(key-str i) i]) (range 2000)))
        root (pt/build-tree put! entries)]
    (is (some? root))
    (doseq [[k v] entries]
      (is (= v (pt/lookup get-fn root k))))
    (testing "2000 entries at ~1/256 chunking builds a multi-node tree"
      (is (> (count @store) 8)))))

(deftest empty-tree
  (let [{:keys [put! get-fn]} (mem-store)]
    (is (nil? (pt/build-tree put! [])))
    (is (nil? (pt/lookup get-fn nil "anything")))))

(deftest scan-prefix-finds-matching-keys
  (let [{:keys [put! get-fn]} (mem-store)
        entries (sort-by first [["app/1" 1] ["app/2" 2] ["zzz/1" 3]])
        root (pt/build-tree put! entries)
        found (pt/scan-prefix get-fn root "app/")]
    (is (= #{["app/1" 1] ["app/2" 2]} (set found)))))

(deftest internal-children-are-real-ipld-links
  ;; decode a multi-level tree's root straight off the block store: children
  ;; must be [max-key <tag-42 Link>], walkable by generic ipld/links with no
  ;; prolly-specific schema knowledge.
  (let [{:keys [put! get-fn]} (mem-store)
        entries (sort-by first (map (fn [i] [(key-str i) i]) (range 2000)))
        root (pt/build-tree put! entries)
        node (ipld/decode (get-fn root))]
    (is (= "internal" (get node "kind")))
    (is (seq (ipld/links node)))
    (is (every? ipld/link? (map second (get node "children"))))
    ;; every linked CID is fetchable and re-derives its own address
    (doseq [cid (ipld/links node)]
      (is (= cid (ipld/cid (get-fn cid)))))))

(deftest scan-prefix-prunes-blocks
  ;; Range-pruning: a prefix scan must return the SAME matches as before, but
  ;; fetch far fewer blocks than a full walk (ADR-2607022330 addendum 3 / #16).
  (let [store (atom {})
        gets  (atom 0)
        put!  (fn [cid bytes] (swap! store assoc cid bytes))
        get-fn (fn [cid] (swap! gets inc) (get @store cid))
        ;; 3000 keys across 3 prefixes → a multi-level tree with many leaves
        entries (sort-by first
                         (for [p ["aaa/" "mmm/" "zzz/"], i (range 1000)]
                           [(str p (key-str i)) (str "v" i)]))
        root (pt/build-tree put! entries)
        total-blocks (count @store)]
    (testing "correctness: prefix returns exactly its 1000 matches"
      (let [rows (pt/scan-prefix get-fn root "mmm/")]
        (is (= 1000 (count rows)))
        (is (every? (fn [[k _]] (clojure.string/starts-with? k "mmm/")) rows))))
    (testing "pruning fetches fewer blocks than a full scan"
      (let [pruned @gets]
        (reset! gets 0)
        (pt/scan-prefix get-fn root "")            ; full scan touches every block
        (let [full @gets]
          (is (< pruned full)
              (str "pruned=" pruned " must be < full=" full " (total-blocks=" total-blocks ")")))))
    (testing "absent prefix fetches only the path, returns nothing"
      (reset! gets 0)
      (is (= [] (pt/scan-prefix get-fn root "nope/")))
      (is (< @gets total-blocks) "absent-prefix scan is pruned, not a full walk"))))
