# hako documentation

Deep-dive guides for hako users.

## Index

- [**Getting started**](getting-started.md) — install, first
  encode / decode, common patterns.
- [**API reference**](api-reference.md) — every public fn and Java
  method, with signatures and examples.
- [**Supported types**](types.md) — full list, wire representation
  hints, roundtrip contract.
- [**Extensions**](extensions.md) — records (Clojure + Java) and
  user-tagged types in depth.
- [**Arenas & MemorySegment**](arenas.md) — memory ownership,
  zero-copy reads, arena lifetimes, ScopedValue interop.
- [**Streaming & batch encoding**](streaming.md) — `encode-many`,
  `decode-many`, symbol-table sharing, log-file patterns.
- [**Performance & tuning**](performance.md) — option flags,
  reusable writer / reader, when to enable `:cache-idents`.
- [**Thread safety**](thread-safety.md) — per-instance contract,
  global state, cross-thread hand-off rules.

## Elsewhere

- [`../SPEC.md`](../SPEC.md) — byte-level wire specification.
- [`../EXTENSIONS.md`](../EXTENSIONS.md) — extension subtype
  registry.
- [`../WIRE_EXAMPLES.md`](../WIRE_EXAMPLES.md) — annotated
  byte-by-byte encoding examples.
- [`../MIGRATION_NIPPY.md`](../MIGRATION_NIPPY.md) — migrating from
  Nippy.
- [`../CHANGELOG.md`](../CHANGELOG.md) — release notes.
