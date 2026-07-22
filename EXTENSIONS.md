# Meep extensions — v0

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
| 7          | prim-bytes    | equivalent to major 3 (bytes) but reader returns `byte[]` copy rather than segment view |
| 8..14      | reserved      |                                                              |
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
- **prim-longs / prim-doubles**: homogeneous numeric collections encoded
  packed. Writer detects `long[]` / `double[]` inputs and homogeneous
  vectors of `Long` / `Double` (opt-in flag). Reader returns a `long[]` /
  `double[]` by default; a `MemorySegment` slice may be requested.

## E.2 User tag registry

Applications register additional tags under low nibble `15` (`user-tag`):

```
<0xEF><user-tag-id:u32 LE><payload>
```

Ranges:

- `0x00000000` – `0x0000FFFF` — reserved for meep core.
- `0x00010000` – `0x0FFFFFFF` — registered third-party tags.
- `0x10000000` – `0xFFFFFFFF` — private / application-defined.

Registration for the public range happens by PR against this document.

## E.3 Reader tolerance

Reader options:

- `:tolerant? true` — unknown extension tag (or unknown user-tag id) does
  not throw; returns `s-exp.meep.ext/TaggedValue{:ext id, :bytes payload}`
  where `payload` is a raw `MemorySegment` slice.
- `:tolerant? false` (default) — throws `ex-info` with `{:type
  ::unknown-extension :id id}`.
