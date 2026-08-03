# Hako extensions — v0

Extension namespace: tag major `0xE` (see `SPEC.md` §4).

Extension frame layout: the tag byte `0xE0` (low nibble MUST be 0 —
any other value in the low nibble is a malformed frame), followed by
one u8 subtype byte selecting from a uniform 256-id namespace, then
the subtype payload.

## E.1 Reserved built-in extensions

| Subtype | Name          | Payload                                                     |
|---------|---------------|-------------------------------------------------------------|
| 0          | sorted-set    | size-tier count, then N values                              |
| 1          | sorted-map    | size-tier count, then N × (key, val)                        |
| 2          | queue         | size-tier count, then N values (`PersistentQueue`)          |
| 3          | record        | symref-encoded classname, size-tier field count, then N values |
| 4          | with-meta     | inner value, then map value (metadata)                      |
| 5          | prim-longs    | size-tier count, then N × i64 LE (packed `long[]`)          |
| 6          | prim-doubles  | size-tier count, then N × f64 LE (packed `double[]`)        |
| 7          | prim-ints     | size-tier count, then N × i32 LE (packed `int[]`)           |
| 8          | prim-floats   | size-tier count, then N × f32 LE (packed `float[]`)         |
| 9          | prim-shorts   | size-tier count, then N × i16 LE (packed `short[]`)         |
| 10         | prim-chars    | size-tier count, then N × u16 LE (packed `char[]`)          |
| 11         | prim-booleans | size-tier count, then N × u8 (packed `boolean[]`, one byte per element) |
| 12..14     | reserved      |                                                              |
| 15         | user-tag      | u32 LE user tag id, then payload (see §E.2)                 |
| 16..255    | reserved      |                                                              |

Notes:

- **sorted-set / sorted-map**: default `compare` only. Custom comparators
  cause write failure at the encoder.
- **record**: reader looks up classname in its registry. Unregistered
  classname triggers strict error, unless reader is configured with a
  fallback strategy (see reader options).
- **with-meta**: outer wrapper; skipped by decoders that don't request
  metadata preservation. Payload structure allows the wrapped value to be
  parsed independently of metadata handling.
- **prim-longs / prim-doubles / prim-ints / prim-floats /
  prim-shorts / prim-chars / prim-booleans**: homogeneous primitive
  arrays encoded packed. Writer detects `long[]` / `double[]` /
  `int[]` / `float[]` / `short[]` / `char[]` / `boolean[]` inputs
  directly. With `:pack-homogeneous true`, also detects homogeneous
  vectors of Long / Double (packed as prim-longs / prim-doubles —
  other element types are NOT auto-detected to avoid ambiguity with
  widening). Reader returns the corresponding typed array.
- **prim-booleans**: encoders MUST emit `0` or `1` per element;
  decoders MUST treat any non-zero byte as `true`.
- **reserved subtypes**: decoders MUST throw on any reserved subtype
  (decoder too old, or corrupt frame — never silently skip, payload
  length is unknown for built-ins).

## E.2 User tag registry

Applications register additional tags under subtype `15` (`user-tag`).
Frame layout:

```
<0xE0> <0x0F>
<user-tag-id : u32 LE>
<length-prefix : tier-value>          ; always emitted as TIER_U32 code (0x0E) + u32 LE
<payload bytes : length>
```

The length prefix is fixed as TIER_U32 (5 bytes total). This lets a
decoder skip an unknown user-tag by advancing `length` bytes past the
prefix — enabling forward-compatible reads (see §E.3).

Payload maximum: 2^32 − 1 bytes (~4 GB).

Ranges:

- `0x00000000` – `0x0000FFFF` — reserved for hako core.
- `0x00010000` – `0x0FFFFFFF` — registered third-party tags.
- `0x10000000` – `0xFFFFFFFF` — private / application-defined.

Registration for the public range happens by PR against this document.

The `ext/register-user-tag!` Clojure API accepts a small app-local id
(`0..0x0FFFFFFF`) and shifts it into the private range on the wire —
callers write `1`, `2`, ... rather than picking hex constants. Use
`ext/register-user-tag-raw!` when coordinating on a specific u32 wire id.

## E.3 Reader tolerance

Reader options:

- `:tolerate-unknown-tags true` — an unregistered user-tag id is not fatal. The
  decoder skips over the length-prefixed payload and yields a
  `s-exp.hako.ext/TaggedValue{:ext id :bytes payload}` where `payload`
  is a `MemorySegment` slice of the raw bytes. Unknown built-in ext
  subtypes still throw — those are spec bugs, not schema drift.
- `:tolerate-unknown-tags false` (default) — an unregistered user-tag id throws
  `ex-info` with `{:type ::unknown-user-tag :id id}`.
