# hako
[![Clojars Project](https://img.shields.io/clojars/v/com.s-exp/hako.svg)](https://clojars.org/com.s-exp/hako)

**Schemaless, low-alloc binary serialization for Clojure.**

Built on JDK 25 FFM (`MemorySegment`).
Meant as a modern alternative to Nippy and Deed for
JVM-only Clojure workloads.

## Highlights

- **Zero runtime dependencies** — only `org.clojure/clojure`. No
  compression libs, no transitive graph.
- **JDK 25 FFM** — encode/decode operate directly on
  `MemorySegment`, no `ByteBuffer` middleman.
- **Java hot path** — top-level `writeAny` / `readAny` dispatch lives
  in Java, `instanceof` compiled to direct bytecode.
- **Per-message symbol table** — repeated keywords / symbols /
  classnames dedup to a 1-byte symref.
- **Competitive on the payloads that matter** — matches or exceeds
  Nippy `fast-freeze` / `fast-thaw` on nearly every measured payload
  (see [Benchmarks](#benchmarks)).
- **Extensible** — records (Clojure + Java), user-tag registry with
  length-prefixed frames for forward-compatible reads.

## Status

Pre-release (`0.1.0`). Wire format documented in [SPEC.md](SPEC.md).
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

```clj
;; deps.edn
{:deps {com.s-exp/hako {:mvn/version "0.1.0"}}
 :aliases {:run {:jvm-opts ["--enable-native-access=ALL-UNNAMED"]}}}
```

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
- **No Java `Serializable` fallback.** Unlike Nippy, hako has no
  path to `ObjectInputStream`. Deserialization gadget chains are
  not applicable.
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

Contenders:

- **hako** — this project.
- **Nippy** — `com.taoensso/nippy 3.4.2`.
  - `nippy` = default `freeze` / `thaw` (Snappy compression + checksums).
  - `nippy-fast` = `fast-freeze` / `fast-thaw` (skips both).
- **Deed** — `com.github.igrishaev/deed-core 0.1.0`.
- **Transit** — `com.cognitect/transit-clj 1.0.333`, MsgPack encoding.
  Included as a reference; different niche (cross-language, JSON-shaped).

Multipliers below are **peer time ÷ hako time** — larger means hako is
that much faster. Values below 1 mean the peer is faster; **bold**
marks those.

### Encode

| payload              |   hako | vs nippy | vs nippy-fast | vs deed | vs transit |
|----------------------|-------:|---------:|--------------:|--------:|-----------:|
| `long-array-1k`      | 869 ns |    3.5×  |         2.9×  |  12.6×  |     26.0×  |
| `double-array-1k`    | 859 ns |    4.0×  |         3.4×  |  12.6×  |     26.8×  |
| `nested-map` (50 kw) | 6.6 µs |    2.1×  |         2.1×  |   2.6×  |      5.5×  |
| `vec-of-longs` (1k)  |  10 µs |    2.9×  |         2.8×  |   2.2×  |      3.2×  |
| `vec-of-strings`     | 2.0 µs |    1.7×  |         1.7×  |   1.9×  |      3.5×  |
| `mixed`              | 396 ns |    1.8×  |         1.6×  |   2.2×  |     11.0×  |
| `small-map`          | 230 ns |    1.5×  |         1.3×  |   2.8×  |     16.1×  |
| `string-10k`         | 1.6 µs |    1.6×  |         1.0×  |   1.3×  |      2.8×  |
| `string-100`         | 102 ns |    1.3×  |     **0.8×**  |   3.9×  |     29.5×  |

### Decode

| payload              |   hako | vs nippy | vs nippy-fast | vs deed | vs transit |
|----------------------|-------:|---------:|--------------:|--------:|-----------:|
| `long-array-1k`      | 612 ns |    4.1×  |         3.6×  |  18.0×  |    312.9×  |
| `double-array-1k`    | 621 ns |    4.0×  |         3.5×  |  17.3×  |    285.3×  |
| `nested-map` (50 kw) | 8.4 µs |    2.1×  |         2.0×  |   3.0×  |      6.5×  |
| `vec-of-longs` (1k)  |  11 µs |    2.1×  |         2.1×  |   2.2×  |     17.9×  |
| `vec-of-strings`     | 4.8 µs |    1.3×  |         1.5×  |   1.6×  |      3.4×  |
| `mixed`              | 434 ns |    2.1×  |         2.0×  |   3.0×  |     10.5×  |
| `small-map`          | 267 ns |    1.4×  |         1.0×  |   2.9×  |     12.3×  |
| `string-10k`         | 974 ns |    4.3×  |         1.2×  |   1.8×  |      5.9×  |
| `string-100`         |  53 ns |    2.1×  |         1.2×  |  10.6×  |     52.5×  |

**One trailing cell:** `string-100` encode, 24 ns dispatch overhead
vs `nippy-fast` on tiny strings. Everywhere else, hako matches or
comes out ahead.

### Records — 100 defrecords in a vector

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
| `long-array-1k`       |    8009 B  |  8038 B |  8034 B |  8040 B | **2619 B** |
| `vec-of-strings`      |     797 B  |   896 B |   892 B |  1330 B |  **793 B** |
| `mixed`               |    **46 B**|    55 B |    51 B |   154 B |    54 B  |
| `small-map`           |     37 B   |    39 B |    35 B |   101 B | **34 B** |
| `string-100`          |    107 B   |   106 B | **102 B**|  140 B |   108 B  |
| `string-10k`          |   10008 B  |**61 B** | 10003 B | 10040 B | 10008 B  |

`nippy` (default) compresses via Snappy — the 10 000-character
repeating-`x` string collapses to 61 B. Compression's speed cost is
visible in the decode column (4.19 µs vs hako's 974 ns). Transit's
variable-length int encoding wins on numeric arrays with small values.
On real Clojure-shaped data (`nested-map`), hako's per-message symbol
table gives it the edge.

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
clj -M:bench -m bench    # criterium benchmarks vs peers
clj -M:bench -m quick    # 5-payload triage bench (~40s)
clj -T:build jar         # build the release jar
```

Layout:

```
src/
  java/com/s_exp/hako/      -- Format, Writer, Reader (Java hot path)
  clj/s_exp/
    hako.clj                -- public API
    hako/reader.clj         -- decode dispatch + kw / sym cache
    hako/writer.clj         -- encode fallback for records / user-tags
    hako/ext.clj            -- registries (record, user-tag)
resources/
  clj-kondo.exports/        -- kondo hooks for register-user-tag! arity
test/
test-java/                  -- Java records used by the record test suite
bench/
SPEC.md · EXTENSIONS.md · WIRE_EXAMPLES.md · MIGRATION_NIPPY.md
```

## License

Mozilla Public License 2.0 — see [LICENSE](LICENSE).
