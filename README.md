# meep

Schemaless, low-alloc binary serialization for Clojure.

Built on JDK 25 FFM (`MemorySegment`) with a Java hot path for encode /
decode dispatch. Meant as a modern alternative to Nippy and Deed for
JVM-only Clojure workloads.

## Status

Pre-release. Wire format documented in [SPEC.md](SPEC.md). Extension
registry documented in [EXTENSIONS.md](EXTENSIONS.md).

## Requirements

- JDK **25+** (uses `java.lang.foreign` FFM API, requires
  `--enable-native-access=ALL-UNNAMED`).
- Clojure **1.12+** (works on 1.13-alpha — reader adapts to the
  updated `PersistentArrayMap` threshold automatically).

## Quick start

```clj
;; deps.edn
{:deps {com.s-exp/meep {:mvn/version "0.1.0"}}
 :aliases {:run {:jvm-opts ["--enable-native-access=ALL-UNNAMED"]}}}
```

```clj
(require '[s-exp.meep :as meep])

(def bs (meep/encode {:name "Alice" :tags #{:a :b :c} :score 42}))
(meep/decode bs)
;; => {:name "Alice", :tags #{:a :b :c}, :score 42}
```

## API

### Encoding

```clj
(meep/encode value)                    ; -> byte[]
(meep/encode value opts)               ; -> byte[]

(meep/encode-to-segment arena value)   ; -> MemorySegment (caller owns arena)
(meep/encode-to-segment arena value opts)

;; Reusable writer for high-throughput encode loops:
(let [wr (meep/writer 4096)]           ; initial buffer size in bytes
  (try
    (dotimes [_ 1000]
      (let [seg (meep/encode-into! wr some-value)]
        ;; consume seg before next call
        ...))
    (finally (.close wr))))
```

**Encode options**

| Option                | Default | Description                                                                 |
|-----------------------|---------|-----------------------------------------------------------------------------|
| `:initial-size`       | 256     | Starting buffer size in bytes.                                              |
| `:meta?`              | false   | Preserve metadata on `IObj` values via the with-meta extension tag.         |
| `:pack-homogeneous?`  | false   | Detect all-Long / all-Double vectors and emit them as packed prim arrays.   |

### Decoding

```clj
(meep/decode src)                      ; src is byte[] or MemorySegment
(meep/decode src opts)

;; Reusable reader:
(let [rd (meep/reader some-src)]
  (meep/decode-into! rd another-src)   ; rebinds and resets state
  ...)
```

**Decode options**

| Option            | Default | Description                                                                            |
|-------------------|---------|----------------------------------------------------------------------------------------|
| `:zero-copy?`     | false   | Return `MemorySegment` slices for byte payloads instead of copying to `byte[]`.        |
| `:tolerant?`      | false   | Unknown user-tag ids yield `TaggedValue` instead of throwing.                          |
| `:cache-idents?`  | false   | Consult a JVM-global cache when interning decoded keywords / symbols.                  |

## Supported types

All roundtrip semantic (`=`) equality is preserved.

- `nil`, `boolean`, `Character`, `Long`, `Integer`, `Short`, `Byte`,
  `Double`, `Float`, `String`, `byte[]`, `long[]`, `double[]`.
- `Keyword`, `Symbol` — with per-message symbol table + symref dedup.
- `UUID`, `java.time.Instant`.
- `BigInteger`, `clojure.lang.BigInt`, `BigDecimal`, `Ratio`.
- `PersistentVector`, `PersistentList`, `PersistentHashSet`,
  `PersistentHashMap`, `PersistentArrayMap`, `ISeq`.
- `PersistentTreeSet`, `PersistentTreeMap` — only with the default
  comparator. Custom comparators cause a write error rather than a
  silent lossy roundtrip.
- `PersistentQueue`.
- Records (via `ext/register-record!`).
- User-defined types (via `ext/register-user-tag!`).

Concrete map / set impls may differ across Clojure versions (the
reader picks `PersistentArrayMap` vs `PersistentHashMap` based on the
runtime threshold). See `SPEC.md` §5 for the roundtrip contract.

