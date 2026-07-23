# Extensions

hako's core wire format covers Clojure's built-in types. Everything
else — records, application-specific value types, third-party
serializers — plugs in via one of two registries.

- [Records](#records)
  - [Clojure defrecord](#clojure-defrecord)
  - [Java records](#java-records)
  - [How registration works](#how-registration-works)
- [User-tagged types](#user-tagged-types)
  - [Registration](#registration)
  - [Write callback contract](#write-callback-contract)
  - [Read callback contract](#read-callback-contract)
  - [Tolerant decode](#tolerant-decode)
  - [ID ranges](#id-ranges)
- [Metadata](#metadata)
- [Extension subtype registry](#extension-subtype-registry)

## Records

Both Clojure `defrecord`s and Java `record`s (JEP 395) are supported.
Both need explicit registration — hako will not encode an unknown
record class.

### Clojure defrecord

```clj
(require '[s-exp.hako :as hako]
         '[s-exp.hako.ext :as ext])

(defrecord Point [x y])

(ext/register-record! Point)

(hako/decode (hako/encode (->Point 3 4)))
;; => #user.Point{:x 3, :y 4}
```

The registration reflects on `Point` once — reads the basis field
order via `getBasis`, resolves the canonical positional constructor,
and caches a `MethodHandle` with all args adapted to `Object` (so
narrow-to-primitive coercions like `Long → int` work at the
invoke).

### Java records

```java
public record Point(int x, int y) {}
```

```clj
(import '(com.example Point))

(ext/register-record! Point)

(-> (Point. 3 4) hako/encode hako/decode .x)
;; => 3
```

Java records use `RecordComponent.getAccessor()` per field. hako
caches a `MethodHandle` per accessor at registration time, so the
write path invokes them directly with no reflection.

### How registration works

`register-record!` builds a `RecordInfo` (a Java `record` in
`com.s_exp.hako`) and stores it in the JVM-global
`RecordRegistry` (Java `ConcurrentHashMap<String, RecordInfo>` +
`ConcurrentHashMap<Class<?>, RecordInfo>`).

Encode path (in `Writer.writeRecord`):

1. `RecordRegistry.byClass(v.getClass())` — one hash lookup.
2. Emit tag `0xE3` (extension: record).
3. `writeInterned` the classname (dedups on repeat via sym-table).
4. Emit field count.
5. For Clojure defrecord: `IPersistentMap.valAt(kw)` per field
   keyword.
6. For Java record: `accessor MethodHandle.invokeWithArguments(v)`
   per field.

Decode path (in `Reader.readRecord`):

1. Read classname (symref-decoded via sym-table).
2. `RecordRegistry.byName(classname)`.
3. Verify field count matches.
4. Read N values, materialize an `Object[]`.
5. `ctorMH.invokeWithArguments(args)` — returns the record instance.

Registration is safe for concurrent access. Re-registering the same
class is idempotent (same class → same RecordInfo).

Failure modes:

- **Encode: unregistered class** →
  `IllegalStateException: hako: record class not registered: <name>`.
- **Decode: unregistered classname** →
  `IllegalStateException: hako: unknown record class: <name>`.
- **Field-count mismatch** — e.g. the class was re-defined with a
  different field set — throws with expected vs actual.

## User-tagged types

For value types that don't fit built-in types, don't extend
`IRecord`, or that you don't own (e.g. `java.net.URI`,
`java.time.LocalDate`), use user-tags.

### Registration

```clj
(import '(java.net URI))

(ext/register-user-tag!
 0x10000001                       ; u32 id — see ranges below
 URI                              ; class to dispatch on
 (fn write [w u]                  ; 2-arity: writer, value
   (.writeString w (str u)))
 (fn read [r]                     ; 1-arity: reader
   (let [tag (.getByte r)
         low (bit-and tag 0x0F)
         n (.readTierPayload r (int low))]
     (URI. (.getString r (int n))))))
```

Now `URI` instances encode as user-tag frames, and decode via the
registered read fn. The clj-kondo hook bundled in the jar validates
the write-fn / read-fn arities at edit time.

### Write callback contract

Signature: `(fn [^Writer w value])`.

- **Do not** emit the frame envelope (`0xEF` + tag id + length
  prefix). hako wraps your payload — you emit **only the payload
  bytes**.
- You may use any Writer method: `writeString`, `writeLong`,
  `putBytes`, container helpers, or recursively `.writeAny w
  child-value`.
- Payload size limit: 2^32 − 1 bytes (u32 length prefix).

### Read callback contract

Signature: `(fn [^Reader r])`.

- You start at the first byte of the payload. The framework has
  already consumed the `0xEF` tag byte, the u32 id, and the u32
  length prefix.
- You **must consume exactly `length` bytes** (matching what the
  writer emitted). hako verifies this and throws if you under/over
  read.
- You may call any Reader method or recursively `.readAny r`.

### Tolerant decode

If a message references a user-tag id that isn't registered in the
current JVM, `decode` throws by default. With `{:tolerate-unknown-tags true}`,
it returns a `TaggedValue`:

```clj
(hako/decode bs {:tolerate-unknown-tags true})
;; => #s_exp.hako.ext.TaggedValue{:ext 0x10000001
;;                                :bytes #<MemorySegment ...>}
```

`:ext` is the u32 id; `:bytes` is a `MemorySegment` slice of the raw
payload bytes. Use this when you might receive messages from
newer producers that carry types your consumer doesn't yet know
about.

Payload bytes stay valid until the source segment closes; see
[Arenas](arenas.md).

### ID ranges

Reserved space (from [../EXTENSIONS.md](../EXTENSIONS.md) §E.2):

| Range                       | Purpose                          |
|-----------------------------|----------------------------------|
| `0x00000000` – `0x0000FFFF` | Reserved for hako core.          |
| `0x00010000` – `0x0FFFFFFF` | Public third-party (PR to hako). |
| `0x10000000` – `0xFFFFFFFF` | Private / application-defined.   |

For internal application use, always pick from the private range.

## Metadata

Metadata is off by default because most workloads don't need it and
the wrapper costs a byte per `IObj`. Opt in:

```clj
(hako/encode (with-meta [1 2 3] {:tag :vec})
             {:preserve-meta true})
```

On the wire, this emits extension subtype `0xE4` (`with-meta`),
followed by the inner value and then the meta map. Decoders that
don't want metadata don't need a matching flag — the wrapper is
unconditional on the wire; the reader always reapplies
`with-meta` when it sees the tag.

## Extension subtype registry

Canonical byte-level definitions live in
[../EXTENSIONS.md](../EXTENSIONS.md). Key points from a user
perspective:

- Subtypes `0..8` are built-in (sorted-set, sorted-map, queue,
  record, with-meta, prim-longs, prim-doubles, prim-ints, prim-floats).
- Subtype `15` is the extensibility path — length-prefixed,
  safe to skip. Registered via `ext/register-user-tag!`.
- Unknown built-in low-nibble values (`9..14`) throw on strict
  decode. Those are spec bugs, not schema drift —
  `:tolerate-unknown-tags` does not apply.
