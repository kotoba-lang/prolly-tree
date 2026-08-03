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

  Update: `multiformats.core/sha256` and `cbor.core/encode` have grown real
  `:cljs` branches (SHA-256 via @noble/hashes, portable CBOR byte buffers).
  `utf8-bytes` below used `.getBytes` unconditionally despite the `.cljc`
  extension -- a genuine gap in this namespace's own portability, not just
  its dependencies' -- now split per-platform. Verified end to end under
  ClojureScript (nbb), not just reasoned about: `build-tree`/`lookup` run
  and byte-identically match the :clj path for the same input.

  Update 2 (tag-42): child references in internal nodes are now REAL IPLD
  links -- CBOR tag 42 over the binary CID, via `kotoba-lang/ipld` -- not
  plain CID strings. This closes the honesty note the first landing
  carried (`cbor.core` had no tag support then; it does now). A generic
  DAG-CBOR/IPFS tool can walk the tree. NOTE: this changes every node's
  bytes and therefore every CID -- a clean break, nothing in production
  consumed the old format (see superproject ADR). Boundary math is
  unchanged (still keyed on the child's CID string), so tree SHAPE is
  identical; only the on-block encoding moved."
  (:require [clojure.string :as str]
            [multiformats.core :as mf]
            [ipld.core :as ipld]))

(def boundary-bits
  "Chunk boundary fires when the low `boundary-bits` bits of the determinant
  hash are all zero. 8 bits == ~1/256 average chunk size (BOUNDARY_MASK=0xFF
  in the deleted Rust implementation)."
  8)

(defn- utf8-bytes [s]
  #?(:clj (.getBytes ^String s "UTF-8")
     :cljs (.encode (js/TextEncoder.) s)))

(defn- boundary?
  "True ~1/(2^boundary-bits) of the time: the last byte of
  sha256(str(x)-as-utf8) is all-zero under `boundary-bits`."
  [x]
  (let [h (mf/sha256 (utf8-bytes (str x)))
        b (bit-and 0xff (aget h (dec (alength h))))]
    (zero? (bit-and b (dec (bit-shift-left 1 boundary-bits))))))

(defn- put-node!
  "DAG-CBOR-encode `node` (links become tag 42), CID it, call
  `(put! cid bytes)`, return the cid string."
  [put! node]
  (ipld/put-node! put! node))

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
  max-key. On-block, each child reference is a real IPLD link (tag 42);
  in-memory summaries stay plain CID strings so the boundary math is
  encoding-independent."
  [put! child-summaries]
  (mapv (fn [chunk]
          (let [node {"kind" "internal"
                      "children" (mapv (fn [[max-key cid]]
                                         [max-key (ipld/link cid)])
                                       chunk)}
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

(defn- verified-node [expected-cid bytes]
  (let [actual (ipld/cid bytes)]
    (when-not (= expected-cid actual)
      (throw (ex-info "prolly-tree: block CID mismatch"
                      {:type :ipld/cid-mismatch :expected-cid expected-cid
                       :actual-cid actual})))
    (ipld/decode bytes)))

(defn- get-node [get-fn cid]
  (verified-node cid (get-fn cid)))

(defn- child-cid
  "A decoded internal-node child entry is `[max-key <tag-42 link>]`; return
  the link's CID string."
  [[_ link]]
  (ipld/link-cid link))

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
                target (or (some (fn [entry]
                                   (when (<= (compare k (first entry)) 0)
                                     (child-cid entry)))
                                 children)
                           (child-cid (last children)))]
            (recur target)))))))

