# Meep binary format — v0

Schemaless, self-describing, one-shot, JVM-focused, little-endian.

Design goals:
- Zero-copy read for byte blobs and string payloads.
- Low allocation write / read via caller-owned `MemorySegment`.
- No varint on hot path — fixed-width size tiers only.
- Per-message symbol table for interned keys (keywords, symbols, record classnames).
- No shared state across messages.

Non-goals:
- Cross-language wire compatibility.
- Streaming / partial reads.
- Backwards compatibility with anything.

---

## 1. Byte order

All fixed-width integers and floats are **little-endian**.

## 2. Envelope

```
<magic:4><version:1><root-value>
```

- `magic` = `0x4D 0x45 0x45 0x50` ("MEEP")
- `version` = `0x00` for this document

`root-value` follows the encoding in §3.

Message boundary = end of `root-value`. There is no length prefix or trailer.
Callers frame messages externally if needed.

## 3. Values

Every value starts with a **tag byte**:

```
7 6 5 4 3 2 1 0
| major | low  |
```

- Bits 7-4: **major type** (16 categories).
- Bits 3-0: **low nibble**, semantics per major.

### 3.1 Size-tier convention

Many majors use the low nibble to encode either an inline value or a
size-of-length-field indicator:

| Low nibble | Meaning                              |
|------------|--------------------------------------|
| 0..11      | Inline value (0..11) — no length or count follows |
| 12         | Length/count = next 1 byte (u8)      |
| 13         | Length/count = next 2 bytes (u16 LE) |
| 14         | Length/count = next 4 bytes (u32 LE) |
| 15         | Length/count = next 8 bytes (u64 LE) |

Applicable majors are marked "size-tier" below.

### 3.2 Major type table

| Major | Hex  | Name       | Payload                                      |
|-------|------|------------|----------------------------------------------|
| 0     | 0x0_ | uint       | size-tier: inline value or u8/u16/u32/u64    |
| 1     | 0x1_ | sint       | size-tier: zig-zag encoded, decoded to signed |
| 2     | 0x2_ | float      | low nibble = subtype (see §3.3.3)            |
| 3     | 0x3_ | bytes      | size-tier: length in bytes, then raw bytes   |
| 4     | 0x4_ | string     | size-tier: length in UTF-8 bytes, then bytes |
| 5     | 0x5_ | keyword    | size-tier: length in bytes, then payload; or symref (see §3.4) |
| 6     | 0x6_ | symbol     | same shape as keyword                        |
| 7     | 0x7_ | vector     | size-tier: element count, then N values      |
| 8     | 0x8_ | list       | size-tier: element count, then N values      |
| 9     | 0x9_ | set        | size-tier: element count, then N values      |
| 10    | 0xA_ | map        | size-tier: pair count, then N × (key, val)   |
| 11    | 0xB_ | record     | reserved — record support lives under 0xE (see EXTENSIONS.md) |
| 12    | 0xC_ | symref     | size-tier: symbol-table index (see §3.4)     |
| 13    | 0xD_ | bignumeric | low nibble = subtype (see §3.3.4)            |
| 14    | 0xE_ | tagged     | extension namespace (see EXTENSIONS.md)      |
| 15    | 0xF_ | special    | low nibble = subtype (see §3.3.5)            |

### 3.3 Sub-type encodings

#### 3.3.1 uint (major 0)

Unsigned, non-negative. Low nibble uses size-tier:

- 0..11: value is the low nibble itself.
- 12: next 1 byte as u8.
- 13: next 2 bytes as u16 LE.
- 14: next 4 bytes as u32 LE.
- 15: next 8 bytes as u64 LE (interpreted unsigned; Clojure decodes to `Long` if in range, else BigInt).

#### 3.3.2 sint (major 1)

Signed integer, encoded via zig-zag (`(n << 1) ^ (n >> 63)` for i64).

- 0..11: inline zig-zag value (decodes to 0, -1, 1, -2, 2, -3, 3, -4, 4, -5, 5, -6).
- 12: next 1 byte as u8, then zig-zag decode.
- 13: u16 LE.
- 14: u32 LE.
- 15: u64 LE (Long or BigInt).

Encoder policy: emit the smallest tier that fits.

#### 3.3.3 float (major 2)

| Low nibble | Meaning              |
|------------|----------------------|
| 0          | f32 (next 4 bytes)   |
| 1          | f64 (next 8 bytes)   |
| 2..15      | reserved             |

#### 3.3.4 bignumeric (major 13, 0xD_)

