# Performance & tuning

hako's defaults are tuned for typical Clojure workloads. The knobs
below let you trade off correctness / semantics for speed or size.

- [Options](#options)
- [Reusable Writer / Reader](#reusable-writer--reader)
- [Ident cache](#ident-cache)
- [Homogeneous vector packing](#homogeneous-vector-packing)
- [Zero-copy bytes](#zero-copy-bytes)
- [When to use which encode fn](#when-to-use-which-encode-fn)
- [When defaults hurt](#when-defaults-hurt)

## Options

### Encode

| Option                        | Default | When to enable                                              |
|-------------------------------|---------|-------------------------------------------------------------|
| `:initial-size`               | 256     | Payload is known to be > a few KB — pre-size to avoid growth. |
| `:preserve-meta`                      | false   | You need metadata roundtrip. Costs 1 tag byte + meta-map per `IObj`. |
| `:pack-homogeneous`          | false   | Vectors of Long / Double you also want as `long[]` / `double[]` on decode. |
| `:coerce-custom-comparator`  | false   | You use `sorted-*-by` and can tolerate silent comparator loss. |

### Decode

| Option            | Default | When to enable                                              |
|-------------------|---------|-------------------------------------------------------------|
| `:zero-copy`     | false   | Bytes payload only; source segment outlives the decoded value. |
| `:tolerate-unknown-tags`      | false   | You may receive user-tags your JVM doesn't know about.       |
| `:cache-idents`  | false   | Unique keyword-heavy workload where dedup across messages helps. |

## Reusable Writer / Reader

`(hako/encode value)` pays two costs per call: opening + closing
an `Arena.ofConfined()`, and copying the encoded segment into a
fresh `byte[]`. For high-throughput encode loops that can consume
a `MemorySegment` directly, `hako/writer` + `encode-into!` avoid
both:

```clj
(with-open [wr (hako/writer 4096)]
  (dotimes [_ N]
    (let [seg (hako/encode-into! wr value)]
      (consume! seg))))
```

Rough numbers per encode (see full bench in the README):

| payload         | `hako/encode` → byte[] | `encode-into!` → segment | speedup |
|-----------------|-----------------------:|-------------------------:|--------:|
| small-map       |                 216 ns |                   209 ns |   ~1×   |
| string-100      |                  97 ns |                    34 ns |   ~3×   |
| long-array-1k   |                 912 ns |                   192 ns |  ~5×    |
| string-10k      |                1.63 µs |                   745 ns |   ~2×   |

The segment-out path wins big on prim arrays (no byte[] copy of
the packed payload) and long strings (no growth-chain garbage).
For tiny map payloads the arena setup cost is already dominated
by per-value dispatch, so the win is negligible.

Reader has an analogous `hako/reader` + `decode-into!` (feed it a
`MemorySegment` source to skip the `MemorySegment/ofArray` wrap).
See [Streaming](streaming.md).

## Ident cache

`:cache-idents true` consults a JVM-global
`ConcurrentHashMap<String, Keyword>` (and one for Symbols) when
decoding first-occurrence identifiers. Every keyword in a payload
still goes through the per-message symbol table for symref dedup;
the global cache accelerates the underlying `Keyword.intern` call
by avoiding the intern-table lookup on repeat first-occurrences
across messages.

- **Enable when:** decoding many messages that share the same
  identifier vocabulary (log-file replay, RPC servers with a fixed
  wire schema).
- **Skip when:** every message has largely unique keywords — the
  cache miss + `putIfAbsent` overhead outweighs the win. Confirmed
  by the bundled benchmark: on the `nested-map` payload the cache
  gives no measurable improvement because the payload already has
  strong per-message dedup.

The cache is unbounded. In practice, applications interact with
tens of thousands of distinct keywords at most and this stays a
few KB of memory. If you need bounded, wrap with your own cache
strategy in a user-tag handler.

## Homogeneous vector packing

`:pack-homogeneous true` scans each vector on encode; if every
element is `Long` (or every element is `Double`), the vector is
emitted as a packed prim array (extension `prim-longs` /
`prim-doubles`). Decodes as `long[]` / `double[]`, not
`PersistentVector`.

- **Wire size:** 8 bytes per element vs varint (~1-9 bytes). For
  small ints, this is a size loss. For random or large ints, it's
  neutral or a win.
- **Encode speed:** faster than emitting boxed elements —
  `MemorySegment.copy` with `LE_LONG` layout is intrinsic.
- **Decode speed:** returns a typed array; consumers who need a
  Clojure vector must `vec` it (which allocates).

Use only when you know the payload shape and can consume typed
arrays directly.

## Zero-copy bytes

`:zero-copy true` returns `MemorySegment` slices for `bytes`
payloads instead of copying into a `byte[]`. Skips the memcpy plus
the byte-array allocation.

For a 1 KB blob, this saves ~50-100 ns per decode. For 1 MB, savings
are meaningful (JVM avoids a large native → heap copy).

Constraints — see [Arenas](arenas.md):

- Slice lifetime = source segment lifetime. Retaining past the
  arena close is unsafe.
- Strings still decode eagerly (UTF-8 conversion required).
- Prim arrays still decode as typed Java arrays.

## When to use which encode fn

Rough decision tree — best-performing to most-convenient:

- **Consuming a `MemorySegment` in a hot loop?** `hako/writer` +
  `hako/encode-into!` — no per-message allocation, no
  `MemorySegment → byte[]` copy. **The fastest path.**
- **Native-side consumer (FFM callee) with your own arena?**
  `hako/encode-to-segment arena value` — output lives in your
  arena.
- **Hot loop that still needs `byte[]`?** `hako/writer` +
  `hako/encode-into!` + one `MemorySegment.copy` per message — you
  amortize arena setup but pay the final copy per call.
- **Batch of related messages, all consumed together?**
  `hako/encode-many values` — one envelope, shared sym-table
  across the batch (aggressive keyword dedup).
- **Just want a `byte[]` and don't care about the last microsecond?**
  `hako/encode value` — simplest, no ownership issues.

Rough decision tree for decode:

- **Just want a value?** `hako/decode bs`.
- **Hot loop?** `hako/reader` + `decode-into!`.
- **Batch?** `hako/decode-many bs`.
- **Bytes payloads that survive as segments?** Add `{:zero-copy
  true}`.

## When defaults hurt

Fast-path defaults are tuned for medium-sized Clojure data. Two
edge cases where you should reach for a knob:

### Tiny payload, high throughput

If you're encoding many tiny values per second (< 100 bytes each),
use the reusable Writer. If your consumer accepts a
`MemorySegment` (e.g. writing directly to a socket / mmap / native
callee), keep the whole loop off-heap:

```clj
(with-open [wr (hako/writer 128)]
  (loop []
    (when-let [message (poll-inbox!)]
      (send! (hako/encode-into! wr message))
      (recur))))
```

If the consumer insists on `byte[]`, pay the final copy per call:

```clj
(with-open [wr (hako/writer 128)]
  (loop []
    (when-let [message (poll-inbox!)]
      (let [seg (hako/encode-into! wr message)
            n (.byteSize seg)
            arr (byte-array n)]
        (java.lang.foreign.MemorySegment/copy
         seg java.lang.foreign.ValueLayout/JAVA_BYTE 0 arr 0 n)
        (send! arr))
      (recur))))
```

The arena setup cost is what's amortized either way — the copy
is a bounded ~50 ns per message that scales linearly with payload
size.

### Predictable schema

If you know every message uses the same 5-10 keywords, use
`encode-many` on the largest batch you can afford. The sym-table
sharing pays for itself within ~3 messages.

If you can't batch (each message is independent) but you still want
dedup, use `:cache-idents true` on decode — the ident cache
amortizes across messages.
