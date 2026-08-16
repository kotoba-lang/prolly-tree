# prolly-tree

`kotoba-lang/prolly-tree` is the shared CLJC home for a content-addressed
probabilistic B-tree (Prolly Tree) over CID-addressed dag-cbor blocks — the
IPLD index structure that backed `kotoba`'s 4-index Datalog Arrangement
(EAVT/AEVT/AVET/VAET) before `kotoba-lang/kotoba` deleted its Rust workspace
(`604896171b`, 2026-07-01) with no CLJC replacement. See
`90-docs/adr/2607010930-clj-wgsl-migration.md` Phase 6.

Chunk boundaries are probabilistic (~1/256 average chunk size, matching the
deleted Rust `BOUNDARY_MASK=0xFF`). **Internal-level boundaries are keyed on
the child's CID, never on the child's max-key** — keying on max-key
re-triggers the same boundary decision at every level for the same key,
which is an infinite-recursion bug the original Rust implementation hit and
fixed.

Storage is injected, not owned: `put!`/`get-fn` ports are passed in by the
caller (in-memory atom, IndexedDB, HTTP `block.get`, whatever). This
namespace does no I/O itself.

Child references in internal nodes are **real IPLD links** — CBOR tag 42
over the binary CID, via [`kotoba-lang/ipld`](https://github.com/kotoba-lang/ipld)
— so a generic DAG-CBOR/IPFS tool can walk the tree with no prolly-specific
schema knowledge (`ipld.core/links` suffices). This replaced the first
landing's plain-CID-string encoding (its documented honesty note): every
node's bytes, and therefore every CID, changed — a clean break; nothing in
production consumed the old format. Boundary math is unchanged (still keyed
on the child's CID string), so tree shape is identical.

## Use

```clojure
(require '[prolly-tree.core :as pt])

(def store (atom {}))
(def put!   (fn [cid bytes] (swap! store assoc cid bytes)))
(def get-fn (fn [cid] (get @store cid)))

(def root (pt/build-tree put! (sort-by first [["a" 1] ["b" 2] ["c" 3]])))
(pt/lookup get-fn root "b")                  ;=> 2
(pt/scan-prefix get-fn root "a")              ;=> [["a" 1]]
(pt/scan-range get-fn root "a" "c")           ;=> [["a" 1] ["b" 2]]  ; [lo, hi)
```

Structural replication can transfer only target blocks that are not proven
shared with a base root:

```clojure
(require '[prolly-tree.diff :as diff])

(def blocks
  (diff/sync-blocks get-fn old-root new-root
                    {:max-blocks 1024 :max-bytes 16777216 :max-reads 2048}))
;; Root-first, CID-verified blocks. Apply them to a store that already holds
;; old-root and new-root becomes completely readable.
```

Equal-CID subtrees cost no reads and no transfer. All comparison reads and
outbound block/byte counts are independently bounded and fail closed.

## IPLD ADL view

The physical leaf/internal block graph is now exposed as an IPLD Advanced Data
Layout rather than only through Prolly-specific functions:

```clojure
(require '[prolly-tree.adl :as adl]
         '[ipld.data-model :as dm]
         '[ipld.selector :as selector])

(def m (adl/view get-fn root))
(dm/kind m)                         ;=> :map
(dm/lookup m "b")                   ;=> 2
(selector/select m {:selector :explore-fields
                    :fields {"b" {:selector :matcher}}})
;;=> [{:path ["b"], :value 2}]
```

Consumers see one ordinary Data Model Map while `adl/substrate` preserves the
physical root CID. `prolly-tree.schema/node-schema` validates every decoded
substrate block as the expected leaf/internal discriminated union before tree
logic interprets it. This is a runtime schema algebra, not a full IPLD Schema
DSL parser.

## Proving one key to someone who has only the root

```clojure
(def proof (pt/inclusion-proof get-fn root "b"))  ; the blocks along root->leaf
(pt/verify root "b" proof)                        ;=> {:value 2}
(pt/verify root "b" (rest proof))                 ;=> nil
```

`verify` does no I/O — the caller supplies the blocks. That is the point: a
party holding only `root` can check the claim without the tree.

**A proof is the path, not a sibling list.** A node's CID is the CID of the
whole node's DAG-CBOR, and an internal node carries every child's link, so
recomputing a parent's CID needs the parent's entire child list — not one
sibling hash. Ethereum's MPT proofs have the same shape for the same reason.
The cost is worth knowing before you budget for it: a proof carries O(height)
blocks and a block holds a whole chunk (~256 entries), so it is kilobytes
where a binary sibling path would be hundreds of bytes. If proof size is the
binding constraint, commit to a structure built *for* proving; this one is the
database's own index.

`root-cid` and `k` are arguments to `verify` and are never read out of the
proof — a verifier that takes the root from the thing it is checking verifies
nothing.

**Absence is not provable here.** Descending with an absent key does land in
the leaf that would hold it, but that argument needs the tree to be
well-formed (keys sorted, every max-key maximal), and one root→leaf path
cannot establish that. Inclusion needs no such assumption: the verifier sees
the pair inside a block it hashed itself. `inclusion-proof` returning nil
means "no proof to hand out", not "absent".

## Scope

Portability is real now, not aspirational: the whole dependency chain
(`multiformats` → `dag-cbor` → `ipld` → this repo) runs on both the JVM and
real ClojureScript (shadow-cljs node-test in CI), producing byte-identical
CIDs on both platforms.

Key-range-pruned scans and structural diff/sync are implemented. Merge policy
and garbage collection of unreferenced nodes remain host/database concerns.

## Test

```bash
clojure -M:test                     # JVM
npm install && npm run test:cljs    # real ClojureScript (shadow-cljs node-test)
```

## License

MIT