(defn scan-prefix
  "All `[k v]` pairs whose string key starts with `prefix`, fetching nodes via
  `(get-fn cid) -> bytes`. Key-range pruned: since keys are sorted and an
  internal node's children are `[max-key cid]` in order, a prefix-matching key
  can only live in a child whose `max-key >= prefix` AND whose predecessor's
  `max-key` has not already passed the prefix range. So we skip children entirely
  below the prefix and stop once past it — descending into O(path) blocks for a
  keyed read instead of the whole tree (ADR-2607022330 addendum 3 / #16). With an
  empty `prefix` this is a full ordered scan, unchanged."
  [get-fn root-cid prefix]
  (when root-cid
    (letfn [(past-prefix? [k]
              ;; k sorts after the prefix AND is not itself within it → the whole
              ;; remaining (sorted) range is beyond the prefix; stop.
              (and (pos? (compare k prefix)) (not (str/starts-with? k prefix))))
            (walk [cid]
              (let [node (get-node get-fn cid)]
                (case (get node "kind")
                  "leaf" (filter (fn [[k _]] (str/starts-with? k prefix))
                                 (get node "entries"))
                  "internal"
                  (loop [children (get node "children"), prev-max nil, out (transient [])]
                    (if (empty? children)
                      (persistent! out)
                      (let [entry (first children)
                            mk (first entry)]
                        (cond
                          ;; predecessor already past the prefix range → done
                          (and (some? prev-max) (past-prefix? prev-max)) (persistent! out)
                          ;; this child's whole range is below the prefix → skip
                          (neg? (compare mk prefix)) (recur (rest children) mk out)
                          ;; may contain matches → descend
                          :else (recur (rest children) mk
                                       (reduce conj! out (walk (child-cid entry))))))))))
              )]
      (vec (walk root-cid)))))

#?(:cljs
   (def ^:private scan-prefix-async-batch-size
     "Max in-flight child fetches at once for `scan-prefix-async`, same
     rationale/value as `kotobase-peer.core`'s `pmap-async-batch-size`
     (duplicated here rather than shared -- prolly-tree has no dependency on
     kotobase-peer, and this codebase's own precedent is per-repo test/
     concurrency helper duplication over a forced shared dependency in the
     wrong direction). 24 is a starting point, not load-tested against
     Workers' exact per-plan subrequest ceiling."
     24))

#?(:cljs
   (defn- pmap-async
     "cljs only: map `f` (returns a `js/Promise`) over `coll` in bounded
     batches of `scan-prefix-async-batch-size` concurrent calls, return one
     `js/Promise` of the resolved results, order-preserved. See that var's
     docstring for why unbounded concurrency is unsafe."
     [f coll]
     (let [batches (partition-all scan-prefix-async-batch-size coll)]
       (reduce (fn [acc-promise batch]
                 (.then acc-promise
                        (fn [acc]
                          (-> (js/Promise.all (into-array (map f batch)))
                              (.then (fn [batch-results] (into acc batch-results)))))))
               (js/Promise.resolve [])
               batches))))

