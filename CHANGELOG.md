# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] — 2026-07-23

Initial pre-release.

### Added

- Schemaless binary wire format (see `SPEC.md`) — 5-byte envelope
  `<magic 'HAKO'><version 0>`, 16 major-type nibbles, fixed-width
  size tiers (inline, u8, u16, u32, u64).
- Public Clojure API (`s-exp.hako` namespace): `encode`,
  `encode-to-segment`, `encode-into!`, `decode`, `decode-into!`,
  `encode-many`, `decode-many`, `writer`, `reader`.
- JDK 25 FFM (`MemorySegment`) hot path in `com.s_exp.hako.Writer` /
  `Reader` with a Java `instanceof` dispatch chain.
- Extension registry (`s-exp.hako.ext`) — `register-record!`,
  `register-user-tag!`, `TaggedValue`.
- Supported types: `nil`, `boolean`, `Character`, `Long`, `Integer`,
  `Short`, `Byte`, `Double`, `Float`, `String`, `byte[]`, `long[]`,
  `double[]`, `int[]`, `float[]`, `UUID`, `java.time.Instant`,
  `BigInteger`, `BigDecimal`, `clojure.lang.BigInt`, `Ratio`,
  `Keyword`, `Symbol`, `PersistentVector`, `PersistentList`,
  `PersistentHashSet`, `PersistentHashMap`, `PersistentArrayMap`,
  `PersistentTreeSet`, `PersistentTreeMap`, `PersistentQueue`,
  Clojure defrecords, Java records (JEP 395).
- Encode options: `:initial-size`, `:meta?`, `:pack-homogeneous?`,
  `:coerce-custom-comparator?`.
- Decode options: `:zero-copy?`, `:tolerant?`, `:cache-idents?`.
- Per-message symbol table with symref dedup for keywords, symbols,
  and record classnames — Object-keyed (`HashMap<Object, Long>`) for
  alloc-free lookups.
- Length-prefixed user-tag frames (`0xEF`) for forward-compat and
  `:tolerant?` skipping.
- Adaptive `PersistentArrayMap` decode fast path — probes the running
  Clojure runtime's threshold at ns-load and picks PAM or PHM.
- `encode-many` / `decode-many` for batch payloads sharing one
  symbol table across values.
- Extensions defined: `sorted-set`, `sorted-map`, `queue`, `record`,
  `with-meta`, `prim-longs`, `prim-doubles`, `prim-ints`,
  `prim-floats`, `user-tag`.
- `Writer.ensure()` capacity overflow guard (rejects at MAX_CAP = 2^62).
- Count-overflow guards on decode to prevent silent `int` truncation
  for `u64`-tier length / count values.

### Documentation

- `SPEC.md`, `EXTENSIONS.md`, `README.md`, `LICENSE`.

### Benchmarks

Criterium quick-bench vs Nippy and Deed:

- `nested-map` (50 keyword keys): encode **6.61 µs** (2.4× Nippy),
  decode **7.90 µs** (2.8× Nippy).
- Prim arrays (`long[]`/`double[]` × 1000): 3–4× faster encode/decode.
- Wins every measured payload except `string-100` encode (24 ns
  dispatch overhead vs Nippy on tiny strings).

[Unreleased]: https://github.com/s-exp/hako/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/s-exp/hako/releases/tag/v0.1.0
