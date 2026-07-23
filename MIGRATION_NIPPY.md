# Migrating from Nippy to hako

Both libraries are Clojure binary serializers. Hako targets JDK 25+
FFM, has no dependencies beyond Clojure, and prioritizes a Java hot
path for encode/decode. Nippy is JDK 8+, mature, and has a broader
type registry.

## API mapping

| Nippy                            | hako equivalent                   |
|----------------------------------|------------------------------------|
| `nippy/freeze value`             | `hako/encode value`                |
| `nippy/thaw bytes`               | `hako/decode bytes`                |
| `nippy/fast-freeze value`        | `hako/encode value`                |
| `nippy/fast-thaw bytes`          | `hako/decode bytes`                |
| Thread-local buffer              | `hako/writer` + `encode-into!`     |
| `nippy/extend-freeze`            | `ext/register-user-tag!`           |
| `nippy/extend-thaw`              | (same call, `read-fn` argument)    |
| `*incl-metadata?* true`          | `{:meta? true}` encode opt         |
| `*freeze-fallback*`              | Not supported — throw on unknown   |
| `*thaw-serializable-allowlist*`  | Not supported — no `Serializable`  |

## Feature differences

| Feature                         | Nippy                     | hako                     |
|---------------------------------|---------------------------|---------------------------|
| JDK requirement                 | 8+                        | **25+ (FFM)**            |
| Wire format compatibility       | Backward compat within v3 | v0, unstable pre-release |
| Streaming API                   | Nippy has `freeze-to-out!`| `encode-many` (batch)    |
| Zero-copy read                  | No                        | Yes via `:zero-copy?`    |
| Custom-comparator sorted colls  | Preserves via `compare`   | Throws (opt-in coerce)   |
| Records                         | Automatic                 | Register per class       |
| Java records                    | Not supported             | **Supported**            |
| Java Serializable fallback      | Yes (opt-in)              | No                       |
| Encryption/compression          | Built-in                  | Out-of-scope             |
| Global keyword cache            | Always on                 | Opt-in `:cache-idents?`  |
| Symbol table                    | Global cache              | **Per-message**          |
| Metadata preservation           | Opt-in dynvar             | Opt-in `:meta?`          |

## Semantic differences to know

1. **Concrete map / set type not preserved.** Hako decodes based on
   size threshold — a Nippy-sourced `PersistentHashMap` may return as
   a `PersistentArrayMap` from hako and vice versa. `=` equality
   holds; use `IPersistentMap` in downstream code, not `instance?
   PersistentHashMap`.

2. **Custom-comparator sorted collections throw by default.** Nippy
   serializes the comparator's presence but restores with `compare`
   (silent loss). Hako fails loud by default. Opt in to Nippy-like
   behavior via `{:coerce-custom-comparator? true}`.

3. **Records must be registered.** Nippy discovers records via
   `IRecord`. Hako requires explicit `(ext/register-record! MyRecord)`
   at application startup — this cache is what makes the read path
   allocation-free.

4. **Per-message symbol table.** Nippy interns keywords/symbols
   globally per JVM. Hako interns per message. Both approaches trade
   off across scenarios:
   - Many small independent messages with heavy keyword reuse: Nippy
     is slightly better (no first-sighting overhead).
   - Long-lived process with many unique messages: hako's per-message
     table avoids unbounded growth.
   - For batch use (log files, RPC streams), use `hako/encode-many`
     to share the sym-table across values in a single blob.

5. **Metadata dropped by default.** Nippy's `*incl-metadata?*` is a
   dynvar; hako uses `{:meta? true}` per encode. Same effect,
   different threading.

6. **No wire compatibility.** Hako is v0 pre-release. Any existing
   Nippy-encoded data must be decoded with Nippy and re-encoded with
   hako.

## Sample migration

Before:

```clj
(require '[taoensso.nippy :as nippy])

(defn store! [k v]
  (redis/set k (nippy/freeze v)))

(defn load [k]
  (nippy/thaw (redis/get k)))

(nippy/extend-freeze URI :com.acme/uri
  [x out] (.writeUTF out (str x)))
(nippy/extend-thaw :com.acme/uri
  [in] (URI. (.readUTF in)))
```

After:

```clj
(require '[s-exp.hako :as hako]
         '[s-exp.hako.ext :as ext])

(defn store! [k v]
  (redis/set k (hako/encode v)))

(defn load [k]
  (hako/decode (redis/get k)))

(ext/register-user-tag!
 0x10000001                            ; pick an id in the private range
 URI
 (fn [w u] (.writeString w (str u)))
 (fn [r]
   (let [tag (.getByte r)
         low (bit-and tag 0x0F)
         n (.readTierPayload r (int low))]
     (URI. (.getString r (int n))))))
```

## Performance expectations

For workloads dominated by keyword-heavy maps and prim arrays, hako
is typically 1.5–3× faster on decode and 1.5–4× faster on encode.
For tiny strings (< 100 chars), hako trails Nippy by ~25 ns per
encode due to `MemorySegment` allocation overhead — negligible in
practice.

Reproduce local numbers via `clj -M:bench -m bench` (see README).
