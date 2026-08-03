# hako
[![Clojars Project](https://img.shields.io/clojars/v/com.s-exp/hako.svg)](https://clojars.org/com.s-exp/hako)


**A Modern, high-performance, Low-alloc Binary Serialization Library for Clojure.**

Built on JDK 25 FFM `MemorySegment`. Quite possibly the fastest —
and lowest-allocation — binary serializer for Clojure on the JVM
today. See [Benchmarks](#benchmarks).

## Highlights

- **Zero runtime dependencies** — only `org.clojure/clojure`.
- **Off-heap by default** — `MemorySegment` via JDK 25 FFM.
- **Low GC pressure** — arena buffer reused across messages,
  segment-out path allocates ~zero per call.
- **Tunable** — reusable Writer/Reader, caller-owned arenas,
  zero-copy byte decode, prim-array packing.
- **Java hot path** — per-value dispatch and primitive writes in
  Java
- **Per-message symbol table** — repeated keywords / symbols
  dedup to a 1-byte symref.
- **Secure by default** — no class loading from wire, no
  `Serializable`, no decompression. See [Security](#security).
- **Extensible** — records (Clojure + Java), user-tag registry
  with length-prefixed frames for forward-compatible reads.

## Status

Pre-release, alpha.

## Requirements

- **JDK 25+** — uses `java.lang.foreign` FFM API. Requires
  `--enable-native-access=ALL-UNNAMED` on the JVM CLI (the packaged
  jar bundles the `Enable-Native-Access` manifest entry so
  application consumers don't see the warning).
- **Clojure 1.12+** — decoder probes `PersistentArrayMap` threshold
  at load, adapts to 1.13's bumped keyword-only limit automatically.

## Install

[![Clojars Project](https://img.shields.io/clojars/v/com.s-exp/hako.svg)](https://clojars.org/com.s-exp/hako)

## Quick start

```clj
(require '[s-exp.hako :as hako])

(def bs (hako/encode {:name "Alice" :tags #{:a :b :c} :score 42}))
(hako/decode bs)
;; => {:name "Alice", :tags #{:a :b :c}, :score 42}
```

> **Note.** `hako/encode` and `hako/decode` are drop-in helpers to
> ease migration from other serializers — each call allocates a
> fresh `Writer` / `Reader` (~500 B / ~250 B) and opens a private
> confined `Arena`. They are **not** the idiomatic way to use hako.
> Reach for `encode-into!` / `decode-into!` (reusable
> `Writer` / `Reader`) or the ThreadLocal-pooled variants
> `encode-pooled` / `decode-pooled` for hot paths. See the
> [API](#api) section below and
> [Performance](docs/performance.md).

## API

### Encoding

```clj
;; Migration-friendly one-shots — convenient, but allocate a fresh
;; Writer (~500 B) and confined Arena per call. Prefer the reusable
;; or pooled forms below on any hot path.
(hako/encode value)                    ; -> byte[]
(hako/encode value opts)               ; -> byte[]

(hako/encode-to-segment arena value)   ; -> MemorySegment (caller owns arena)
(hako/encode-to-segment arena value opts)

;; Idiomatic — reusable writer for high-throughput encode loops:
(with-open [wr (hako/writer 4096)]
  (dotimes [_ 1000]
    (let [seg (hako/encode-into! wr some-value)]
      ;; consume `seg` before the next call — the slice is
      ;; overwritten on the next encode-into!
      ...)))

;; Zero-alloc write straight into a caller-owned buffer (byte[] or
;; ByteBuffer). Skips the MemorySegment.asSlice wrapper — useful for
;; direct-to-LMDB / socket / mmap paths.
(hako/encode-into-buffer! wr dst value)   ; -> byte count written

;; Batch API — multiple values share one symbol table:
(hako/encode-many [{:a 1} {:a 2} {:a 3}])
;; keyword :a is encoded once, symref'd twice.

;; Batch encode into a reusable writer (skips the ~500 B Writer setup
;; that `encode-many` pays per call):
(hako/encode-many-into! wr [{:a 1} {:a 2} {:a 3}])   ; -> MemorySegment

;; Pooled convenience — ThreadLocal-backed writer, opt-in:
(hako/encode-pooled value)                ; -> byte[], zero setup alloc after warmup

;; Transducer-friendly decode over a batch stream:
(into #{} (filter :active) (hako/decoder bs))
(sequence (map :id) (hako/decoder bs))
(reduce + 0 (hako/decoder bs))
```

**Encode options**

| Option                       | Default | Description                                                                             |
|------------------------------|---------|-----------------------------------------------------------------------------------------|
| `:initial-size`              | 256     | Starting buffer size in bytes.                                                          |
| `:preserve-meta`                     | false   | Preserve metadata on `IObj` values via the `with-meta` extension tag.                   |
| `:pack-homogeneous`         | false   | Detect all-Long / all-Double vectors and emit them as packed prim arrays.               |
| `:coerce-custom-comparator` | false   | Allow `sorted-set-by` / `sorted-map-by` — the custom comparator is dropped on decode.   |

### Decoding

```clj
(hako/decode src)                      ; src is byte[] or MemorySegment
(hako/decode src opts)

;; Reusable reader:
(let [rd (hako/reader some-src)]
  (hako/decode-into! rd another-src))

;; Pooled convenience — ThreadLocal-backed reader, opt-in.
;; Skips the ~250 B per-call Reader alloc.
(hako/decode-pooled bs)

;; Cleanup for short-lived / servlet-container threads that use the
;; pooled variants. Closes the pooled Writer's confined Arena and
;; drops scratch buffers. Long-lived pooled threads never need this.
(hako/close-thread-locals!)

;; Batch — inverse of encode-many, returns a vector of values:
(hako/decode-many bs)
```

**Decode options**

| Option            | Default | Description                                                                            |
|-------------------|---------|----------------------------------------------------------------------------------------|
| `:zero-copy`     | false   | Return `MemorySegment` slices for byte payloads instead of copying to `byte[]`.        |
| `:tolerate-unknown-tags`      | false   | Unregistered user-tag ids yield `TaggedValue` instead of throwing.                     |
| `:cache-idents`  | false   | Consult a JVM-global cache when interning decoded keywords / symbols.                  |

## Supported types

Semantic equality (`=`) is preserved for all listed types.

- `nil`, `boolean`, `Character`, `Long`, `Integer`, `Short`, `Byte`,
  `Double`, `Float`, `String`.
- `byte[]`, `long[]`, `double[]`, `int[]`, `float[]`, `short[]`,
  `char[]`, `boolean[]` — packed, component type preserved.
- `Object[]` (and any reference-typed array — component type not
  preserved, decodes as `Object[]`).
- `Keyword`, `Symbol` — with per-message symbol table + symref dedup.
- `UUID`, `java.util.Date`, `java.util.regex.Pattern` (flags
  preserved), `java.net.URI`.
- `java.time`: `Instant`, `Duration`, `Period`, `LocalDate`,
  `LocalTime`, `LocalDateTime`, `ZonedDateTime`, `OffsetDateTime`.
- `BigInteger`, `clojure.lang.BigInt`, `BigDecimal`, `Ratio`.
- `PersistentVector`, `PersistentList`, `PersistentHashSet`,
  `PersistentHashMap`, `PersistentArrayMap`, `ISeq`.
- `PersistentTreeSet`, `PersistentTreeMap` — default comparator
  only; custom comparators cause a write error unless
  `:coerce-custom-comparator true`.
- `PersistentQueue`.
- **Clojure records** via `defrecord` (requires registration).
- **Java records** (JEP 395; requires registration).
- User-defined types via `ext/register-user-tag!`.

Concrete map / set impls may differ across Clojure versions — the
reader picks `PersistentArrayMap` vs `PersistentHashMap` based on
the runtime's threshold. See `SPEC.md` §5 for the roundtrip
contract.

## Extensions

### Records

```clj
(require '[s-exp.hako.ext :as ext])

(defrecord Point [x y])
(ext/register-record! Point)

(hako/decode (hako/encode (->Point 3 4)))
;; => #user.Point{:x 3, :y 4}
```

Registration reflects on the class once and caches a
`MethodHandle` for the canonical positional constructor.

Java records work identically:

```java
public record Point(int x, int y) {}
```

```clj
(ext/register-record! com.example.Point)
```

### User-tagged types

```clj
(import '(java.io File))

(ext/register-user-tag!
 1                                      ; small app-local id (shifted to 0x10000001 on wire)
 File
 (fn write [w f] (.writeString w (.getPath ^File f)))
 (fn read  [r]
   (let [tag (.getByte r)
         low (bit-and tag 0x0F)
         n (.readTierPayload r (int low))]
     (File. (.getString r (int n))))))

(hako/decode (hako/encode (File. "/tmp/data.edn")))
;; => #object[java.io.File ...]
```

Frames are length-prefixed, so an unknown user-tag id can be
skipped by a `:tolerate-unknown-tags` reader without derailing the surrounding
message. See EXTENSIONS.md §E.2.

### Metadata

Opt-in per encode:

```clj
(hako/encode (with-meta [1 2 3] {:tag :vec})
             {:preserve-meta true})
```

## Wire format

Byte-level spec in [SPEC.md](SPEC.md). Extension registry in
[EXTENSIONS.md](EXTENSIONS.md). Byte-by-byte worked examples in
[WIRE_EXAMPLES.md](WIRE_EXAMPLES.md). Highlights:

- 5-byte envelope `<magic 'HAKO'><version 0>`.
- Every value starts with a tag byte: high nibble = major type,
  low nibble = size tier or subtype.
- Fixed-width size tiers (inline `0..11`, u8, u16, u32, u64) — no
  varint on the hot path.
- Little-endian throughout.
- Per-message symbol table for interned keyword / symbol payloads.
- Zero shared state across messages.

## Security

hako is designed for decoding untrusted input safely. Key guarantees:

- **No arbitrary class loading.** Records only instantiate classes
  registered via `ext/register-record!` — the wire carries a
  classname string, but hako looks it up in the registry rather than
  calling `Class.forName`. An attacker cannot force instantiation of
  arbitrary Java classes.
- **No arbitrary code execution via user-tags.** User-tag ids
  dispatch through the `register-user-tag!` registry. Unregistered
  ids throw by default; `:tolerate-unknown-tags true` returns an
  opaque `TaggedValue{:ext id :bytes segment-slice}` — never
  invokes unknown code.
- **No Java `Serializable` fallback.** hako has no path to
  `ObjectInputStream`. Deserialization gadget chains are not applicable.
- **No decompression.** The wire format doesn't ship compressed
  payloads — no zip / gzip / snappy decompression on the read path,
  so no compression-bomb amplification vector.
- **Per-message symbol table.** Interning state is scoped to one
  message. A malicious message can't poison state for future decodes.
- **Bounded reads.** Count and length fields are validated against
  remaining segment bytes before allocation. Silent truncation for
  u64-tier counts that exceed `Integer/MAX_VALUE` is rejected
  cleanly, not truncated.
- **Envelope enforcement.** Magic + version bytes are checked
  before any dispatch.
- **Confined memory.** Encoder writes into `Arena.ofConfined()` —
  cross-thread misuse is blocked by the FFM layer with
  `WrongThreadException`, not a memory corruption.

**Not defended against** (out of scope):
- Malicious user-tag write / read callbacks you register yourself.
  Registered code runs with your JVM's privileges — vet the
  callbacks you install.
- Denial-of-service via extreme payload sizes. hako reads what you
  give it; enforce input size limits at the transport layer.

## Benchmarks

Criterium (quick-bench statistics: 6 samples × 100 ms target, 1.5 s
JIT warmup per call), JDK 25, `-server -Xmx4g`, direct-linking on.
Single machine, AC power — reproduce with `clj -M:bench -m bench`.

hako's core value proposition is **off-heap encoding into a
`MemorySegment`** with minimal Java-heap allocation. Four call
styles are measured; `encode-into!` uses a long-lived reusable
`Writer` (arena buffer amortized across messages), the others open
a fresh arena per call:

- **`encode-into!` → seg** — reusable `Writer`, emits a
  `MemorySegment` slice per message. No `byte[]` allocation per
  call, no arena open/close per call.
  **The differentiator — this is what the design is optimized
  for.**
- **`encode-into!` → byte[]** — same reusable Writer, then a
  trailing `MemorySegment → byte[]` copy for callers stuck on
  `byte[]` APIs.
- **`encode` → byte[]** — one-shot `hako/encode`. Fresh confined
  arena + final off-heap → heap copy per call. Convenient, less
  optimal.
- **`encode-to-segment` → seg** — one-shot with a caller-provided
  `Arena`. Arena setup cost per call, but the result stays
  off-heap in the caller's arena.

Peers (JVM-only, on-heap output): `nippy` (default `freeze`,
compression + checksums), `nippy-fast` (`fast-freeze`, no
compression), `deed` (`com.github.igrishaev/deed-core 0.1.0`), and
`transit` (`com.cognitect/transit-clj 1.0.333`, MsgPack — different
niche, reference only).

### Encode — hako call-style ladder

Absolute times per call. `encode-into!` → seg is the ceiling; other
columns show what each additional convenience costs.

| payload              | `encode-into!` →seg | `encode-into!` →byte[] | `encode` →byte[] | `encode-to-segment` →seg |
|----------------------|--------------------:|-----------------------:|-----------------:|-------------------------:|
| `long-array-1k`      |             0.19 µs |                0.72 µs |          0.86 µs |                  0.64 µs |
| `double-array-1k`    |             0.20 µs |                0.70 µs |          0.85 µs |                  0.66 µs |
| `string-100`         |             0.04 µs |                0.06 µs |          0.10 µs |                  0.14 µs |
| `small-map`          |             0.12 µs |                0.13 µs |          0.20 µs |                  0.28 µs |
| `mixed`              |             0.22 µs |                0.24 µs |          0.30 µs |                  0.39 µs |
| `string-10k`         |             0.74 µs |                1.13 µs |          1.30 µs |                  1.23 µs |
| `vec-of-strings`     |             1.27 µs |                1.29 µs |          1.56 µs |                  1.62 µs |
| `nested-map` (50 kw) |             4.24 µs |                4.30 µs |          5.12 µs |                  5.16 µs |
| `vec-of-longs` (1k)  |             5.67 µs |                5.82 µs |          6.24 µs |                  6.37 µs |

The segment-out path wins by ~4.4× on prim arrays, ~1.8× on
long strings, and matches the byte[] paths on collection payloads
(where per-value dispatch dominates the arena/copy costs).
Numbers are the mean of two full runs; hako cells reproduce within
a few percent, peer-library outliers (e.g. nippy on `string-10k`)
vary more across runs.

### Encode — `encode-into!` →seg vs peers

| payload              | `encode-into!` →seg |   nippy | nippy-fast |    deed | transit | vs nippy-fast |
|----------------------|--------------------:|--------:|-----------:|--------:|--------:|--------------:|
| `long-array-1k`      |             0.19 µs | 19.7 µs |    19.9 µs | 11.0 µs | 21.9 µs |         102× |
| `double-array-1k`    |             0.20 µs | 22.6 µs |    10.9 µs | 10.9 µs | 24.6 µs |          55× |
| `string-100`         |             0.04 µs | 0.12 µs |    0.07 µs | 0.42 µs |  3.0 µs |         1.9× |
| `small-map`          |             0.12 µs | 0.30 µs |    0.25 µs | 0.62 µs |  3.7 µs |         2.1× |
| `mixed`              |             0.22 µs | 0.55 µs |    0.52 µs | 0.83 µs |  4.2 µs |         2.4× |
| `string-10k`         |             0.74 µs | 2.70 µs |    1.11 µs | 2.17 µs |  4.5 µs |         1.5× |
| `vec-of-strings`     |             1.27 µs | 2.33 µs |    2.32 µs | 3.69 µs | 7.08 µs |         1.8× |
| `nested-map` (50 kw) |             4.24 µs | 10.1 µs |    10.4 µs | 16.4 µs | 35.9 µs |         2.4× |
| `vec-of-longs` (1k)  |             5.67 µs | 19.3 µs |    19.1 µs | 21.3 µs | 31.4 µs |         3.4× |

`encode-into!` →seg leads every cell — including `string-10k`
where the byte[]-output paths lose to `nippy-fast`.

### Decode

Decode has two hako variants — one-shot `hako/decode` (byte[]
source, wrapped via `MemorySegment/ofArray` internally) and reused
`hako/decode-into!` (segment source, no wrap per call). Both take
`{:cache-idents true}`.

| payload              | `decode-into!` →seg src | `decode` →byte[] src |   nippy | nippy-fast |    deed | transit | vs nippy-fast |
|----------------------|------------------------:|---------------------:|--------:|-----------:|--------:|--------:|--------------:|
| `long-array-1k`      |                 0.56 µs |              0.58 µs | 13.7 µs |    13.4 µs | 10.4 µs |  196 µs |          24× |
| `double-array-1k`    |                 0.56 µs |              0.58 µs | 12.1 µs |     8.0 µs | 10.7 µs |  176 µs |          14× |
| `string-100`         |                 0.05 µs |              0.06 µs | 0.09 µs |    0.05 µs | 0.57 µs |  2.8 µs |         1.0× |
| `small-map`          |                 0.14 µs |              0.24 µs | 0.24 µs |    0.18 µs | 0.79 µs |  3.2 µs |         1.3× |
| `mixed`              |                 0.32 µs |              0.27 µs | 0.67 µs |    0.64 µs |  1.3 µs |  4.6 µs |         2.0× |
| `string-10k`         |                 0.86 µs |              1.08 µs |  3.6 µs |     1.1 µs |  1.75 µs |  5.73 µs |         1.3× |
| `vec-of-strings`     |                 2.62 µs |              2.67 µs | 3.76 µs |    3.07 µs | 8.15 µs | 16.3 µs |         1.2× |
| `nested-map` (50 kw) |                 5.66 µs |              5.78 µs | 15.0 µs |    15.0 µs | 25.7 µs | 59.8 µs |         2.6× |
| `vec-of-longs` (1k)  |                11.49 µs |             11.31 µs | 13.9 µs |    13.7 µs | 25.7 µs |  192 µs |         1.2× |

`decode-into!` →seg src leads every cell; `string-100` is a dead heat
with `nippy-fast` (~1 ns apart) — its `readUTF` intrinsic is hard to
beat on payloads smaller than a cache line. See
[Performance](docs/performance.md) for the tradeoffs.

### Allocation

Bytes allocated per operation, measured via
`ThreadMXBean.getThreadAllocatedBytes` over 100 000 iterations
post-warmup. Lower = less GC pressure on the consuming system.
Reproduce with `clj -M:bench -m alloc-bench`.

**Encode** — `encode-into!` →seg vs `nippy/fast-freeze`

| payload              | `encode-into!` →seg | nippy-fast | vs nippy-fast |
|----------------------|--------------------:|-----------:|--------------:|
| `long-array-1k`      |              **40** |     37888  | **947× less** |
| `string-10k`         |           **10056** |     20152  | **2.0× less** |
| `ns-map` (50 kw)     |              **40** |     21784  | **544× less** |
| `string-100`         |             **160** |       352  | **2.2× less** |
| `small-map`          |              **88** |       288  | **3.3× less** |
| `mixed`              |              **96** |       504  | **5.3× less** |
| `vec-of-strings`     |            **2440** |      3472  | **1.4× less** |
| `nested-map` (50 kw) |              **40** |     10000  | **250× less** |

**Decode** — `decode-into!` →seg src vs `nippy/fast-thaw`
(measured with `{:cache-idents true}`)

| payload              | `decode-into!` →seg src | nippy-fast | vs nippy-fast |
|----------------------|------------------------:|-----------:|--------------:|
| `long-array-1k`      |                **8088** |     70976  | **8.8× less** |
| `nested-map` (50 kw) |                **7792** |     40176  | **5.2× less** |
| `small-map`          |                 **240** |       848  | **3.5× less** |
| `string-10k`         |               **10112** |     20192  | **2.0× less** |
| `string-100`         |                 **216** |       384  | **1.8× less** |
| `mixed`              |                 **600** |      2344  | **3.9× less** |
| `ns-map` (50 kw)     |                **3664** |     24800  | **6.8× less** |
| `vec-of-strings`     |                **5808** |      8256  | **1.4× less** |

`encode-into!` →seg wins allocation on **every measured cell** —
encode and decode. Keyword-heavy encode paths (`nested-map`,
`ns-map`) sit at the **40 B** MemorySegment-slice baseline once
the Writer is warmed and the global keyword-bytes cache has been
populated. Use `encode-into-buffer!` to hand the encoded bytes
directly into a caller-owned `byte[]` / `ByteBuffer` — skips the
40 B slice wrapper for callers who own their output buffer.

This allocation delta is the tail-latency story — invisible to
mean-of-loop timing benches. Under load (e.g. 100k msg/s), a
10 KB/op reduction per encode is 1 GB/s less young-gen churn on
the consuming JVM. See [Performance](docs/performance.md) for
tuning notes.

### Records — 100 records in a vector

5-field `Event` record (`{:id :ts :user :action :payload}`), 100
instances in a vector. Reproduce with `clj -M:bench -m records-bench`.

| metric | `encode-into!` / `decode-into!` | nippy-fast | multiplier |
|---|---:|---:|---:|
| encode  | **8.7 µs**  | 60 µs    | **6.9×** |
| decode  | **26 µs**   | 72 µs    | **2.7×** |
| size    | **2933 B**  | 10088 B  | **3.4× smaller** |

Records are where the per-message symbol table pays off hardest.
hako emits the record classname + field-key keywords once per
message, then symrefs them (1 byte each) for the remaining 99
records. Nippy re-emits every keyword payload. Result: **~7×
faster encode, ~3× faster decode, ~3.4× smaller wire** vs
`nippy-fast`. The win grows with vector length as the symref-vs-
payload ratio widens.

Nippy's default `freeze` compresses the output with Snappy and
comes in at **2173 B — smaller than hako's uncompressed 2933 B**,
but at the cost of adding Snappy on the decode path (compression
bomb vector). Wrap hako in transport-layer compression if the
tradeoff makes sense for your setup.

Registration required — see
[Extensions §Records](docs/extensions.md#records).

### Encoded size

| payload | hako | nippy | nippy-fast | deed | transit |
|---|---:|---:|---:|---:|---:|
| `nested-map` (50 kw)  |   **732 B** |  1632 B |  1628 B |  3598 B |  1128 B |
| `vec-of-longs` (1k)   |    2740 B  |  2878 B |  2874 B | 10024 B | **2619 B** |
| `long-array-1k`       |    8009 B  | **2880 B** | 2876 B |  8040 B |  2619 B |
| `double-array-1k`     |    8009 B  | **4165 B** | 8997 B |  8040 B |  9003 B |
| `vec-of-strings`      |     797 B  |   896 B |   892 B |  1330 B |  **793 B** |
| `mixed`               |    **46 B**|    55 B |    51 B |   154 B |    54 B  |
| `small-map`           |     37 B   |    39 B |    35 B |   101 B | **34 B** |
| `string-100`          |    107 B   |   106 B | **102 B**|  140 B |   108 B  |
| `string-10k`          |   10008 B  |**61 B** | 10003 B | 10040 B | 10008 B  |

Two patterns dominate:

- **Keyword-heavy structures**: hako wins big — `nested-map`
  (50 kw) is **~55% smaller** than nippy. Per-message symbol table
  compresses repeat idents to 1-byte symrefs; peers re-emit the
  full ident each time.
- **Homogeneous numeric arrays**: nippy wins via varint on
  `long-array-1k` / `double-array-1k`. hako uses fixed 8-byte
  layout — same as `MemorySegment.copy` intrinsics, no per-element
  encode/decode branching on the hot path. The wire loss buys the
  perf win visible in the encode/decode tables above.
- **Large repetitive strings**: nippy compresses `string-10k` to
  61 bytes via Snappy. hako intentionally does not compress —
  wrap in your transport-layer compression if you need it. Keeps
  the decode path free of decompression bomb amplification.

Everywhere else, hako is within a few bytes of the best peer.

### Reproduce

```sh
clj -M:bench -m bench                  # full sweep (~15 min)
clj -M:bench -m bench nested-map       # single payload
clj -M:bench -m quick                  # 5-payload triage bench, ~40 s
```

## Documentation

Full user guides in [`docs/`](docs/):

- [Getting started](docs/getting-started.md)
- [API reference](docs/api-reference.md)
- [Supported types](docs/types.md)
- [Extensions](docs/extensions.md) — records + user-tags
- [Arenas & MemorySegment](docs/arenas.md) — memory model
- [Streaming & batch](docs/streaming.md) — `encode-many`, log-file
  patterns
- [Performance & tuning](docs/performance.md)
- [Thread safety](docs/thread-safety.md)

Wire-format specifications:

- [SPEC.md](SPEC.md) — byte-level wire-format specification.
- [EXTENSIONS.md](EXTENSIONS.md) — extension registry.
- [WIRE_EXAMPLES.md](WIRE_EXAMPLES.md) — annotated byte-by-byte
  encoding examples.

Other:

- [docs/migration-nippy.md](docs/migration-nippy.md) — Nippy → hako
  guide.
- [CHANGELOG.md](CHANGELOG.md) — release notes.

## Development

```sh
clj -T:build javac       # compile Java sources → target/classes
clj -T:build javac-test  # compile Java test-support classes
clj -M:test              # run full test suite (currently 425 assertions)
clj -M:bench -m bench    # criterium benchmarks vs peers (~15min)
clj -M:bench -m quick    # 5-payload triage bench (~40s)
clj -T:build jar         # build the release jar
```

## License

Mozilla Public License 2.0 — see [LICENSE](LICENSE).
