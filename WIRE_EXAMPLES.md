# Hako wire-format examples

Concrete byte-level worked examples for common cases. Complements
`SPEC.md` (byte-level definition) and `EXTENSIONS.md` (extension
registry).

Notation:
- `HH` — a hex byte
- `[ ... ]` — an optional payload

Every message begins with the envelope:

```
48 41 4B 4F 00
'H''A''K''O' ver=0
```

Skip the envelope in the examples below; each example shows only the
`root-value` bytes.

## 1. Small integer — inline tier

`(encode 5)` → uint 5.

```
05
├─ major=0x0 (uint), low nibble=5 (inline value)
```

## 2. Medium integer — u8 tier

`(encode 200)` → uint 200.

```
0C C8
├─ 0C  = major=0x0, low=0x0C (TIER_U8)
└─ C8  = payload byte, value 200
```

## 3. Negative integer — sint / u8 tier

`(encode -50)` → sint (zig-zag encoded).

Zig-zag: `(-50 << 1) ^ (-50 >> 63)` = 99 = `0x63`.

```
1C 63
├─ 1C  = major=0x1 (sint), low=0x0C (TIER_U8)
└─ 63  = payload = 99 (decodes to -50 via zig-zag)
```

## 4. Short string

`(encode "hi")` → M_STRING (major=0x4) with length 2 (inline).

```
42 68 69
├─ 42       = major=0x4 (string), low=0x02 (inline length = 2)
├─ 68 ('h')
└─ 69 ('i')
```

## 5. Keyword (first occurrence)

`(encode :name)` → M_KW (major=0x5).

Payload structure: `<size-tier length> <ns-length:u8> <ns bytes> <name bytes>`.
For `:name`: no ns → ns-length=0, name-bytes=`name` (4 bytes). Total
payload length = 1 (ns-length byte) + 0 (ns) + 4 (name) = 5.

```
55 00 6E 61 6D 65
├─ 55         = major=0x5 (kw), low=0x05 (inline length = 5)
├─ 00         = ns-length = 0
└─ 6E 61 6D 65 = 'n' 'a' 'm' 'e'
```

Symbol-table state: `{:name → 0}`.

## 6. Same keyword again — symref

Second write of `:name` in the same message emits a symref to
index 0.

```
C0
├─ C0 = major=0xC (symref), low=0x00 (inline index = 0)
```

## 7. Vector `[1 2 3]`

Vector major 0x7, inline count 3, then three inline uints.

```
73 01 02 03
├─ 73 = major=0x7 (vector), low=0x03 (inline count = 3)
├─ 01 = uint 1
├─ 02 = uint 2
└─ 03 = uint 3
```

## 8. Map `{:a 1}`

Map major 0xA, inline count 1, then key/value pair.

```
A1 51 00 61 01
├─ A1        = major=0xA (map), low=0x01 (inline pair count = 1)
├─ 51 00 61  = keyword :a (first occurrence — inline)
│   ├─ 51    = major=0x5 (kw), low=0x01 (inline length = 1)
│   ├─ 00    = ns-length = 0
│   └─ 61    = 'a'
└─ 01        = uint 1
```

Symbol-table state after: `{:a → 0}`.

## 9. Map with repeated keyword `{:a 1 :b 2 :a 3}` (illegal but illustrates)

Skip — Clojure prevents duplicate keys in a literal map. Here's a
concrete two-map case:

`(encode [{:a 1} {:a 2}])`:

```
72 A1 51 00 61 01 A1 C0 02
├─ 72        = vector inline count 2
├─ A1        = map inline count 1  (first map)
├─ 51 00 61  = keyword :a inline (first sighting)
├─ 01        = uint 1
├─ A1        = map inline count 1  (second map)
├─ C0        = symref to :a (index 0)
└─ 02        = uint 2
```

Notice the second `:a` compresses from 5 bytes to 1 byte.

## 10. UUID

`(encode #uuid "12345678-9abc-def0-1234-56789abcdef0")` → special
subtype 6.

```
F6 F0 DE BC 9A 78 56 34 12 F0 DE BC 9A 78 56 34 12
├─ F6                       = major=0xF (special), low=0x06 (UUID)
├─ F0 DE BC 9A 78 56 34 12  = MSB u64 LE (0x123456789ABCDEF0)
└─ F0 DE BC 9A 78 56 34 12  = LSB u64 LE (0x123456789ABCDEF0)
```

## 11. Packed `long[]` — extension prim-longs

`(encode (long-array [1 2 3]))`:

```
E5 03 01 00 ...  02 00 ...  03 00 ...
├─ E5           = major=0xE (extension), low=0x05 (EXT_PRIM_LONGS)
├─ 03           = tier-value: inline count = 3
└─ 3 × 8 bytes  = each i64 LE
```

## 12. User-tag frame

Registration:

```clj
(ext/register-user-tag! 0x10000001 URI
  (fn write [w u] (.writeString w (str u)))
  (fn read  [r] ...))
```

Encoding a `URI` produces:

```
EF <u32 id LE> 0E <u32 payload-length LE> <payload bytes>
├─ EF        = major=0xE (ext), low=0x0F (user-tag)
├─ u32       = 0x10000001 (little-endian: 01 00 00 10)
├─ 0E        = tier code = TIER_U32 (always u32 for user-tag)
├─ u32       = payload length in bytes (little-endian)
└─ payload   = whatever the user's write-fn emitted
```

A `:tolerant? true` decoder can skip an unknown tag by advancing
`payload-length` bytes past the length prefix — no need to
understand the payload contents.

## 13. Size-tier cheat table

| Low nibble | Encoding                          | Range               |
|-----------:|-----------------------------------|---------------------|
| 0..11      | inline (nibble is the value)       | 0..11               |
| 12 (0x0C)  | next 1 byte is the value           | 12..255             |
| 13 (0x0D)  | next 2 bytes u16 LE                | 256..65 535         |
| 14 (0x0E)  | next 4 bytes u32 LE                | 65 536..4 294 967 295 |
| 15 (0x0F)  | next 8 bytes u64 LE                | up to 2^63 − 1      |

Same table applies to every size-tiered major (uint, sint, bytes,
string, keyword, symbol, vector, list, set, map, symref,
bignumeric byte-count, extension counts).
