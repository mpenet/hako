# API reference

Complete signatures for the public Clojure API and the Java Writer /
Reader surface most Clojure users will touch.

- [`s-exp.hako`](#s-exphako)
- [`s-exp.hako.ext`](#s-exphakoext)
- [Java Writer](#java-writer)
- [Java Reader](#java-reader)

## `s-exp.hako`

### `encode`

```clj
(encode value)          ; -> byte[]
(encode value opts)     ; -> byte[]
```

Encode `value` and return a fresh `byte[]`. Opens a private confined
arena, closes it before returning. Options — see
[Getting started](getting-started.md) and [Performance](performance.md):

| Option                        | Default |
|-------------------------------|---------|
| `:initial-size`               | `256`   |
| `:preserve-meta`                      | `false` |
| `:pack-homogeneous`          | `false` |
| `:coerce-custom-comparator`  | `false` |

### `encode-to-segment`

```clj
(encode-to-segment arena value)         ; -> MemorySegment
(encode-to-segment arena value opts)    ; -> MemorySegment
```

Encode into a segment allocated inside caller-supplied `arena`. The
returned segment invalidates when `arena` closes. Options same as
`encode`. See [Arenas](arenas.md).

### `writer` / `encode-into!`

```clj
(writer)                                 ; -> Writer  (initial-size = 4096)
(writer initial-size)                    ; -> Writer

(encode-into! wr value)                  ; -> MemorySegment (slice)
(encode-into! wr value opts)             ; -> MemorySegment (slice)
```

Reusable Writer. `Writer` implements `AutoCloseable`; wrap in
`with-open`. The returned slice is valid until the next
`encode-into!` call. Options same as `encode`.

### `encode-many`

```clj
(encode-many values)                     ; -> byte[]
(encode-many values opts)                ; -> byte[]
```

Encode many values with **one shared symbol table**. Single envelope,
concatenated encoded values. Options same as `encode`, plus opts
carry through to each value.

### `decode`

```clj
(decode src)                             ; -> value
(decode src opts)                        ; -> value
```

`src` may be a `byte[]` or a `MemorySegment`. Options:

| Option            | Default |
|-------------------|---------|
| `:zero-copy`     | `false` |
| `:tolerate-unknown-tags`      | `false` |
| `:cache-idents`  | `false` |

### `decode-many`

```clj
(decode-many src)                        ; -> vector of values
(decode-many src opts)                   ; -> vector of values
```

Inverse of `encode-many`. Reads values until end-of-input. Options
same as `decode`.

### `reader` / `decode-into!`

```clj
(reader src)                             ; -> Reader

(decode-into! rd src)                    ; -> value
(decode-into! rd src opts)               ; -> value
```

Reusable Reader. `.reset(newSeg)` rebinds internally. Options
same as `decode`.

## `s-exp.hako.ext`

### `TaggedValue`

```clj
(->TaggedValue ext-id memory-segment)   ; deftype
(tagged-value? x)                        ; -> boolean
```

Returned by tolerant decode when an unregistered user-tag is seen.
`:ext` is the u32 tag id; `:bytes` is a `MemorySegment` slice of the
raw payload.

### `register-record!`

```clj
(register-record! record-class)          ; -> record-class
```

Register a Clojure `defrecord` or Java `record` class. Reflects
once, caches a `MethodHandle` for the canonical constructor.
Registered classes participate in encode / decode automatically.

### `register-user-tag!`

```clj
(register-user-tag! id klass write-fn read-fn)   ; -> id
```

Bind a user-tag id (u32; see [../EXTENSIONS.md](../EXTENSIONS.md)
§E.2 for ranges) to a Java class + encode/decode callbacks.

- `write-fn` — `(fn [^Writer w value])` — write the payload bytes.
- `read-fn`  — `(fn [^Reader r])` — parse one value from the
  length-bounded payload region.

### `default-comparator?`

```clj
(default-comparator? sorted-coll)        ; -> boolean
```

`true` if `sorted-coll` uses hako's known natural-ordering
comparator (the one `sorted-set` / `sorted-map` install). Used
internally to detect custom comparators.

## Java Writer

Namespace: `com.s_exp.hako.Writer`.

Constructor and lifecycle:

| Signature                          | Notes                                    |
|------------------------------------|------------------------------------------|
| `new Writer(long initialSize)`     | Opens `Arena.ofConfined()`.              |
| `close()`                          | Closes the arena. `AutoCloseable`.       |
| `reset()`                          | Cursor to 0. Preserves handler + opts.   |
| `finish()`                         | Returns a slice `[0, pos)`.              |

Primitive emitters (mostly consumed by user-tag write-fns):

| Method                                          | Wire effect                          |
|-------------------------------------------------|--------------------------------------|
| `putByte(int)`                                  | 1 byte.                              |
| `putU16(int)`                                   | 2 bytes LE.                          |
| `putU32(long)`                                  | 4 bytes LE.                          |
| `putI32(int)`                                   | 4 bytes LE signed.                   |
| `putU64(long)`                                  | 8 bytes LE.                          |
| `putF32(float)` / `putF64(double)`              | 4 / 8 bytes LE.                      |
| `putBytes(byte[])`                              | Raw copy.                            |
| `putTierValue(long)`                            | Tier code byte + payload.            |
| `putSizedTag(int major, long count)`            | Full tag + tier.                     |

Scalar writers:

| Method                                          |
|-------------------------------------------------|
| `writeNil()`, `writeTrue()`, `writeFalse()`     |
| `writeLong(long)`, `writeDouble(double)`        |
| `writeFloat(float)`, `writeChar(int)`           |
| `writeString(String)`, `writeBytes(byte[])`     |
| `writeUuid(long msb, long lsb)`                 |
| `writeInstant(long epochSec, int nano)`         |
| `writeBigInteger(BigInteger)`                   |
| `writeBigDecimal(BigDecimal)`                   |
| `writeRatio(clojure.lang.Ratio)`                |
| `writeLongArray(long[])` / `writeDoubleArray(double[])` |
| `writeIntArray(int[])` / `writeFloatArray(float[])`     |

Container headers (write value payloads yourself):

- `writeVectorHeader(long n)`
- `writeListHeader(long n)`
- `writeSetHeader(long n)`
- `writeMapHeader(long n)`

Composite:

- `writeInterned(int major, Object internKey, String ns, String name)`
- `writeEnvelope()`
- `writeRecord(Object v)` — full record write.
- `writeAny(Object v)` — top-level dispatch.
- `beginUserTag(int tagId)` → `long mark`; `endUserTag(long mark)`.

Config setters (preserved across `reset()`):

- `setWriteMeta(boolean)`, `setPackHomogeneous(boolean)`,
  `setCoerceCustomComparator(boolean)`
- `setUnknownHandler(UnknownHandler)`

## Java Reader

Namespace: `com.s_exp.hako.Reader`.

Constructor and lifecycle:

| Signature                              |
|----------------------------------------|
| `new Reader(MemorySegment)`            |
| `reset(MemorySegment newSeg)`          |
| `pos()`, `remaining()`, `segment()`    |

Primitive readers:

| Method                          | Returns                    |
|---------------------------------|----------------------------|
| `getByte()`                     | `int` (0..255)             |
| `getU16()`                      | `int` (0..65535)           |
| `getU32()`                      | `long` (0..2^32-1)         |
| `getI32()`                      | `int`                      |
| `getI64()`                      | `long`                     |
| `getF32()`, `getF64()`          | `float` / `double`         |
| `getBytes(int n)`               | fresh `byte[]`             |
| `sliceBytes(long n)`            | zero-copy `MemorySegment`  |
| `getString(int n)`              | UTF-8 decoded `String`     |
| `readTierPayload(int tierCode)` | `long`                     |
| `readTierValue()`               | tier-code byte + payload   |

Bulk:

- `readLongArray(int)`, `readDoubleArray(int)`
- `readIntArray(int)`, `readFloatArray(int)`

Composite:

- `readEnvelope()` — checks magic + version, advances 5 bytes.
- `readAny()` — top-level dispatch, returns Clojure-friendly value.
- `skip(long n)` — advance cursor without reading.
- `internAdd(Object)`, `internGet(int)` — per-message sym-table.

Config setters (preserved across `reset()`):

- `setZeroCopy(boolean)`, `setTolerant(boolean)`,
  `setCacheIdents(boolean)`
- `setArrayMapThresholds(int nonKw, int kw)`
- `setExtensionHandler(ExtensionHandler)`
