# Thread safety

hako has three concurrency tiers.

- [1. Instance-per-thread state](#1-instance-per-thread-state)
- [2. Static caches — safe for concurrent decode](#2-static-caches--safe-for-concurrent-decode)
- [3. Registries — safe for concurrent read, serialized writes](#3-registries--safe-for-concurrent-read-serialized-writes)
- [Cross-thread hand-off](#cross-thread-hand-off)
- [Concurrent decode test coverage](#concurrent-decode-test-coverage)

## 1. Instance-per-thread state

`com.s_exp.hako.Writer` and `com.s_exp.hako.Reader` are **not
thread-safe**. Each instance is intended for use by one thread at
a time.

Writer additionally has a **JVM-enforced** constraint: its internal
`Arena.ofConfined()` throws `WrongThreadException` from the FFM
layer if accessed from a thread other than the one that constructed
it. Bug-proof at runtime.

Reader's thread constraint depends on the source `MemorySegment`:

- Segment from `byte[]` (via `MemorySegment.ofArray`) — heap-backed,
  thread-safe to read from any thread.
- Segment from `Arena.ofConfined()` — pinned to that thread.
- Segment from `Arena.ofShared()` or `Arena.global()` — safe from
  any thread.
- Segment from `Arena.ofAuto()` — safe from any thread, but survives
  only until GC decides otherwise.

## 2. Static caches — safe for concurrent decode

Reader's ident cache (used when `:cache-idents true`) lives in two
static fields:

```java
private static final ConcurrentHashMap<String, Keyword> KW_CACHE = new ConcurrentHashMap<>();
private static final ConcurrentHashMap<String, Symbol>  SYM_CACHE = new ConcurrentHashMap<>();
```

Reads via `.get`; writes via `putIfAbsent` (returns existing value
on race so hako uses that). All threads see the same interned
`Keyword` / `Symbol` instances — `identical?` holds across
concurrent decode.

**Verified** in `test/s_exp/hako/concurrency_test.clj`:

```clj
(deftest cache-concurrent-decode
  (testing "concurrent decode with :cache-idents produces equal + interned keywords"
    ...))
```

## 3. Registries — safe for concurrent read, serialized writes

### `RecordRegistry` (Java, static)

```java
private static final ConcurrentHashMap<String, RecordInfo> BY_NAME = ...;
private static final ConcurrentHashMap<Class<?>, RecordInfo> BY_CLASS = ...;
```

`put` is unconditional but idempotent — re-registering the same
class produces the same `RecordInfo` (derived deterministically from
the class). `byName` / `byClass` are lock-free reads.

Registration is expected at application startup, but is safe to run
concurrently on multiple threads.

### `user-tag-registry` (Clojure atom)

`swap!` serializes registrations; deref-reads race with writes safely
(atoms provide happens-before ordering).

### `warned-custom-cmp?` (Clojure atom)

`compare-and-set!` guarantees the coercion warning fires at most
once per JVM, regardless of concurrent encoders.

## Cross-thread hand-off

### Value hand-off — always safe

Encoded `byte[]`s are pure data; hand them off freely. Decoded
values are immutable Clojure data structures (or Java records / user
values); also safe.

### Segment hand-off — arena-dependent

If you produce a `MemorySegment` on thread A via
`hako/encode-to-segment`, and hand it to thread B:

- Segment allocated in `Arena.ofConfined()`: thread B access throws
  `WrongThreadException`.
- Segment allocated in `Arena.ofShared()`: thread B may read safely.
- Segment allocated in `Arena.global()`: thread B may read safely.

Use `Arena/ofShared` when you know cross-thread hand-off is needed:

```clj
(let [arena (java.lang.foreign.Arena/ofShared)]
  (try
    (let [seg (hako/encode-to-segment arena payload)]
      (send-to-worker-thread! seg))
    (finally
      ;; Close only after all consumers have finished with the segment.
      ;; Arena.ofShared() blocks close() until concurrent readers exit.
      (.close arena))))
```

### Writer / Reader hand-off — avoid

Passing a `Writer` or `Reader` instance across threads is not
supported. The Writer's confined arena will refuse cross-thread
access anyway; the Reader's mutable cursor makes concurrent use
unsafe even with a shared source segment.

Use one instance per thread, or serialize access via a queue.

### Publication

When a Writer / Reader is *published* to another thread via a
proper synchronization primitive (e.g. `java.util.concurrent`
collections, `volatile` fields, `Thread.start`), the JVM guarantees
constructor writes and subsequent config-setter writes are visible.
hako does not use `volatile` on its config fields because the
instance-per-thread contract makes it moot — but if you find
yourself needing cross-thread publication + first-use, the
`java.util.concurrent` publication guarantees are sufficient.

## Concurrent decode test coverage

`test/s_exp/hako/concurrency_test.clj` runs three properties on 8
threads:

1. **cache-concurrent-decode** — many threads decode the same
   `byte[]`; assert all resulting keywords are `identical?` to the
   canonical interned Keyword.
2. **cache-race-first-write** — many threads race to first-see 100
   unique keywords; every thread must succeed.
3. **cache-off-baseline** — same but with `:cache-idents false`,
   proving the default path is thread-safe too.

If you extend hako with a stateful decode helper, add a similar
concurrent test.