#?(:cljs
   (defn scan-prefix-async
     "Async, concurrency-batched counterpart to `scan-prefix`, for a `get-fn`
     that returns a `js/Promise<bytes>` (fetches over the network, e.g. R2)
     rather than the synchronous get-fn/block-miss-trampoline contract
     `scan-prefix` assumes.

     `scan-prefix` over a trampoline like `kotobase.cljc-worker.r2/with-
     blocks` does exactly ONE new block discovery per call: `get-fn` throws
     synchronously on the first not-yet-cached CID, the trampoline catches
     it, fetches JUST THAT block, and re-runs `scan-prefix`'s ENTIRE
     recursive walk from `root-cid`. Only raw bytes are cached by the
     trampoline -- `get-node`'s `ipld/decode` is NOT memoized -- so a walk
     touching N distinct blocks costs O(N) SEQUENTIAL round trips (one
     discovered per retry, never batched) and, because every retry re-walks
     and re-decodes every already-fetched node from the root, O(N^2) total
     node decodes. For a mature tree (thousands of leaf blocks) this
     quadratic redundant-decode cost, not raw I/O latency, is a plausible
     dominant contributor to a cold hydrate exceeding a Worker's CPU budget
     (kotoba-lang/kotobase-peer's `hydrate-db`, gftdcojp/app-aozora#78 /
     ADR-2607120730).

     `scan-prefix-async` fetches each internal node's children CONCURRENTLY
     (bounded batch, `pmap-async`) instead of one miss at a time, and decodes
     each node exactly once (no retry-from-root) -- O(N) round trips
     (batched, not sequential) and O(N) decodes, not O(N^2). Same key-range
     pruning as `scan-prefix`; same `[k v]` pair shape.

     get-fn: `(get-fn cid) -> js/Promise<bytes>`. → `js/Promise<[[k v] ...]>`."
     [get-fn root-cid prefix]
     (if (nil? root-cid)
       (js/Promise.resolve [])
       (letfn [(past-prefix? [k]
                 (and (pos? (compare k prefix)) (not (str/starts-with? k prefix))))
               (children-to-walk [node]
                 (loop [children (get node "children"), prev-max nil, acc []]
                   (if (empty? children)
                     acc
                     (let [entry (first children)
                           mk (first entry)]
                       (cond
                         (and (some? prev-max) (past-prefix? prev-max)) acc
                         (neg? (compare mk prefix)) (recur (rest children) mk acc)
                         :else (recur (rest children) mk (conj acc entry)))))))
               (walk [cid]
                 (-> (get-fn cid)
                     (.then #(verified-node cid %))
                     (.then (fn [node]
                              (case (get node "kind")
                                "leaf" (js/Promise.resolve
                                        (filterv (fn [[k _]] (str/starts-with? k prefix))
                                                 (get node "entries")))
                                "internal"
                                (-> (pmap-async (fn [entry] (walk (child-cid entry)))
                                                (children-to-walk node))
                                    (.then (fn [results] (vec (apply concat results))))))))))]
         (walk root-cid)))))

;; ── Incremental insert ──────────────────────────────────────────────────────
;;
;; Why this can exist at all, and why it must be byte-identical to a rebuild.
;;
;; Chunk boundaries here are CONTENT-DEFINED and purely local: a leaf chunk ends
;; after an entry whose KEY hashes to a boundary, an internal chunk ends after a
;; child whose CID hashes to a boundary. Nothing about a chunk depends on how
;; many entries precede it or on how the tree was assembled. That is the
;; property (history independence) the whole content-addressed design rests on:
;; the same logical content must produce the same CIDs regardless of insertion
;; order, or two replicas holding identical data disagree about their hashes.
;;
;; It also means an insert cannot ripple: adding key K changes exactly the run
;; of entries between the boundary before K and the boundary at or after it.
;; Every other chunk's bytes are untouched and are structurally shared.
;;
;; So `insert` rebuilds a bounded WINDOW at each level rather than the level:
;; the affected child plus its immediate successor. The successor is needed
;; because a replacement changes a child's CID, and a child that WAS a boundary
;; may stop being one -- in which case its chunk must absorb the next one. That
;; merge is the case a naive "re-chunk this node's own children" misses, and it
;; is exactly the case that would silently produce a tree no rebuild could
;; reproduce.
;;
;; `insert-tested-equivalent?` in the tests is the gate: for randomized key
;; sets and insertion orders, inserting into a tree must yield the SAME root CID
;; as building the whole sorted set from scratch. An implementation that is
;; merely "close" is worse than none, because the divergence is invisible until
;; two nodes compare roots.

(defn- leaf-node? [node] (= "leaf" (get node "kind")))

(defn- node-entries
  "Leaf entries as a vector of [k v], with dag-cbor's vectors normalized."
  [node]
  (mapv vec (get node "entries")))

(defn- node-children
  "Internal children as a vector of [max-key cid-string]. In-memory summaries
  stay plain CID strings so boundary math is encoding-independent, matching
  `build-internal-level`."
  [node]
  (mapv (fn [entry] [(first entry) (child-cid entry)]) (get node "children")))

