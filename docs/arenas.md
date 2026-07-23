# Arenas & MemorySegment

hako is built on JDK 22+ FFM. Every encode writes into a
`MemorySegment`, every decode reads from one. This doc explains the
memory-ownership model, the arena types you interact with, and how
zero-copy reads are made safe.

- [Background](#background)
- [Arena types](#arena-types)
- [Encoder arenas](#encoder-arenas)
- [Decoder arenas](#decoder-arenas)
- [Zero-copy reads](#zero-copy-reads)
- [Cross-thread hand-off](#cross-thread-hand-off)
- [Lifetime pitfalls](#lifetime-pitfalls)

## Background

An `Arena` is a memory-lifetime scope. `MemorySegment`s allocated
inside an arena become invalid once the arena is closed — any
subsequent access throws `IllegalStateException`. The FFM API
enforces this at runtime; use-after-close cannot corrupt memory.

hako uses arenas in three roles:

1. **Encoder-owned** — hako creates an `Arena.ofConfined()` for the
   duration of a single `encode` or a reusable `writer`.
2. **Caller-owned** — you pass in an `Arena` and hako allocates the
   output inside it. Used by `encode-to-segment`.
3. **Decoder input** — the segment you feed to `decode` may come
   from any arena (or be heap-backed from `byte[]`).

## Arena types

| Arena kind | Factory | Lifetime | Access thread |
|---|---|---|---|
| Confined | `Arena/ofConfined` | Explicit `close()` (`AutoCloseable`) | Creating thread only |
| Shared | `Arena/ofShared` | Explicit `close()` | Any thread |
| Global | `Arena/global` | Process lifetime | Any thread |
| Auto | `Arena/ofAuto` | GC-driven | Any thread |

Confined arenas are the default hako uses internally — they're
cheapest and give the strongest safety net. Shared arenas are needed
if you want the encoded segment to survive being handed off between
threads.

## Encoder arenas

### `hako/encode` — hidden confined arena

```clj
(hako/encode value)
```

Under the hood:

1. `Arena.ofConfined()` opens.
2. `Writer` allocates the initial segment inside it.
3. On overflow, a larger segment is allocated (also in the arena;
   the old one becomes garbage but the arena still tracks it until
   close).
4. The encoded region is copied into a fresh `byte[]`.
5. Arena closes; all native memory reclaimed.

You never see the arena. Cost: one native allocation + copy per
encode. Trade-off: the API is byte-array-simple.

### `hako/encode-to-segment` — caller-owned arena

```clj
(with-open [arena (java.lang.foreign.Arena/ofConfined)]
  (let [seg (hako/encode-to-segment arena value)]
    ;; use `seg` while the arena is open
    ...))
```

You supply the arena. hako still uses a private confined arena
internally for the working buffer, but the returned segment is
allocated in **your** arena. When your arena closes, the returned
segment invalidates.

Use this when:

- You want to hand the encoded bytes to a native library (JNI/FFM
  callee) that expects a `MemorySegment`.
- You are batching many messages inside a longer-lived arena to
  avoid per-encode arena setup / teardown.

### `hako/writer` — reusable confined arena

```clj
(with-open [wr (hako/writer 4096)]
  ...)
```

The Writer opens one `Arena.ofConfined()` at construction and holds
it for the writer's lifetime. `encode-into!` reuses the segment: the
cursor resets to 0 on `.reset`, but the underlying buffer stays
allocated (and grows in-arena as needed).

Cost: single arena setup / teardown amortized over N messages.
Constraint: single-thread (confined arena).

## Decoder arenas

`hako/decode` accepts:

- `byte[]` — wrapped as a heap-backed `MemorySegment` via
  `MemorySegment/ofArray`. No arena involved on the input side.
- `MemorySegment` — used as-is.

The decoder never opens an arena. It only reads from the source.

Ownership of the source segment is entirely yours. hako will not
close it. If the segment came from a confined arena in another
thread, decode from another thread will throw
`WrongThreadException`.

## Zero-copy reads

With `{:zero-copy true}`, `bytes` payloads (major `0x3`) decode to
a `MemorySegment` slice of the source rather than a fresh `byte[]`:

```clj
(with-open [arena (java.lang.foreign.Arena/ofConfined)]
  (let [seg (hako/encode-to-segment arena {:blob (byte-array [1 2 3])})
        r   (hako/decode seg {:zero-copy true})]
    (:blob r)
    ;; => MemorySegment slice over `seg`, 3 bytes long
    ;; Reading it after `arena` closes throws.
    ))
```

Constraints:

- **The returned slice's lifetime = the source segment's lifetime.**
  Do not retain it past the arena's close.
- Zero-copy applies to byte payloads only. Strings still decode to
  `String` (UTF-8 decode is required); prim arrays still decode to
  typed Java arrays.

For long-lived slices, allocate the source in a shared or auto arena:

```clj
(let [arena (java.lang.foreign.Arena/ofShared)
      seg   (hako/encode-to-segment arena payload)
      r     (hako/decode seg {:zero-copy true})]
  ;; hand `r` to another thread — safe with shared arena
  ...
  ;; eventually
  (.close arena))
```

## Cross-thread hand-off

Confined arenas throw `WrongThreadException` if accessed from a
thread other than the one that opened them. This is a JVM-enforced
safety net, not a hako check.

**Encoder:** the internal Writer arena is confined. If you construct
`(hako/writer)` on thread A and pass the Writer to thread B, thread
B's `encode-into!` will throw. If you need cross-thread encoding:

- Use one Writer per thread, or
- Use `hako/encode` (per-call arena; each thread gets its own), or
- Pipe messages through a queue and encode on one thread.

**Decoder:** hako allocates no arena; the source arena's rules apply.
`byte[]` inputs are safe across threads. `Arena/ofShared`-backed
segments are safe. Confined segments are not.

## Lifetime pitfalls

- **Don't retain `encode-into!` slices past the next call.** The
  cursor resets and later writes overwrite the region. Copy out
  what you need first.
- **Don't retain `zero-copy?` slices past the source arena's
  close.** Read them, transform them, or copy to a `byte[]`.
- **Don't allocate a huge initial buffer for `hako/writer` unless
  you need it.** The default (4 KiB) doubles on overflow — allocating
  a segment for a payload you never write wastes native memory.
- **Don't call `.close` twice.** `AutoCloseable.close()` is
  idempotent on `Arena`, but hako's `Writer.close()` delegates
  directly — safer to use `with-open`.

## Related

- [Streaming & batch](streaming.md) for arena patterns that batch
  many messages inside one arena.
- [Thread safety](thread-safety.md) for the full concurrency model.
