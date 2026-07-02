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

## Honesty note on portability

`multiformats.core/sha256` and `cbor.core/encode` (this repo's two deps) are
today JVM-only despite living in `.cljc`/portable-named repos. This
namespace is itself written portably (`.cljc`, no JVM-only syntax outside
what it calls), but transitively only runs on the JVM until those two
upstream repos grow `:cljs` branches. That gap is **not** hidden or worked
around here — it is a tracked follow-up for the eventual browser/wasm
target (`kotoba-lang/kotoba-client`).

Not in scope for this landing: key-range-pruned scan (current `scan-prefix`
walks every internal child), tree diff/merge, garbage collection of
unreferenced nodes.

## Test

```bash
clojure -M:test
```

## License

MIT
