(ns prolly-tree.core
  "Content-addressed probabilistic B-tree (Prolly Tree) over CID-addressed
  dag-cbor blocks.

  Ports for storage are injected -- `put!` (cid, bytes -> ignored, called
  once per node built) and `get-fn` (cid -> bytes) are passed in by the
  caller. This namespace performs no I/O itself.

  Chunk boundaries are probabilistic (~1/256 average chunk size), matching
  the deleted kotoba-store Rust implementation. LEAF-level boundaries are
  keyed on the entry's key; INTERNAL-level boundaries are keyed on the
  CHILD's CID, never on the child's max-key -- keying an internal boundary
  on max-key re-triggers the same boundary decision at every level for the
  same key, which is an infinite-recursion bug the original Rust
  implementation hit and fixed.

  Honesty note: `multiformats.core/sha256` and `cbor.core/encode` are today
  JVM-only despite living in `.cljc`/portable-named repos, so this
  namespace -- while itself written portably -- only actually runs on the
  JVM until those two grow `:cljs` branches. That is a follow-up, not
  something this namespace works around or hides."
  (:require [clojure.string :as str]
            [multiformats.core :as mf]
            [cbor.core :as cbor]))

(def boundary-bits
  "Chunk boundary fires when the low `boundary-bits` bits of the determinant
  hash are all zero. 8 bits == ~1/256 average chunk size (BOUNDARY_MASK=0xFF
  in the deleted Rust implementation)."
  8)

(defn- utf8-bytes ^bytes [^String s]
  (.getBytes s "UTF-8"))

(defn- boundary?
  "True ~1/(2^boundary-bits) of the time: the last byte of
  sha256(str(x)-as-utf8) is all-zero under `boundary-bits`."
  [x]
  (let [h (mf/sha256 (utf8-bytes (str x)))
        b (bit-and 0xff (aget h (dec (alength h))))]
    (zero? (bit-and b (dec (bit-shift-left 1 boundary-bits))))))

(defn- put-node!
  "CBOR-encode `node`, CID it (dag-cbor codec), call `(put! cid bytes)`,
  return the cid string."
  [put! node]
  (let [bytes (cbor/encode node)
        cid (mf/cidv1-dag-cbor bytes)]
    (put! cid bytes)
    cid))

(defn- chunk-by
  "Split `items` into chunks; a chunk ends right after any item for which
  `boundary-fn` returns true, or at the end of `items`. Never splits an
  empty input."
  [boundary-fn items]
  (if (empty? items)
    []
    (loop [remaining items chunk [] out []]
      (if (empty? remaining)
        (if (seq chunk) (conj out chunk) out)
        (let [item (first remaining)
              chunk' (conj chunk item)]
          (if (boundary-fn item)
            (recur (rest remaining) [] (conj out chunk'))
            (recur (rest remaining) chunk' out)))))))

(defn- build-leaf-level
  "`sorted-entries`: seq of `[k v]`, already sorted by k. Returns a seq of
  `[max-key cid]` summaries after put!-ing each leaf node."
  [put! sorted-entries]
  (mapv (fn [chunk]
          (let [node {"kind" "leaf" "entries" (mapv vec chunk)}
                cid (put-node! put! node)
                max-key (first (last chunk))]
            [max-key cid]))
        (chunk-by (fn [[k _]] (boundary? k)) sorted-entries)))

(defn- build-internal-level
  "`child-summaries`: seq of `[max-key cid]`, already sorted by max-key.
  Boundary is determined by the CHILD CID (see namespace docstring), not by
  max-key."
  [put! child-summaries]
  (mapv (fn [chunk]
          (let [node {"kind" "internal" "children" (mapv vec chunk)}
                cid (put-node! put! node)
                max-key (first (last chunk))]
            [max-key cid]))
        (chunk-by (fn [[_ cid]] (boundary? cid)) child-summaries)))

(defn build-tree
  "Build a Prolly Tree from `sorted-entries` (seq of `[k v]`, sorted by `k`
  ascending, `k` comparable via `compare`), calling `(put! cid bytes)` for
  every node created. Returns the root CID string, or nil for empty input."
  [put! sorted-entries]
  (when (seq sorted-entries)
    (loop [level (build-leaf-level put! sorted-entries)]
      (if (= 1 (count level))
        (second (first level))
        (recur (build-internal-level put! level))))))

(defn- get-node [get-fn cid]
  (cbor/decode (get-fn cid)))

(defn lookup
  "Point lookup of `k` under `root-cid`, fetching nodes via `(get-fn cid) ->
  bytes`. Returns the value, or nil if `k` is absent or `root-cid` is nil
  (empty tree)."
  [get-fn root-cid k]
  (when root-cid
    (loop [cid root-cid]
      (let [node (get-node get-fn cid)]
        (case (get node "kind")
          "leaf" (some (fn [[ek ev]] (when (= ek k) ev)) (get node "entries"))
          "internal"
          (let [children (get node "children")
                target (or (some (fn [[max-key child-cid]]
                                    (when (<= (compare k max-key) 0) child-cid))
                                  children)
                           (second (last children)))]
            (recur target)))))))

(defn scan-prefix
  "All `[k v]` pairs whose string key starts with `prefix`, fetching nodes
  via `(get-fn cid) -> bytes`. Walks every internal child (no key-range
  pruning) -- fine for the tree sizes this landing targets; range-pruned
  scan is a follow-up."
  [get-fn root-cid prefix]
  (when root-cid
    (letfn [(walk [cid]
              (let [node (get-node get-fn cid)]
                (case (get node "kind")
                  "leaf" (filter (fn [[k _]] (str/starts-with? k prefix))
                                 (get node "entries"))
                  "internal" (mapcat (fn [[_ child-cid]] (walk child-cid))
                                     (get node "children")))))]
      (vec (walk root-cid)))))
