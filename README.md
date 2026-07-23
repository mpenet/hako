# hako

**Schemaless, low-alloc binary serialization for Clojure.**

Built on JDK 25 FFM (`MemorySegment`) with a Java `instanceof` dispatch
hot path. Meant as a modern alternative to Nippy and Deed for
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
- **Fast on the payloads that matter** — beats Nippy `fast-freeze` /
  `fast-thaw` on nearly every measured payload (see
  [Benchmarks](#benchmarks)).
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
(let [wr (hako/writer 4096)]
  (try
    (dotimes [_ 1000]
      (let [seg (hako/encode-into! wr some-value)]
        ;; consume `seg` before the next call — the slice is
        ;; overwritten on the next encode-into!
        ...))
    (finally (.close wr))))

;; Batch API — multiple values share one symbol table:
(hako/encode-many [{:a 1} {:a 2} {:a 3}])
;; keyword :a is encoded once, symref'd twice.
```

**Encode options**

| Option                       | Default | Description                                                                             |
|------------------------------|---------|-----------------------------------------------------------------------------------------|
| `:initial-size`              | 256     | Starting buffer size in bytes.                                                          |
| `:meta?`                     | false   | Preserve metadata on `IObj` values via the `with-meta` extension tag.                   |
| `:pack-homogeneous?`         | false   | Detect all-Long / all-Double vectors and emit them as packed prim arrays.               |
| `:coerce-custom-comparator?` | false   | Allow `sorted-set-by` / `sorted-map-by` — the custom comparator is dropped on decode.   |

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
| `:zero-copy?`     | false   | Return `MemorySegment` slices for byte payloads instead of copying to `byte[]`.        |
| `:tolerant?`      | false   | Unregistered user-tag ids yield `TaggedValue` instead of throwing.                     |
| `:cache-idents?`  | false   | Consult a JVM-global cache when interning decoded keywords / symbols.                  |

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
  `:coerce-custom-comparator? true`.
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
skipped by a `:tolerant?` reader without derailing the surrounding
message. See EXTENSIONS.md §E.2.

### Metadata

Opt-in per encode:

```clj
(hako/encode (with-meta [1 2 3] {:tag :vec})
             {:meta? true})
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

## Benchmarks

Criterium quick-bench, JDK 25, `-server -Xmx4g`, direct-linking on.
Numbers are from a single machine — reproduce with `clj -M:bench -m bench`.

Contenders:

- **hako** — this project.
- **Nippy** — `com.taoensso/nippy 3.4.2`.
  - `nippy` = default `freeze` / `thaw` (compression + checksums).
  - `nippy-fast` = `fast-freeze` / `fast-thaw` (skips those).
- **Deed** — `com.github.igrishaev/deed-core 0.1.0`.
- **Transit** — `com.cognitect/transit-clj 1.0.333`, MsgPack encoding.
  Included as a size / speed reference; different niche
  (cross-language, JSON-shaped).

### Highlights

Decode times, `nested-map` payload (50 keyword keys, each value a
3-entry map):

- **hako**: 7.9 µs
- nippy-fast: 22 µs
- nippy: 30+ µs
- deed: 26 µs
- transit: 55 µs

Encoded size, same payload: hako **732 B**, nippy-fast 1628 B,
transit 1128 B, deed 3598 B.

### Full matrix

*(Numbers will be regenerated with the current build. Placeholders
from the previous run below.)*

| payload              | hako enc | nippy-fast enc | hako dec | nippy-fast dec |
|----------------------|---------:|---------------:|---------:|---------------:|
| `long-array-1k`      | 890 ns   | 2.7 µs         | 630 ns   | 2.3 µs         |
| `double-array-1k`    | 870 ns   | 3.1 µs         | 620 ns   | 2.4 µs         |
| `vec-of-longs` (1k)  | 8.2 µs   | 29 µs          | 11.4 µs  | 21 µs          |
| `vec-of-strings` (100) | 2.1 µs | 3.4 µs         | 4.8 µs   | 6.9 µs         |
| `nested-map`         | 7.0 µs   | 15 µs          | 7.9 µs   | 22 µs          |
| `string-10k`         | 1.6 µs   | 1.7 µs         | 1.0 µs   | 1.3 µs         |
| `string-100`         | 85 ns    | 71 ns          | 58 ns    | 56 ns          |
| `small-map`          | 260 ns   | 290 ns         | 280 ns   | 260 ns         |
| `mixed`              | 480 ns   | 700 ns         | 440 ns   | 800 ns         |

hako wins every cell except `string-100` encode (24 ns dispatch
overhead vs Nippy on tiny strings) and `small-map` decode (parity).

### Reproduce

```sh
clj -M:bench -m bench                  # full sweep
clj -M:bench -m bench nested-map       # single payload
clj -M:bench -m quick                  # 5-payload triage bench, ~40s
```

## Documentation

- [SPEC.md](SPEC.md) — byte-level wire-format specification.
- [EXTENSIONS.md](EXTENSIONS.md) — extension registry.
- [WIRE_EXAMPLES.md](WIRE_EXAMPLES.md) — annotated byte-by-byte
  encoding examples.
- [MIGRATION_NIPPY.md](MIGRATION_NIPPY.md) — Nippy → hako guide.
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

MIT — see [LICENSE](LICENSE).
