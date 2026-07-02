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
```

## Scope

Portability is real now, not aspirational: the whole dependency chain
(`multiformats` → `dag-cbor` → `ipld` → this repo) runs on both the JVM and
real ClojureScript (shadow-cljs node-test in CI), producing byte-identical
CIDs on both platforms.

Not in scope for this landing: key-range-pruned scan (current `scan-prefix`
walks every internal child), tree diff/merge, garbage collection of
unreferenced nodes.

## Test

```bash
clojure -M:test                     # JVM
npm install && npm run test:cljs    # real ClojureScript (shadow-cljs node-test)
```

## License

MIT