## Extensions

### Records

```clj
(require '[s-exp.meep.ext :as ext])

(defrecord Point [x y])
(ext/register-record! Point)

(meep/decode (meep/encode (->Point 3 4)))
;; => #user.Point{:x 3, :y 4}
```

Registration reflects on the record class once and caches a
`MethodHandle` for the canonical positional constructor.
Unregistered record classes cause an encode-time error.

### User-tagged types

```clj
(import '(java.net URI))
(require '[s-exp.meep.ext :as ext]
         '[s-exp.meep :as meep])

(ext/register-user-tag!
  0x10000001
  URI
  (fn write [w ^URI u] (.writeString w (str u)))
  (fn read [r]
    ;; Consume exactly the payload bytes announced in the frame.
    (let [tag (.getByte r)
          low (bit-and tag 0x0F)
          n (.readTierPayload r (int low))]
      (URI. (.getString r (int n))))))

(meep/decode (meep/encode (URI. "https://example.com")))
;; => #object[java.net.URI ...]
```

User-tag frames carry a length prefix, so an unknown tag id can be
skipped by a `:tolerant?` reader without breaking the surrounding
message. See EXTENSIONS.md §E.2.

### Metadata

Opt in per encode:

```clj
(meep/encode (with-meta [1 2 3] {:tag :vec})
             {:meta? true})
```

## Wire format

Byte-level spec in [SPEC.md](SPEC.md). Highlights:

- 5-byte envelope `<magic 'MEEP'><version 0>`.
- Every value starts with a tag byte: high nibble = major type,
  low nibble = size tier or subtype.
- Fixed-width size tiers (inline 0–11, u8, u16, u32, u64) — no
  varint on the hot path.
- Little-endian throughout.
- Per-message symbol table for interned keyword / symbol payloads.
- Zero shared state across messages.

## Performance

Criterium quick-bench, JDK 25, `-server -Xmx4g`, direct-linking on.

| payload | meep enc | nippy enc | deed enc | meep dec | nippy dec | deed dec |
|---|---:|---:|---:|---:|---:|---:|
| `long-array-1k` | **902 ns** | 2.73 µs | 11.1 µs | **624 ns** | 2.30 µs | 11.0 µs |
| `double-array-1k` | **871 ns** | 3.11 µs | 11.1 µs | **620 ns** | 2.35 µs | 11.0 µs |
| vec-of-longs (1k) | **10.4 µs** | 28.6 µs | 22.3 µs | **12.5 µs** | 20.8 µs | 25.4 µs |
| vec-of-strings (100) | **2.03 µs** | 3.48 µs | 3.81 µs | **4.81 µs** | 6.40 µs | 8.40 µs |
| nested-map (50 kw) | **11.6 µs** | 14.3 µs | 17.2 µs | **8.50 µs** | 21.1 µs | 26.0 µs |
| string-10k | **1.52 µs** | 1.73 µs | 2.34 µs | **1.10 µs** | 1.26 µs | 1.81 µs |
| string-100 | 103 ns | **79 ns** | 437 ns | **53 ns** | 66 ns | 593 ns |
| small-map | **267 ns** | 293 ns | 642 ns | 317 ns | **287 ns** | 803 ns |
| mixed | **478 ns** | 660 ns | 864 ns | **441 ns** | 797 ns | 1.36 µs |

Encoded size on `nested-map`: meep 732 B, nippy 1628 B, deed 3598 B.

Reproduce: `clj -M:bench -m bench`.

## Development

```sh
clj -T:build javac    # compile Java sources to target/classes
clj -M:test           # run test suite
clj -M:bench -m bench # run benchmarks
```

Layout:

```
src/
  java/com/s_exp/meep/    -- Format, Writer, Reader (hot path)
  clj/s_exp/
    meep.clj              -- public API
    meep/reader.clj       -- decode dispatch
    meep/writer.clj       -- encode dispatch fallback
    meep/ext.clj          -- record + user-tag registries
test/
bench/
SPEC.md
EXTENSIONS.md
```

## License

MIT — see `LICENSE`.
