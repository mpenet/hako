# Supported types

Semantic equality (`=`) is preserved for every listed type. Concrete
JVM class may differ — hako roundtrips value semantics, not
class-identity.

- [Scalars](#scalars)
- [Strings and bytes](#strings-and-bytes)
- [Identifiers](#identifiers)
- [Numerics](#numerics)
- [Time and identity](#time-and-identity)
- [Collections](#collections)
- [Records](#records)
- [Fallback for unknown types](#fallback-for-unknown-types)
- [Concrete-type contract](#concrete-type-contract)

## Scalars

| Clojure         | Java                    | Wire                                     |
|-----------------|-------------------------|------------------------------------------|
| `nil`           | `null`                  | special `0xF0`                           |
| `true` / `false`| `Boolean.TRUE / FALSE`  | special `0xF1` / `0xF2`                  |
| `\a`            | `Character`             | special `0xF8` + u16 code unit           |
| `3.14`          | `Double`                | float `0x21` + f64 LE                    |
| `3.14M`         | `Float`                 | float `0x20` + f32 LE                    |
| `42`            | `Long` (and cross-type Integer / Short / Byte) | uint / sint tier + tier payload |

Integers use a size-tier scheme (inline `0..11`, u8, u16, u32, u64)
for uints, and zig-zag + same tiers for negatives. `Integer`,
`Short`, `Byte` all decode as `Long` — the wire format has one
integer path.

Special float values:

| Value              | Wire                     |
|--------------------|--------------------------|
| `Double/NaN`       | special `0xF3`           |
| `Double/POSITIVE_INFINITY` | special `0xF4`     |
| `Double/NEGATIVE_INFINITY` | special `0xF5`     |

## Strings and bytes

| Clojure         | Wire                                    |
|-----------------|-----------------------------------------|
| `"hello"`       | string major `0x4` + UTF-8 bytes         |
| `byte[]`        | bytes major `0x3` + raw bytes            |

Strings are stored as UTF-8. Decoding to a Clojure `String` is
unavoidable (JDK's `new String(bytes, UTF_8)` handles both LATIN1
compact strings and full UTF-8).

With `{:zero-copy true}`, `byte[]` payloads decode to
`MemorySegment` slices instead of fresh `byte[]`. See
[Arenas](arenas.md).

## Identifiers

| Clojure           | Wire                                                             |
|-------------------|------------------------------------------------------------------|
| `:foo`            | keyword major `0x5` + `<ns-length:u8><ns bytes><name bytes>`      |
| `:ns/foo`         | same, with `ns = "ns"`                                            |
| `'foo`, `'ns/foo` | symbol major `0x6`, same layout                                   |

First occurrence in a message is emitted inline; subsequent
occurrences are one-byte `symref`s (major `0xC`) that point into
the per-message symbol table.

`Keyword` and `Symbol` use distinct symbol-table slots even if
they have the same name (`:foo` and `'foo` do not collide — this
was a latent bug fixed in 0.1.0).

## Numerics

| Clojure                    | Wire                                       |
|----------------------------|--------------------------------------------|
| `BigInteger`               | bignumeric major `0xD0` — 2's-complement BE bytes |
| `clojure.lang.BigInt`      | same wire; decoded as `BigInt`             |
| `BigDecimal`               | bignumeric `0xD1` — i32 scale + BigInt unscaled |
| `Ratio`                    | bignumeric `0xD2` — num + den BigInts       |

Precision is exact.

## Time and identity

| Clojure / JDK           | Wire                                              |
|-------------------------|---------------------------------------------------|
| `java.util.UUID`        | special `0xF6` + 16 bytes                          |
| `java.time.Instant`     | special `0xF7` + i64 epoch-sec + i32 nanos         |

Instants roundtrip pre-1970 (negative epoch-sec) correctly.

## Collections

| Clojure               | Wire                                             |
|-----------------------|--------------------------------------------------|
| `[]`, `[1 2 3]`       | vector major `0x7` + count + elements             |
| `'(1 2 3)`            | list major `0x8` + count + elements               |
| `#{1 2 3}`            | set major `0x9` + count + elements                |
| `{:a 1 :b 2}`         | map major `0xA` + count + (k, v) pairs            |

Set / map iteration order on the wire is undefined. Decoded values
compare semantically (`=`) with the source but may not iterate in
the same order.

### Sorted collections

| Clojure               | Wire                                              |
|-----------------------|---------------------------------------------------|
| `(sorted-set 3 1 2)`  | extension `0xE0` + count + values                  |
| `(sorted-map ...)`    | extension `0xE1` + count + (k, v) pairs           |

Only the default comparator (`compare`) roundtrips. Custom
comparators throw at encode unless `{:coerce-custom-comparator true}`
is set, in which case the comparator is silently dropped (falls back
to `compare` on decode).

### Queues

| Clojure                              | Wire                     |
|--------------------------------------|--------------------------|
| `clojure.lang.PersistentQueue/EMPTY` | extension `0xE2` + count + values |

Decodes as `PersistentQueue`.

### Prim arrays

| Java     | Wire                                                     |
|----------|----------------------------------------------------------|
| `long[]` | extension `0xE5` — packed i64 LE                         |
| `double[]` | extension `0xE6` — packed f64 LE                       |
| `int[]`  | extension `0xE7` — packed i32 LE                         |
| `float[]` | extension `0xE8` — packed f32 LE                        |

With `{:pack-homogeneous true}` on encode, vectors of all-`Long`
or all-`Double` elements are auto-detected and emitted as packed
prim arrays. See [Performance](performance.md).

## Records

Both Clojure `defrecord`s and Java `record`s require explicit
registration. See [Extensions §Records](extensions.md#records).

Wire: extension `0xE3` + symref classname + field count + values.

## Fallback for unknown types

If none of the above match at encode time:

1. `IPersistentVector` / `IPersistentMap` / `IPersistentSet` fall
   into the container path.
2. `ISeq` / `Iterable` materialize and encode as a list.
3. User-tag registry lookup by `(class v)` — if found, invokes the
   user's write callback.
4. Otherwise: `IllegalArgumentException` "no writer for value of
   type X".

## Concrete-type contract

The wire format encodes value shape, not concrete class. The
decoder picks a reasonable concrete class per the container's size
and content, adapting to the runtime Clojure version.

### Maps

`PersistentArrayMap` vs `PersistentHashMap` selection is based on
runtime-probed thresholds:

- Clojure ≤ 1.12: array-map cap ≈ 8 for all key types.
- Clojure 1.13+: array-map cap = 8 for mixed keys, 64 for
  all-keyword keys.

hako probes both thresholds at ns-load and adapts. Encoded bytes
from any Clojure version decode on any other Clojure version;
`=` holds; only the runtime class may differ.

### Sets

Similar strategy — hash-set by default; `sorted-set` extension for
the sorted case.

### Lists vs seqs

An `ISeq` (including lazy seqs) encodes as a list. On decode it
materializes to a `PersistentList`. If you need lazy semantics on
the far side, apply `seq` yourself.
