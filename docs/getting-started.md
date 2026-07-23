# Getting started

## Install

```clj
;; deps.edn
{:deps {org.clojure/clojure {:mvn/version "1.12.0"}
        com.s-exp/hako     {:mvn/version "0.1.0"}}
 :aliases {:run {:jvm-opts ["--enable-native-access=ALL-UNNAMED"]}}}
```

hako needs JDK **25+** for the FFM `MemorySegment` API. If you consume
the published jar, its manifest includes
`Enable-Native-Access: ALL-UNNAMED` so applications don't see the
FFM restricted-method warning. When developing locally, add the flag
to your `:jvm-opts` (see the `:run` alias above).

## First encode / decode

```clj
(require '[s-exp.hako :as hako])

(def payload {:name "Alice" :tags #{:a :b :c} :score 42})

(def bs (hako/encode payload))
;; => byte[] of length 37

(hako/decode bs)
;; => {:name "Alice", :tags #{:a :b :c}, :score 42}
```

## What just happened

- `encode` allocated a confined `Arena`, wrote a 5-byte envelope
  (`H A K O 0x00`), dispatched the map / keywords / string / long to
  the Java `writeAny` path, and returned a plain `byte[]` sized to
  the actual output.
- `decode` wrapped the byte array as a heap-backed `MemorySegment`,
  read the envelope, and reversed the process.

No shared state was created; every hako operation defaults to
per-call arena management with automatic cleanup.

## Common patterns

### One-shot encode → byte[]

```clj
(hako/encode value)
```

Default. Allocates a per-call arena, closes it, returns a fresh
`byte[]`. Ideal for RPC responses, cache values, and anywhere you
already have byte-array-shaped I/O.

### Roundtrip

```clj
(-> value hako/encode hako/decode)
;; = value  (semantic equality, `=`)
```

### High-throughput encode loop

```clj
(with-open [wr (hako/writer 4096)]
  (dotimes [i 100000]
    (let [seg (hako/encode-into! wr {:i i :tag :event})]
      (write-to-socket! seg))))
```

`hako/writer` opens an `Arena.ofConfined()` once. `encode-into!`
reuses the same segment — the returned `MemorySegment` slice is
overwritten on the next call. Consume it before the next iteration.

See [Arenas & MemorySegment](arenas.md) for the ownership model, and
[Performance](performance.md) for tuning.

### Multiple values with shared symbol table

Log files, batched writes, and RPC frames often repeat the same
keyword keys across many values. Emit them once via `encode-many`:

```clj
(def messages [{:type :evt :id 1}
               {:type :evt :id 2}
               {:type :evt :id 3}])

(hako/encode-many messages)
;; keywords :type / :evt / :id encoded on the first message,
;; symref'd (1 byte each) in the remaining messages.
```

See [Streaming & batch](streaming.md) for details.

### Zero-copy decode

If your source is already `MemorySegment`-backed (mmap, native
buffer), avoid the byte[] copy for `bytes` payloads:

```clj
(hako/decode segment {:zero-copy true})
```

Byte payloads come back as `MemorySegment` slices over the source
segment. See [Arenas & MemorySegment](arenas.md).

## Next steps

- Register your record types: [Extensions §Records](extensions.md#records).
- Custom types via user-tags: [Extensions §User tags](extensions.md#user-tagged-types).
- Understand the wire format: [../SPEC.md](../SPEC.md) and
  [../WIRE_EXAMPLES.md](../WIRE_EXAMPLES.md).
