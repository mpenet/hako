# Hako extensions — v0

Extension namespace: tag major `0xE` (see `SPEC.md` §4).

Extension tag byte layout: `0xE_` where the low nibble selects an
extension sub-registry. Extension payload follows.

## E.1 Reserved built-in extensions

| Low nibble | Name          | Payload                                                     |
|------------|---------------|-------------------------------------------------------------|
| 0          | sorted-set    | size-tier count, then N values                              |
| 1          | sorted-map    | size-tier count, then N × (key, val)                        |
| 2          | queue         | size-tier count, then N values (`PersistentQueue`)          |
| 3          | record        | symref-encoded classname, size-tier field count, then N values |
| 4          | with-meta     | inner value, then map value (metadata)                      |
| 5          | prim-longs    | size-tier count, then N × i64 LE (packed `long[]`)          |
| 6          | prim-doubles  | size-tier count, then N × f64 LE (packed `double[]`)        |
| 7          | prim-ints     | size-tier count, then N × i32 LE (packed `int[]`)           |
| 8          | prim-floats   | size-tier count, then N × f32 LE (packed `float[]`)         |
| 9..14      | reserved      |                                                              |
| 15         | user-tag      | u32 LE user tag id, then payload (see §E.2)                 |

Notes:

- **sorted-set / sorted-map**: default `compare` only. Custom comparators
  cause write failure at the encoder.
- **record**: reader looks up classname in its registry. Unregistered
  classname triggers strict error, unless reader is configured with a
  fallback strategy (see reader options).
- **with-meta**: outer wrapper; skipped by decoders that don't request
  metadata preservation. Payload structure allows the wrapped value to be
  parsed independently of metadata handling.
- **prim-longs / prim-doubles / prim-ints / prim-floats**: homogeneous
  numeric collections encoded packed. Writer detects `long[]` /
  `double[]` / `int[]` / `float[]` inputs directly. With
  `:pack-homogeneous true`, also detects homogeneous vectors of Long /
  Double (packed as prim-longs / prim-doubles — Integer / Float vectors
  are NOT auto-detected to avoid ambiguity with widening). Reader
  returns the corresponding typed array.

## E.2 User tag registry

Applications register additional tags under low nibble `15` (`user-tag`).
Frame layout:

```
<0xEF>
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

## E.3 Reader tolerance

Reader options:

- `:tolerate-unknown-tags true` — an unregistered user-tag id is not fatal. The
  decoder skips over the length-prefixed payload and yields a
  `s-exp.hako.ext/TaggedValue{:ext id :bytes payload}` where `payload`
  is a `MemorySegment` slice of the raw bytes. Unknown built-in ext
  subtypes (0..6) still throw — those are spec bugs, not schema drift.
- `:tolerate-unknown-tags false` (default) — an unregistered user-tag id throws
  `ex-info` with `{:type ::unknown-user-tag :id id}`.