All bignumeric payloads use a "raw size-tier value" encoding for byte-count
prefixes: **1 byte tier code + (0 to 8) byte payload** according to the tier
table in §3.1. Distinct from an untagged uint value because there is no
major-type prefix — the byte-count sits directly after the bignumeric tag
byte.

| Low nibble | Meaning     | Payload                                                    |
|------------|-------------|------------------------------------------------------------|
| 0          | BigInteger  | raw-tier byte-count, then two's-complement big-endian bytes |
| 1          | BigDecimal  | i32 scale (LE), then raw-tier byte-count, then unscaled two's-complement big-endian bytes |
| 2          | Ratio       | numerator (raw-tier byte-count + bytes), then denominator (raw-tier byte-count + bytes) |
| 3..15      | reserved    |                                                            |

#### 3.3.5 special (major 15, 0xF_)

| Low nibble | Value                              |
|------------|------------------------------------|
| 0          | nil                                |
| 1          | true                               |
| 2          | false                              |
| 3          | NaN (f64)                          |
| 4          | +Inf (f64)                         |
| 5          | -Inf (f64)                         |
| 6          | UUID (next 16 bytes)               |
| 7          | java.time.Instant (i64 epoch-seconds LE + i32 nanos LE) |
| 8          | char (next 2 bytes as u16 LE, decoded as UTF-16 code unit) |
| 9..15      | reserved                           |

### 3.4 Symbol table (interning)

Per-message table for keyword, symbol, and record classname payloads.

**Write rule:** the first time a keyword/symbol payload is emitted, encode
inline via major 5 or 6 with size-tier length. Immediately after emission,
assign it the next auto-incrementing table index (starting at 0).

**Read rule:** track the same auto-incrementing index on decode.

**Reuse rule:** subsequent occurrences MAY be emitted as major 12 (symref)
whose size-tier payload is the index. Encoder MUST emit inline the first
time; MAY choose to inline again (no dedup); indices only ever grow.

Rationale: per-message state, no shared symbol tables between messages, no
allocation from external context, safe for untrusted decode.

Keyword payload shape (when emitted inline):

```
<size-tier length> <ns-length:u8> <ns bytes> <name bytes>
```

- `ns-length` = 0 means keyword has no namespace; entire remaining payload is the name.
- ns-length + 1 + name-length == size-tier length.

Symbol payload uses the identical shape.

### 3.5 Collections

- Container tags encode **element count** exactly (not stream-terminated).
- Sets and maps: iteration order on write is implementation-defined.
- Maps do not distinguish concrete map type on the wire. See §5.
- Vectors, lists, sets, and maps use the same size-tier convention.

## 4. Extensions (major 0xE)

Reserved namespace for user- and library-defined tags. See `EXTENSIONS.md`.

Reader behavior for unknown extension tag: throw by default; opt-in `{:tolerant? true}` returns an opaque `TaggedValue{id, payload-bytes}`.

## 5. Concrete-type roundtrip guarantees

Semantic equality (`=`) is preserved for all types listed below. Concrete
Clojure type is **not** preserved in these cases:

- All maps decode to `PersistentHashMap` (regardless of source being array-map / hash-map).
- All sets decode to `PersistentHashSet` (unless sorted-set extension emitted).
- Lists / seqs / cons chains decode to `PersistentList`.
- Metadata is dropped unless the writer was configured with `{:meta? true}`.

Preserved via extension tag (see `EXTENSIONS.md`):

- `sorted-map` and `sorted-set` with the **default** comparator.
- `PersistentQueue`.
- Records (via registry).
- Metadata (opt-in).

Explicit write failures (loud, never silent):

- `sorted-map-by` / `sorted-set-by` with custom comparator.
- Any collection type not covered above.

## 6. Limits

- Container element count: up to 2^64 − 1 (u64 size-tier).
- Byte string / UTF-8 length: same.
- Symbol table index: same.

Practical limits are bounded by `MemorySegment` addressable size and JVM heap.

## 7. Determinism (non-guarantee)

Encoded output is **not** required to be deterministic across:

- Map / set iteration order.
- Choice of size-tier when multiple fit (encoder MAY pick larger).
- Symbol-table interning choices (see §3.4 reuse rule).

Applications requiring canonical form MUST post-process (e.g. via a canonical
extension pass, not defined here).

## 8. Security

Decoder MUST:

- Enforce that container counts do not exceed remaining segment bytes / value
  slots. Reject truncated messages before allocation.
- Reject unknown record classnames unless the class is in the reader's
  registry. No `Class.forName` on decoder input.
- Never invoke arbitrary constructors from wire data.
