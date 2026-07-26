# hako
[![Clojars Project](https://img.shields.io/clojars/v/com.s-exp/hako.svg)](https://clojars.org/com.s-exp/hako)


**A Modern, Schemaless, Low-alloc Binary Serialization Library for Clojure.**

Built on JDK 25 FFM `MemorySegment`.

## Highlights

- **Zero runtime dependencies** — only `org.clojure/clojure`. No
  compression libs, no transitive graph.
- **Off-heap by default** — encode/decode operate on
  `MemorySegment` via JDK 25 FFM. The reusable `Writer` emits
  output as a `MemorySegment` slice with no per-message `byte[]`
  allocation.
- **Low GC pressure** — arena-scoped writer buffers reused across
  messages; instance-field scratch for string decode; namespaced-
  keyword decode without composite-key allocation.
- **Tunable for zero-copy / low-alloc workloads** — reusable
  `Writer` + `Reader` amortize arena setup, `encode-to-segment`
  writes into your own arena, `:zero-copy` decode returns
  `MemorySegment` slices for byte payloads, `:pack-homogeneous`
  emits typed prim arrays. Pick knobs per call site.
- **Java hot path** — the heavy lifting (per-value dispatch,
  primitive reads/writes, container walking) runs in Java.
- **Per-message symbol table** — repeated keywords / symbols /
  classnames dedup to a 1-byte symref.
- **Secure by default** — safe to decode untrusted input. No
  arbitrary class loading (record registry lookup, never
  `Class.forName` on wire data), no `Serializable` fallback, no
  decompression path (no compression-bomb vector), bounded reads,
  per-message symbol table. See [Security](#security).
- **Extensible** — records (Clojure + Java), user-tag registry with
  length-prefixed frames for forward-compatible reads.

## Status

Pre-release, alpha. Wire format documented in [SPEC.md](SPEC.md).
Extension registry in [EXTENSIONS.md](EXTENSIONS.md). Byte-level
worked examples in [WIRE_EXAMPLES.md](WIRE_EXAMPLES.md).

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

## API

### Encoding

```clj
(hako/encode value)                    ; -> byte[]
(hako/encode value opts)               ; -> byte[]

(hako/encode-to-segment arena value)   ; -> MemorySegment (caller owns arena)
(hako/encode-to-segment arena value opts)

;; Reusable writer for high-throughput encode loops:
(with-open [wr (hako/writer 4096)]
  (dotimes [_ 1000]
    (let [seg (hako/encode-into! wr some-value)]
      ;; consume `seg` before the next call — the slice is
      ;; overwritten on the next encode-into!
      ...)))

;; Batch API — multiple values share one symbol table:
(hako/encode-many [{:a 1} {:a 2} {:a 3}])
;; keyword :a is encoded once, symref'd twice.

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
- `byte[]`, `long[]`, `double[]`, `int[]`, `float[]`.
- `Keyword`, `Symbol` — with per-message symbol table + symref dedup.
- `UUID`, `java.time.Instant`.
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
(import '(java.net URI))

(ext/register-user-tag!
 0x10000001                             ; pick an id in the private range
 URI
 (fn write [w u] (.writeString w (str u)))
 (fn read  [r]
   (let [tag (.getByte r)
         low (bit-and tag 0x0F)
         n (.readTierPayload r (int low))]
     (URI. (.getString r (int n))))))

(hako/decode (hako/encode (URI. "https://example.com")))
;; => #object[java.net.URI ...]
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

Byte-level spec in [SPEC.md](SPEC.md). Worked examples in
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

Criterium quick-bench, JDK 25, `-server -Xmx4g`, direct-linking on.
Single machine — reproduce with `clj -M:bench -m bench`.

hako's core value proposition is **off-heap encoding into a caller-
owned `MemorySegment`** with minimal Java-heap allocation. Four call
styles are measured:

- **hako⤾ →seg** — reused `Writer` emitting a `MemorySegment` slice
  (`hako/encode-into!`). No `byte[]` allocation per call; the
  writer's arena buffer is reused across messages. **The
  differentiator — this is what the design is optimized for.**
- **hako⤾ →byte[]** — same, but with a trailing
  `MemorySegment → byte[]` copy for callers stuck on `byte[]` APIs.
- **hako →byte[]** — one-shot `hako/encode`. Fresh confined arena +
  final off-heap → heap copy per call. Convenient, less optimal.
- **hako encode-to-seg** — one-shot `hako/encode-to-segment` with a
  caller-provided `Arena`. Arena setup cost per call, but the result
  stays off-heap.

Peers (JVM-only, on-heap output): `nippy` (default `freeze`,
compression + checksums), `nippy-fast` (`fast-freeze`, no
compression), `deed` (`com.github.igrishaev/deed-core 0.1.0`), and
`transit` (`com.cognitect/transit-clj 1.0.333`, MsgPack — different
niche, reference only).

### Encode — hako call-style ladder

Absolute times per call. `hako⤾ →seg` is the ceiling; other columns
show what each additional convenience costs.

| payload              | hako⤾ →seg | hako⤾ →byte[] | hako →byte[] | encode-to-seg |
|----------------------|-----------:|--------------:|-------------:|--------------:|
| `long-array-1k`      |     192 ns |        723 ns |       912 ns |        665 ns |
| `double-array-1k`    |     178 ns |        698 ns |       845 ns |        636 ns |
| `string-100`         |      34 ns |         45 ns |        97 ns |        132 ns |
| `small-map`          |     209 ns |        213 ns |       216 ns |        251 ns |
| `mixed`              |     285 ns |        350 ns |       371 ns |        436 ns |
| `string-10k`         |    745 ns  |       1146 ns |      1628 ns |       1197 ns |
| `vec-of-strings`     |     1.35 µs|       1.38 µs |      1.65 µs |       1.69 µs |
| `nested-map` (50 kw) |     5.54 µs|       5.55 µs |      5.90 µs |       6.22 µs |
| `vec-of-longs` (1k)  |     7.51 µs|       7.60 µs |      8.18 µs |       8.21 µs |

The segment-out path wins by 3.5–5× on prim arrays, 2× on
long strings, and matches the byte[] paths on collection payloads
(where per-value dispatch dominates the arena/copy costs).

### Encode — hako⤾ →seg vs peers

| payload              | hako⤾ →seg | nippy    | nippy-fast | deed     | transit  |
|----------------------|-----------:|---------:|-----------:|---------:|---------:|
| `long-array-1k`      |     192 ns |  18.5 µs |    18.6 µs |  11.2 µs |  21.9 µs |
| `double-array-1k`    |     178 ns |  22.6 µs |    10.9 µs |  10.9 µs |  24.6 µs |
| `string-100`         |      34 ns |   123 ns |      73 ns |   418 ns |   3.0 µs |
| `small-map`          |     209 ns |   318 ns |     252 ns |   641 ns |   3.8 µs |
| `mixed`              |     285 ns |   528 ns |     493 ns |   871 ns |   4.2 µs |
| `string-10k`         |    745 ns  |   2.8 µs |     1.1 µs |   2.2 µs |   4.5 µs |
| `vec-of-strings`     |     1.35 µs|   2.23 µs|     2.22 µs|   3.89 µs|   7.09 µs|
| `nested-map` (50 kw) |     5.54 µs|  11.13 µs|    11.40 µs|  16.27 µs|  36.89 µs|
| `vec-of-longs` (1k)  |     7.51 µs|  17.47 µs|    17.18 µs|  21.54 µs|  31.07 µs|

hako⤾ →seg leads every cell — including `string-10k` where the
byte[]-output paths lose to `nippy-fast`.

### Decode

Decode has two hako variants — one-shot `hako/decode` (byte[] source,
wrapped via `MemorySegment/ofArray` internally) and reused
`hako/decode-into!` (segment source, no wrap per call). Both take
`{:cache-idents true}`.

| payload              | hako⤾ (seg src) | hako (byte[] src) | nippy    | nippy-fast | deed     | transit  |
|----------------------|----------------:|------------------:|---------:|-----------:|---------:|---------:|
| `long-array-1k`      |          589 ns |            602 ns |  12.1 µs |    12.0 µs |  10.8 µs |   200 µs |
| `double-array-1k`    |          561 ns |            591 ns |  12.1 µs |     8.1 µs |  10.8 µs |   176 µs |
| `string-100`         |           45 ns |             57 ns |    95 ns | **47 ns**  |   571 ns |   2.8 µs |
| `small-map`          |          189 ns |            207 ns |   267 ns |     204 ns |   802 ns |   3.3 µs |
| `mixed`              |          401 ns |            435 ns |   598 ns |     558 ns |   1.4 µs |   4.7 µs |
| `string-10k`         |          928 ns |           1.10 µs |   3.6 µs |     1.1 µs |   1.7 µs |   6.0 µs |
| `vec-of-strings`     |         2.77 µs |          2.83 µs  |  4.09 µs |    3.97 µs |  8.25 µs |  16.80 µs|
| `nested-map` (50 kw) |         6.46 µs |          6.97 µs  | 14.73 µs |   13.74 µs | 25.98 µs |  59.07 µs|
| `vec-of-longs` (1k)  |        11.45 µs |         11.45 µs  | 12.05 µs |   11.85 µs | 25.52 µs | 199.87 µs|

Only `nippy-fast` on `string-100` decode edges hako⤾ (2 ns gap, noise
basically). Nippy's `readUTF` intrinsic is unbeatable on payloads smaller than a
cache line — matching would require a wire-format change to MUTF-8.  See
[Performance](docs/performance.md) for the tradeoffs.

### Records — 100 records in a vector

| metric | hako | nippy-fast | multiplier |
|---|---:|---:|---:|
| encode  | **4.3 µs**  | 28 µs   | **6.4×** |
| decode  | **12 µs**   | 72 µs   | **5.9×** |
| size    | **706 B**   | 2473 B  | **3.5× smaller** |

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
