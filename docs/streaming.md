# Streaming & batch encoding

- [Model](#model)
- [`encode-many` / `decode-many`](#encode-many--decode-many)
- [Reusable Writer for high-throughput encode](#reusable-writer-for-high-throughput-encode)
- [Reusable Reader for high-throughput decode](#reusable-reader-for-high-throughput-decode)
- [Log-file patterns](#log-file-patterns)
- [RPC / socket patterns](#rpc--socket-patterns)
- [Sharing sym-tables across values](#sharing-sym-tables-across-values)
- [Choosing a strategy](#choosing-a-strategy)

## Model

hako does not stream one byte at a time. It streams **values into
segments**. There are four ways to write more than one value:

1. **Per-value encode** — `(hako/encode v)` per message, each with
   its own envelope and per-message symbol table. Simplest, most
   independent.
2. **Reusable Writer, per-message envelope** — `hako/writer` +
   `encode-into!`. Same envelope-per-message semantics as (1) but
   amortizes arena setup / teardown.
3. **Batch API** — `encode-many` / `decode-many`. Single envelope,
   many values, **one symbol table shared across the batch**.
4. **Explicit Writer + `writeAny`** — for advanced patterns
   (delimited streams, custom framing) — call the Java Writer
   directly.

## `encode-many` / `decoder` / `decode-many`

Best for medium-sized batches where the symbol-table dedup pays for
itself:

```clj
(require '[s-exp.hako :as hako])

(def batch
  [{:type :event :ts 1 :user "alice"}
   {:type :event :ts 2 :user "bob"}
   {:type :event :ts 3 :user "alice"}])

(def bytes (hako/encode-many batch))

(hako/decode-many bytes)
;; => the same three maps
```

Wire layout:

```
<magic HAKO><version 0>
<value 1>
<value 2>
...
<value N>
```

`decode-many` reads values until it hits end-of-input, returning a
vector.

### Transducer-friendly read: `hako/decoder`

For most streams you don't want to materialize a vector. Use
`(hako/decoder bs)` to get a **reducible + iterable** source over the
values. Composes with any `clojure.core` fn that consumes reducibles
or iterables:

```clj
(into #{} (filter :active) (hako/decoder bs))
(into [] (comp (map :id) (take 100)) (hako/decoder bs))
(sequence xform (hako/decoder bs))
(eduction  xform (hako/decoder bs))
(reduce f init (hako/decoder bs))
(transduce xform f init (hako/decoder bs))
(run! process! (hako/decoder bs))
```

Each reduction / iteration spins up a fresh Reader; the source is
safe to walk multiple times. Early termination via `reduced` or a
short `.hasNext` stops the reader immediately without over-reading.

`decode-many` is now just `(into [] (decoder bs))`.

**Options:**

`encode-many` accepts the same options as `encode`:
`:initial-size`, `:preserve-meta`, `:pack-homogeneous`,
`:coerce-custom-comparator`.

`decode-many` / `decoder` accept the same as `decode`:
`:zero-copy`, `:tolerate-unknown-tags`, `:cache-idents`.

**When to use:**

- ✅ Multi-value payloads written together — log-file batches,
  bulk uploads, tar-style archives, RPC framing where the
  application layer keeps the batch intact.
- ❌ Independently-addressable messages (queue frames where each
  message must be readable in isolation) — use per-message encoding
  instead.

**Size savings:**

For 100 identical maps `{:type :evt :user "alice"}` (3 kw + 1
string), the size comparison is roughly:

| Strategy         | Bytes |
|------------------|-------|
| 100 × `encode`   | ~2100 |
| `encode-many`    | ~440  |

Almost 5× smaller — every repeated keyword after the first message
compresses to a single-byte symref.

## Reusable Writer for high-throughput encode

Use when each message must stand alone (per-message envelope, no
shared sym-table) but you want to amortize the arena setup.

```clj
(with-open [wr (hako/writer 4096)]
  (dotimes [i N]
    (let [seg (hako/encode-into! wr {:i i :ts (System/currentTimeMillis)})]
      (transport-write! seg))))
```

Each call to `encode-into!`:

1. Resets the internal cursor to 0.
2. Emits a fresh envelope.
3. Encodes the value.
4. Returns a `MemorySegment` slice.

**Constraints:**

- The slice is valid **only until the next `encode-into!` call** on
  this writer. Consume it before iterating.
- The Writer is single-thread — the internal `Arena.ofConfined()`
  pins it to the creating thread.

**Copy patterns:**

If your transport needs a `byte[]`, either:

```clj
(let [seg (hako/encode-into! wr v)
      n (.byteSize seg)
      arr (byte-array n)]
  (java.lang.foreign.MemorySegment/copy
   seg java.lang.foreign.ValueLayout/JAVA_BYTE 0 arr 0 n)
  (transport-write! arr))
```

Or just use `(hako/encode v)` — same net cost.

## Reusable Reader for high-throughput decode

Mirror of the writer. Rebinds to a new source each call:

```clj
(let [rd (hako/reader (byte-array 0))]
  (doseq [frame frames]
    (let [v (hako/decode-into! rd frame)]
      (handle! v))))
```

The Reader's extension handler and array-map thresholds survive
resets — no re-configuration cost per message.

## Log-file patterns

### Write

Append `encode-many` batches to a growing file. Each batch is a
standalone hako stream — you can seek to any batch boundary and
`decode-many` from there.

```clj
(with-open [out (io/output-stream "events.hako" :append true)]
  (.write out (hako/encode-many batch)))
```

### Rotate + read

```clj
(defn read-log [path]
  (let [bs (with-open [in (io/input-stream path)]
             (.readAllBytes in))]
    (hako/decode-many bs)))
```

For batches too large to hold in memory, split the file into
fixed-size chunks at the application layer (each chunk is its own
hako stream) and process chunks lazily.

## RPC / socket patterns

hako's wire format has no built-in message framing (see
[../SPEC.md](../SPEC.md) §2 — "Message boundary = end of
root-value"). For sockets, wrap each hako message in your own
length-prefix frame:

```clj
(defn send-message! [^OutputStream out value]
  (let [bs (hako/encode value)]
    (.write out (int->4-byte-be (alength bs)))
    (.write out bs)))

(defn recv-message! [^InputStream in]
  (let [len (read-4-byte-be in)
        bs (.readNBytes in len)]
    (hako/decode bs)))
```

The per-message symbol table (envelope-per-message) means values
survive out-of-order delivery, drops, and reconnects independently.
Batch-style shared sym-tables (`encode-many`) do not survive
frame-level fragmentation — decode the whole batch or nothing.

## Sharing sym-tables across values

hako does **not** offer JVM-persistent global sym-tables (Nippy
does). The per-message model is intentional:

- Simpler wire format — no external synchronization.
- Safe for untrusted decode — no shared mutable state that a
  malformed message could poison.
- Predictable memory footprint — no unbounded per-JVM cache growth.

If you want dedup across many small messages, batch them into an
`encode-many` call.

If you want dedup across many *separately-emitted* messages, hako
does not currently support it. This is intentional; see
[../CHANGELOG.md](../CHANGELOG.md) for future direction.

## Choosing a strategy

| Situation                                     | Use                                              |
|-----------------------------------------------|---------------------------------------------------|
| One-off encode                                | `hako/encode`                                     |
| Many messages, each independent               | `hako/writer` + `encode-into!`                    |
| Batch of related messages, all read together  | `hako/encode-many` / `hako/decode-many`           |
| Native-interop output (FFM callee)            | `hako/encode-to-segment` with a caller arena       |
| Framed socket / queue                         | `hako/encode` + application-level length prefix   |
| Log file appended in batches                  | Concat `hako/encode-many` outputs                  |

## Related

- [Arenas & MemorySegment](arenas.md) — memory-lifetime model.
- [Performance](performance.md) — when each option pays off.
- [API reference](api-reference.md) — full function signatures.