(defn- upsert-sorted
  "Insert or replace [k v] in a sorted vector of entries, keeping key order."
  [entries k v]
  (let [i (count (take-while #(neg? (compare (first %) k)) entries))
        hit? (and (< i (count entries)) (= k (first (nth entries i))))]
    (if hit?
      (assoc entries i [k v])
      (vec (concat (subvec entries 0 i) [[k v]] (subvec entries i))))))

(defn- child-index
  "Index of the child that owns `k`: the first whose max-key >= k, else the
  last. Same rule `lookup` descends by, kept in one place."
  [children k]
  (or (first (keep-indexed (fn [i [max-key _]]
                             (when (<= (compare k max-key) 0) i))
                           children))
      (dec (count children))))

(defn- rechunk-leaves
  "Re-chunk `entries` into leaf nodes and return their [max-key cid] summaries."
  [put! entries]
  (build-leaf-level put! entries))

(defn- rechunk-internal
  "Re-chunk `children` into internal nodes and return their summaries."
  [put! children]
  (build-internal-level put! children))

(defn- splice
  "Replace `n` items of `v` starting at `i` with `replacement`."
  [v i n replacement]
  (vec (concat (subvec v 0 i) replacement (subvec v (min (count v) (+ i n))))))

(defn- tree-height
  "Number of internal levels below `root-cid`. A leaf root has height zero.
  Prolly trees are level-balanced, so following the first child is sufficient."
  [get-fn root-cid]
  (loop [cid root-cid, height 0]
    (let [node (get-node get-fn cid)]
      (if (leaf-node? node)
        height
        (recur (second (first (node-children node))) (inc height))))))

(defn- leaf-summaries-at-height
  [get-fn cid height]
  (let [node (get-node get-fn cid)]
    (cond
      (zero? height)
      [[(first (last (node-entries node))) cid]]

      (= 1 height)
      (node-children node)

      :else
      (into [] (mapcat (fn [[_ child]]
                         (leaf-summaries-at-height get-fn child (dec height))))
            (node-children node)))))

(defn- leaf-summaries
  "Every leaf's `[max-key cid]` under `root-cid`, in key order.

  Walks internal nodes only. There are ~1/256 as many of those as there are
  leaves (and ~1/65536 as many again above them), so collecting the whole leaf
  index costs O(n/256) reads -- for a five-million-entry tree, on the order of
  eighty blocks rather than twenty thousand."
  [get-fn root-cid]
  (leaf-summaries-at-height get-fn root-cid
                            (tree-height get-fn root-cid)))

(defn- mutation-plan
  [leaves additions removals]
  (let [last-index (dec (count leaves))
        addition-indexed (mapv (fn [[k _ :as pair]]
                                 [(child-index leaves k) pair])
                               additions)
        removal-indices (map #(child-index leaves %) removals)
        affected (-> (set (map first addition-indexed))
                     (into (mapcat #(if (< % last-index) [% (inc %)] [%])
                                   removal-indices))
                     sort)
        windows (reduce (fn [ranges i]
                          (if (and (seq ranges)
                                   (= i (inc (second (peek ranges)))))
                            (conj (pop ranges) [(first (peek ranges)) i])
                            (conj ranges [i i])))
                        []
                        affected)]
    {:addition-indexed addition-indexed
     :windows windows
     :removal-set (set removals)}))

(defn insert
  "Insert or replace `[k v]` under `root-cid`, writing only the affected leaf
  and the internal nodes above it, and return the new root CID.

  A nil `root-cid` builds a single-entry tree. The result is byte-identical to
  `(build-tree put! sorted-entries-with-k)` -- the tests treat any divergence
  as a failure rather than a variant, because a content-addressed store whose
  insert can produce a tree a rebuild would not is one where two replicas
  holding identical data disagree about their root hash.

  Only ONE leaf is read and rewritten; every other leaf is shared with the old
  tree. The internal levels are re-chunked in full rather than patched through
  a window. That is deliberate: boundaries are content-defined, so a
  replacement whose new CID stops being a boundary must merge its chunk with
  the following one, and when the affected node is the last child of its
  parent that sibling lives under a different parent. A window that cannot see
  past its parent cannot express that merge, and an earlier draft of this
  function got it wrong in exactly that case. Re-chunking each internal level
  from the whole ordered summary list is equivalent to what `build-tree` does
  by construction, and internal nodes are ~1/256 of the tree -- so the cost is
  a few dozen blocks where a full rebuild pays tens of thousands, and the
  correctness argument is one sentence instead of a case analysis."
  [put! get-fn root-cid k v]
  (if (nil? root-cid)
    (build-tree put! [[k v]])
    (let [leaves (leaf-summaries get-fn root-cid)
          i (child-index leaves k)
          leaf (get-node get-fn (second (nth leaves i)))
          rebuilt (rechunk-leaves put! (upsert-sorted (node-entries leaf) k v))
          level (splice leaves i 1 rebuilt)]
      (loop [level level]
        (if (= 1 (count level))
          (second (first level))
          (recur (rechunk-internal put! level)))))))

(defn insert-many
  "Insert or replace many `[k v]` pairs under `root-cid` in one pass, and
  return the new root CID.

  This is not a convenience wrapper. Calling `insert` in a loop re-chunks the
  internal levels once PER ENTRY, and the internal levels are the part this
  design rebuilds in full — so a thousand entries would rewrite them a
  thousand times and cost more than the rebuild the whole exercise exists to
  avoid. Here every affected leaf is rebuilt, then the internal levels are
  re-chunked exactly once.

  A fold's novelty is precisely this shape: many entries at once, spread over
  a few leaves. `pairs` need not be sorted or distinct; later pairs win for a
  repeated key, matching `insert`'s replace semantics.

  Byte-identical to `(build-tree put! all-sorted-entries)`, same as `insert`,
  and the tests assert it against both a rebuild and a loop of single inserts."
  [put! get-fn root-cid pairs]
  (let [pairs (vec pairs)]
    (cond
      (empty? pairs) root-cid
      (nil? root-cid) (build-tree put! (->> pairs
                                            (reduce (fn [m [k v]] (assoc m k v)) {})
                                            (sort-by first)
                                            vec))
      :else
      (let [leaves (leaf-summaries get-fn root-cid)
            ;; group by the leaf that owns each key, so every leaf is read and
            ;; rebuilt at most once however many keys land in it
            by-leaf (reduce (fn [acc [k v]]
                              (update acc (child-index leaves k) (fnil conj []) [k v]))
                            {}
                            pairs)
            level (reduce
                   (fn [level [i additions]]
                     ;; `i` indexes the ORIGINAL leaves. Splicing shifts every
                     ;; position after it, so this runs right-to-left: a splice
                     ;; at a higher index cannot move a lower one, and `i`
                     ;; stays valid without tracking an offset.
                     (let [at i
                           leaf (get-node get-fn (second (nth level at)))
                           entries (reduce (fn [es [k v]] (upsert-sorted es k v))
                                           (node-entries leaf)
                                           additions)]
                       (splice level at 1 (rechunk-leaves put! entries))))
                   leaves
                   ;; descending so each splice only affects indices after it
                   (sort-by first > by-leaf))]
        (loop [level level]
          (if (= 1 (count level))
            (second (first level))
            (recur (rechunk-internal put! level))))))))

(defn mutate-many
  "Apply `additions` (`[k v]`) and `removals` (keys) in one leaf pass.

  Removal windows include the successor leaf because deleting a boundary key
  can merge its chunk with the following chunk. Additions and removals sharing
  a window are coalesced, and internal levels are rebuilt exactly once.
  Additions win when the same key occurs in both inputs."
  [put! get-fn root-cid additions removals]
  (let [additions (vec additions)
        removals (vec (distinct removals))]
    (cond
      (nil? root-cid) (insert-many put! get-fn nil additions)
      (and (empty? additions) (empty? removals)) root-cid
      :else
      (let [leaves (leaf-summaries get-fn root-cid)
            {:keys [addition-indexed windows removal-set]}
            (mutation-plan leaves additions removals)
            level (reduce
                   (fn [level [start end]]
                     (let [retained (->> (subvec level start (inc end))
                                         (mapcat (fn [[_ cid]]
                                                   (node-entries
                                                    (get-node get-fn cid))))
                                         (remove (fn [[k _]]
                                                   (contains? removal-set k)))
                                         vec)
                           window-additions (keep (fn [[i pair]]
                                                    (when (<= start i end) pair))
                                                  addition-indexed)
                           entries (reduce (fn [es [k v]]
                                             (upsert-sorted es k v))
                                           retained
                                           window-additions)]
                       (splice level start (inc (- end start))
                               (rechunk-leaves put! entries))))
                   leaves
                   (sort-by first > windows))]
        (loop [level level]
          (cond
            (empty? level) nil
            (= 1 (count level)) (second (first level))
            :else (recur (rechunk-internal put! level))))))))

(defn delete-many
  "Delete `keys` under `root-cid` in one pass and return the new root CID.
  Missing and duplicate keys are no-ops; deleting the last entry returns nil."
  [put! get-fn root-cid keys]
  (mutate-many put! get-fn root-cid [] keys))

(defn delete
  "Delete `k` under `root-cid`; one-key convenience over `delete-many`."
  [put! get-fn root-cid k]
  (delete-many put! get-fn root-cid [k]))

;; ── the write path, for a store that answers with a Promise ─────────────────
;;
;; `scan-prefix` grew an async counterpart and the write path never did, so a
;; caller on a Promise-returning store could read a tree it had no way to
;; update. Two consumers hit that asymmetry from opposite sides this week:
;; `arrangement`'s `commit-delta!` had a `:cljs` branch that `.then`ed the
;; synchronous `insert-many` (so it could not complete at all), and
;; `kotobase-storage`'s signed head needed the same treatment one layer up.
;;
;; This is deliberately NOT an async re-implementation of the algorithm. The
;; insert logic is subtle -- the internal levels are re-chunked in full for a
;; correctness reason `insert`'s docstring spends a paragraph on, and an
;; earlier draft got exactly that case wrong. A parallel async copy would be
;; two places to get it right and one place to forget. Instead the I/O is
;; peeled off both ends: fetch what the traversal will read, run the existing
;; synchronous function against that, then flush the writes it buffered.
;;
;; What this buys, precisely: the writes are AWAITED before the new root CID
;; is handed back. `insert-many` calls `put!` and ignores what it returns, so
;; a Promise-returning `put!` was never waited on -- the caller got a root
;; naming blocks that might not have landed yet, and would publish a head
;; pointing at them.

#?(:cljs
   (defn- fetch-node!
     [get-fn cache cid]
     (if (contains? @cache cid)
       (js/Promise.resolve (verified-node cid (get @cache cid)))
       (-> (js/Promise.resolve (get-fn cid))
           (.then (fn [bytes]
                    (swap! cache assoc cid bytes)
                    (verified-node cid bytes)))))))

#?(:cljs
   (defn- warm-height!
     [get-fn cache root-cid]
     (letfn [(walk [cid height]
               (-> (fetch-node! get-fn cache cid)
                   (.then (fn [node]
                            (if (leaf-node? node)
                              height
                              (walk (second (first (node-children node)))
                                    (inc height)))))))]
       (walk root-cid 0))))

#?(:cljs
   (defn- warm-leaf-summaries!
     [get-fn cache cid height]
     (-> (fetch-node! get-fn cache cid)
         (.then
          (fn [node]
            (cond
              (zero? height)
              [[(first (last (node-entries node))) cid]]

              (= 1 height)
              (node-children node)

              :else
              (-> (pmap-async
                   (fn [[_ child]]
                     (warm-leaf-summaries! get-fn cache child (dec height)))
                   (node-children node))
                  (.then (fn [levels] (vec (apply concat levels)))))))))))

