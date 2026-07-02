(ns prolly-tree.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [prolly-tree.core :as pt]))

(defn- mem-store []
  (let [store (atom {})]
    {:put! (fn [cid bytes] (swap! store assoc cid bytes))
     :get-fn (fn [cid] (get @store cid))
     :store store}))

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
        entries (sort-by first (map (fn [i] [(format "key-%04d" i) i]) (range 2000)))
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