#?(:cljs
   (defn- warm-mutation!
     "Warm internal summary blocks plus only the leaf windows a mutation reads.

     The old writer recursively fetched every leaf merely to discover that it
     was a leaf. Height makes that discovery once; a penultimate internal node
     already contains every child's max-key/CID summary."
     [get-fn cache root-cid additions removals]
     (-> (warm-height! get-fn cache root-cid)
         (.then
          (fn [height]
            (-> (warm-leaf-summaries! get-fn cache root-cid height)
                (.then
                 (fn [leaves]
                   (let [{:keys [windows]}
                         (mutation-plan leaves additions removals)
                         leaf-cids
                         (distinct
                          (mapcat
                           (fn [[start end]]
                             (map second (subvec leaves start (inc end))))
                           windows))]
                     (pmap-async #(fetch-node! get-fn cache %) leaf-cids))))))))))

#?(:cljs
   (defn insert-many-async
     "`insert-many` for a `get-fn`/`put!` pair that returns Promises.

     -> `js/Promise` of the new root CID, resolved only after every block it
     wrote has been acknowledged. Byte-identical to the synchronous result for
     the same input, because it IS the synchronous function -- see the note
     above."
     [put! get-fn root-cid pairs]
     (let [pairs (vec pairs)]
       (if (empty? pairs)
         (js/Promise.resolve root-cid)
         (let [cache (atom {})
               writes (atom [])
               ;; `put-node!` uses the return value, so keep `insert-many`'s
               ;; contract: hand back the cid, buffer the bytes.
               buffered-put! (fn [cid bytes] (swap! writes conj [cid bytes]) cid)]
           (-> (if (nil? root-cid)
                 (js/Promise.resolve nil)
                 (warm-mutation! get-fn cache root-cid pairs []))
               (.then (fn [_]
                        (insert-many buffered-put! #(get @cache %) root-cid pairs)))
               (.then (fn [new-root]
                        (-> (pmap-async (fn [[cid bytes]]
                                          (js/Promise.resolve (put! cid bytes)))
                                        @writes)
                            (.then (fn [_] new-root)))))))))))

#?(:cljs
   (defn mutate-many-async
     "`mutate-many` for a Promise-returning store. Resolves only after every
     newly referenced block has been acknowledged."
     [put! get-fn root-cid additions removals]
     (let [additions (vec additions)
           removals (vec (distinct removals))]
       (if (and (empty? additions) (empty? removals))
         (js/Promise.resolve root-cid)
         (let [cache (atom {})
               writes (atom [])
               buffered-put! (fn [cid bytes] (swap! writes conj [cid bytes]) cid)]
           (-> (if root-cid
                 (warm-mutation! get-fn cache root-cid additions removals)
                 (js/Promise.resolve nil))
               (.then (fn [_]
                        (mutate-many buffered-put! #(get @cache %) root-cid
                                     additions removals)))
               (.then (fn [new-root]
                        (-> (pmap-async (fn [[cid bytes]]
                                          (js/Promise.resolve (put! cid bytes)))
                                        @writes)
                            (.then (fn [_] new-root)))))))))))

#?(:cljs
   (defn delete-many-async
     "`delete-many` for a Promise-returning store."
     [put! get-fn root-cid keys]
     (mutate-many-async put! get-fn root-cid [] keys)))

#?(:cljs
   (defn delete-async
     "`delete` for a Promise-returning store."
     [put! get-fn root-cid k]
     (delete-many-async put! get-fn root-cid [k])))

#?(:cljs
   (defn insert-async
     "`insert` for a Promise-returning store. One pair through
     `insert-many-async`, which has identical replace semantics."
     [put! get-fn root-cid k v]
     (insert-many-async put! get-fn root-cid [[k v]])))
