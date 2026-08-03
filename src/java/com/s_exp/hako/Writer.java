package com.s_exp.hako;

import clojure.lang.AFn;
import clojure.lang.BigInt;
import clojure.lang.Counted;
import clojure.lang.IFn;
import clojure.lang.IKVReduce;
import clojure.lang.IObj;
import clojure.lang.IReduce;
import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentSet;
import clojure.lang.IPersistentVector;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.PersistentQueue;
import clojure.lang.PersistentTreeMap;
import clojure.lang.PersistentTreeSet;
import clojure.lang.Ratio;
import clojure.lang.Symbol;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable hako-format encoder. Owns an internal {@code Arena.ofConfined()}
 * and grows the backing {@link MemorySegment} by doubling on overflow.
 *
 * <p>NOT thread-safe. The confined Arena pins the instance to its
 * creating thread — any attempt to use a Writer from another thread
 * throws {@code WrongThreadException} from the FFM layer.
 *
 * <p>Reusable across many messages via {@link #reset()}. The registered
 * {@link UnknownHandler} and configuration fields survive resets.
 */
public final class Writer implements AutoCloseable {

    private static final byte[] EMPTY = new byte[0];

    private Arena arena;
    private MemorySegment seg;
    private long pos;
    private long cap;
    // Per-message symbol table — open-addressing hash on primitive
    // arrays. `symKeys` holds Keyword/Symbol/String instances; `symIdxs`
    // holds their assigned symref indices. Allocated once at
    // construction and reused across `reset()`, so a warmed Writer
    // does zero allocation on repeat encodes of the same shape.
    // Replaces the previous `HashMap<Object, Long>` which allocated
    // ~72 B per unique intern (HashMap$Node + autoboxed Long).
    private Object[] symKeys = new Object[8];
    private long[] symIdxs = new long[8];
    private int symMask = 7;
    private int symSize = 0;
    private long nextSymIdx = 0;

    private long symTableGet(Object key) {
        int i = key.hashCode() & symMask;
        while (true) {
            Object k = symKeys[i];
            if (k == null) return -1L;
            if (k == key || k.equals(key)) return symIdxs[i];
            i = (i + 1) & symMask;
        }
    }

    private void symTablePut(Object key, long idx) {
        if ((symSize + 1) << 2 > (symKeys.length * 3)) {
            int newCap = symKeys.length << 1;
            Object[] oldKeys = symKeys;
            long[] oldIdxs = symIdxs;
            symKeys = new Object[newCap];
            symIdxs = new long[newCap];
            symMask = newCap - 1;
            symSize = 0;
            for (int j = 0; j < oldKeys.length; j++) {
                Object k = oldKeys[j];
                if (k != null) symTablePutRaw(k, oldIdxs[j]);
            }
        }
        symTablePutRaw(key, idx);
    }

    private void symTablePutRaw(Object key, long idx) {
        int i = key.hashCode() & symMask;
        while (symKeys[i] != null) i = (i + 1) & symMask;
        symKeys[i] = key;
        symIdxs[i] = idx;
        symSize++;
    }
    private boolean writeMeta = false;
    private boolean packHomogeneous = false;
    private boolean coerceCustomComparator = false;

    public Writer(long initialSize) {
        if (initialSize < 1) initialSize = 64;
        this.arena = Arena.ofConfined();
        this.seg = arena.allocate(initialSize, 1);
        this.cap = initialSize;
        this.pos = 0;
    }

    public long pos() { return pos; }

    public long cap() { return cap; }

    public boolean writeMeta() { return writeMeta; }

    public void setWriteMeta(boolean b) { this.writeMeta = b; }

    public boolean packHomogeneous() { return packHomogeneous; }

    public void setPackHomogeneous(boolean b) { this.packHomogeneous = b; }

    public boolean coerceCustomComparator() { return coerceCustomComparator; }

    public void setCoerceCustomComparator(boolean b) { this.coerceCustomComparator = b; }

    /**
     * Handler invoked for values that don't match any built-in
     * dispatch — records, custom user-tag types, or unknown classes.
     * Set by the Clojure layer.
     */
    public interface UnknownHandler {
        void write(Writer w, Object v);
    }

    private UnknownHandler unknownHandler;

    public void setUnknownHandler(UnknownHandler h) { this.unknownHandler = h; }

    public MemorySegment finish() {
        return seg.asSlice(0, pos);
    }

    /**
     * Zero-alloc copy of the encoded region into `dst[off..]`. Returns
     * the byte count written. Skips the {@code asSlice} wrapper that
     * {@link #finish()} allocates — hand-off for callers who own their
     * output buffer.
     */
    public int copyTo(byte[] dst, int off) {
        int n = (int) pos;
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0, dst, off, n);
        return n;
    }

    /**
     * Zero-alloc copy of the encoded region into `dst`. Advances
     * {@code dst.position()} by the byte count written; returns that
     * count. Heap-backed ByteBuffers take a direct byte[] copy; direct
     * ByteBuffers copy via a {@code MemorySegment.ofBuffer} wrapper.
     */
    public int copyTo(ByteBuffer dst) {
        int n = (int) pos;
        int dstPos = dst.position();
        if (dst.hasArray()) {
            MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0,
                               dst.array(), dst.arrayOffset() + dstPos, n);
        } else {
            MemorySegment.copy(seg, 0, MemorySegment.ofBuffer(dst), dstPos, n);
        }
        dst.position(dstPos + n);
        return n;
    }

    /**
     * Reset the writer for reuse. Cursor is set to 0, sym-table is
     * cleared, and per-message options are restored to defaults. The
     * underlying buffer and arena are kept.
     *
     * The MemorySegment returned by the previous finish() call becomes
     * a view into memory that is about to be overwritten — callers must
     * consume it before calling reset().
     */
    public void reset() {
        pos = 0;
        if (symSize > 0) {
            Arrays.fill(symKeys, null);
            symSize = 0;
        }
        nextSymIdx = 0;
        writeMeta = false;
        packHomogeneous = false;
        coerceCustomComparator = false;
        // Preserve unknownHandler across reset — it's a one-time setup.
    }

    @Override
    public void close() {
        arena.close();
    }

    private static final long MAX_CAP = 1L << 62;

    private void ensure(long n) {
        if (n > cap - pos || n < 0) grow(n);
    }

    private void grow(long n) {
        if (n < 0 || n > MAX_CAP - pos) {
            throw new IllegalStateException(
                "hako: write exceeds max buffer capacity (" + MAX_CAP + " bytes)");
        }
        long need = pos + n;
        long newCap = cap;
        while (newCap < need) newCap <<= 1;
        // A confined Arena only releases memory on close() — allocating
        // the new buffer in the same arena would retain every prior
        // generation until the Writer is closed (~2x final size pinned
        // for the lifetime of a pooled Writer). Each buffer generation
        // gets its own arena; the old one is closed after the copy.
        Arena newArena = Arena.ofConfined();
        MemorySegment newSeg = newArena.allocate(newCap, 1);
        MemorySegment.copy(seg, 0L, newSeg, 0L, pos);
        arena.close();
        arena = newArena;
        seg = newSeg;
        cap = newCap;
    }

    /**
     * Test-only hook that exercises the ensure() overflow guard.
     * Not intended for production callers.
     */
    public void ensureForTesting(long n) { ensure(n); }

    public void putByte(int b) {
        ensure(1);
        seg.set(ValueLayout.JAVA_BYTE, pos, (byte) b);
        pos += 1;
    }

    public void putU16(int v) {
        ensure(2);
        seg.set(Format.LE_SHORT, pos, (short) v);
        pos += 2;
    }

    public void putU32(long v) {
        ensure(4);
        seg.set(Format.LE_INT, pos, (int) v);
        pos += 4;
    }

    public void putI32(int v) {
        ensure(4);
        seg.set(Format.LE_INT, pos, v);
        pos += 4;
    }

    public void putU64(long v) {
        ensure(8);
        seg.set(Format.LE_LONG, pos, v);
        pos += 8;
    }

    /** Same wire layout as {@link #putU64}; distinguishes signed callers. */
    public void putI64(long v) {
        ensure(8);
        seg.set(Format.LE_LONG, pos, v);
        pos += 8;
    }

    public void putF32(float v) {
        ensure(4);
        seg.set(Format.LE_FLOAT, pos, v);
        pos += 4;
    }

    public void putF64(double v) {
        ensure(8);
        seg.set(Format.LE_DOUBLE, pos, v);
        pos += 8;
    }

    public void putBytes(byte[] bs) {
        putBytes(bs, 0, bs.length);
    }

    public void putBytes(byte[] bs, int off, int len) {
        ensure(len);
        MemorySegment.copy(bs, off, seg, ValueLayout.JAVA_BYTE, pos, len);
        pos += len;
    }

    /** Emit tag byte with a size-tier'd length/count prefix. Returns tier code. */
    public int putSizedTag(int major, long n) {
        int code = Format.tierCode(n);
        putByte(Format.tag(major, code));
        putTierPayload(code, n);
        return code;
    }

    /**
     * Emit a raw size-tier value (tier code byte + optional payload),
     * without any major-type tag prefix. Used inside composite payloads
     * such as bignumeric byte-counts.
     */
    public void putTierValue(long n) {
        int code = Format.tierCode(n);
        putByte(code);
        putTierPayload(code, n);
    }

    private void putTierPayload(int code, long n) {
        switch (code) {
            case Format.TIER_U8: putByte((int) n); break;
            case Format.TIER_U16: putU16((int) n); break;
            case Format.TIER_U32: putU32(n); break;
            case Format.TIER_U64: putU64(n); break;
            default: break;
        }
    }

    /** Emit an extension frame header: 0xE0 tag byte + u8 subtype. */
    public void putExtTag(int subtype) {
        ensure(2);
        seg.set(ValueLayout.JAVA_BYTE, pos, (byte) Format.M_EXT);
        seg.set(ValueLayout.JAVA_BYTE, pos + 1, (byte) subtype);
        pos += 2;
    }

    public void writeEnvelope() {
        putByte(Format.MAGIC_0);
        putByte(Format.MAGIC_1);
        putByte(Format.MAGIC_2);
        putByte(Format.MAGIC_3);
        putByte(Format.VERSION);
    }

    public void writeNil() {
        putByte(Format.tag(Format.M_SPEC, Format.SPEC_NIL));
    }

    public void writeTrue() {
        putByte(Format.tag(Format.M_SPEC, Format.SPEC_TRUE));
    }

    public void writeFalse() {
        putByte(Format.tag(Format.M_SPEC, Format.SPEC_FALSE));
    }

    // Scalar emitters fuse tag byte + payload into a single ensure()
    // and positional writes — one bounds check per value instead of
    // one per put call. Benched -4..-7% on numeric payloads (AC power;
    // battery throttling produces misleading numbers on this path).

    public void writeLong(long n) {
        int major;
        long u;
        if (n >= 0) {
            major = Format.M_UINT;
            u = n;
        } else {
            major = Format.M_SINT;
            u = Format.zigZagEncode(n);
        }
        int code = Format.tierCode(u);
        switch (code) {
            case Format.TIER_U8:
                ensure(2);
                seg.set(ValueLayout.JAVA_BYTE, pos, (byte) Format.tag(major, code));
                seg.set(ValueLayout.JAVA_BYTE, pos + 1, (byte) u);
                pos += 2;
                break;
            case Format.TIER_U16:
                ensure(3);
                seg.set(ValueLayout.JAVA_BYTE, pos, (byte) Format.tag(major, code));
                seg.set(Format.LE_SHORT, pos + 1, (short) u);
                pos += 3;
                break;
            case Format.TIER_U32:
                ensure(5);
                seg.set(ValueLayout.JAVA_BYTE, pos, (byte) Format.tag(major, code));
                seg.set(Format.LE_INT, pos + 1, (int) u);
                pos += 5;
                break;
            case Format.TIER_U64:
                ensure(9);
                seg.set(ValueLayout.JAVA_BYTE, pos, (byte) Format.tag(major, code));
                seg.set(Format.LE_LONG, pos + 1, u);
                pos += 9;
                break;
            default:  // inline tier, tag byte only
                putByte(Format.tag(major, code));
                break;
        }
    }

    public void writeDouble(double d) {
        if (Double.isNaN(d)) {
            putByte(Format.tag(Format.M_SPEC, Format.SPEC_NAN));
            return;
        }
        if (d == Double.POSITIVE_INFINITY) {
            putByte(Format.tag(Format.M_SPEC, Format.SPEC_PINF));
            return;
        }
        if (d == Double.NEGATIVE_INFINITY) {
            putByte(Format.tag(Format.M_SPEC, Format.SPEC_NINF));
            return;
        }
        ensure(9);
        seg.set(ValueLayout.JAVA_BYTE, pos, (byte) Format.tag(Format.M_FLOAT, Format.FLOAT_F64));
        seg.set(Format.LE_DOUBLE, pos + 1, d);
        pos += 9;
    }

    public void writeFloat(float f) {
        ensure(5);
        seg.set(ValueLayout.JAVA_BYTE, pos, (byte) Format.tag(Format.M_FLOAT, Format.FLOAT_F32));
        seg.set(Format.LE_FLOAT, pos + 1, f);
        pos += 5;
    }

    public void writeString(String s) {
        // Note: a zero-alloc scratch-buffer encode (per-char ASCII copy)
        // was benched at 3-8x slower than getBytes — the JDK intrinsifies
        // UTF-8 encoding of compact strings into a vectorized copy that
        // no userland char loop can match. The transient byte[] is the
        // cheaper trade.
        byte[] bs = s.getBytes(StandardCharsets.UTF_8);
        putSizedTag(Format.M_STRING, bs.length);
        putBytes(bs);
    }

    public void writeBytes(byte[] bs) {
        putSizedTag(Format.M_BYTES, bs.length);
        putBytes(bs);
    }

    public void writeUuid(long msb, long lsb) {
        ensure(17);
        seg.set(ValueLayout.JAVA_BYTE, pos, (byte) Format.tag(Format.M_SPEC, Format.SPEC_UUID));
        seg.set(Format.LE_LONG, pos + 1, msb);
        seg.set(Format.LE_LONG, pos + 9, lsb);
        pos += 17;
    }

    public void writeInstant(long epochSec, int nano) {
        ensure(13);
        seg.set(ValueLayout.JAVA_BYTE, pos, (byte) Format.tag(Format.M_SPEC, Format.SPEC_INST));
        seg.set(Format.LE_LONG, pos + 1, epochSec);
        seg.set(Format.LE_INT, pos + 9, nano);
        pos += 13;
    }

    public void writeChar(int codeUnit) {
        ensure(3);
        seg.set(ValueLayout.JAVA_BYTE, pos, (byte) Format.tag(Format.M_SPEC, Format.SPEC_CHAR));
        seg.set(Format.LE_SHORT, pos + 1, (short) codeUnit);
        pos += 3;
    }

    public void writeDate(long epochMillis) {
        ensure(9);
        seg.set(ValueLayout.JAVA_BYTE, pos, (byte) Format.tag(Format.M_SPEC, Format.SPEC_DATE));
        seg.set(Format.LE_LONG, pos + 1, epochMillis);
        pos += 9;
    }

    public void writeLongArray(long[] arr) {
        int n = arr.length;
        putExtTag(Format.EXT_PRIM_LONGS);
        putTierValue(n);
        long bytes = (long) n * 8L;
        ensure(bytes);
        MemorySegment.copy(arr, 0, seg, Format.LE_LONG, pos, n);
        pos += bytes;
    }

    /**
     * Emit the user-tag header for tag id `tagId` and return an offset
     * that must be passed to `endUserTag` after payload bytes have been
     * written. Between the two calls, callers write the payload via any
     * Writer method — the framework fills in a u32 length prefix on end.
     */
    public long beginUserTag(int tagId) {
        putExtTag(Format.EXT_USER_TAG);
        putI32(tagId);
        long lenMark = pos;
        ensure(5);
        pos += 5;
        return lenMark;
    }

    public void endUserTag(long lenMark) {
        long payloadStart = lenMark + 5;
        long payloadLen = pos - payloadStart;
        if (payloadLen > 0xFFFFFFFFL) {
            throw new IllegalStateException("hako: user-tag payload exceeds 4 GiB");
        }
        seg.set(ValueLayout.JAVA_BYTE, lenMark, (byte) Format.TIER_U32);
        seg.set(Format.LE_INT, lenMark + 1, (int) payloadLen);
    }

    public void writeDoubleArray(double[] arr) {
        int n = arr.length;
        putExtTag(Format.EXT_PRIM_DOUBLES);
        putTierValue(n);
        long bytes = (long) n * 8L;
        ensure(bytes);
        MemorySegment.copy(arr, 0, seg, Format.LE_DOUBLE, pos, n);
        pos += bytes;
    }

    public void writeIntArray(int[] arr) {
        int n = arr.length;
        putExtTag(Format.EXT_PRIM_INTS);
        putTierValue(n);
        long bytes = (long) n * 4L;
        ensure(bytes);
        MemorySegment.copy(arr, 0, seg, Format.LE_INT, pos, n);
        pos += bytes;
    }

    public void writeFloatArray(float[] arr) {
        int n = arr.length;
        putExtTag(Format.EXT_PRIM_FLOATS);
        putTierValue(n);
        long bytes = (long) n * 4L;
        ensure(bytes);
        MemorySegment.copy(arr, 0, seg, Format.LE_FLOAT, pos, n);
        pos += bytes;
    }

    public void writeShortArray(short[] arr) {
        int n = arr.length;
        putExtTag(Format.EXT_PRIM_SHORTS);
        putTierValue(n);
        long bytes = (long) n * 2L;
        ensure(bytes);
        MemorySegment.copy(arr, 0, seg, Format.LE_SHORT, pos, n);
        pos += bytes;
    }

    public void writeCharArray(char[] arr) {
        int n = arr.length;
        putExtTag(Format.EXT_PRIM_CHARS);
        putTierValue(n);
        long bytes = (long) n * 2L;
        ensure(bytes);
        MemorySegment.copy(arr, 0, seg, Format.LE_CHAR, pos, n);
        pos += bytes;
    }

    public void writeBooleanArray(boolean[] arr) {
        int n = arr.length;
        putExtTag(Format.EXT_PRIM_BOOLS);
        putTierValue(n);
        ensure(n);
        for (int i = 0; i < n; i++) {
            seg.set(ValueLayout.JAVA_BYTE, pos + i, arr[i] ? (byte) 1 : (byte) 0);
        }
        pos += n;
    }

    // Container counts are capped at u32 — the u64 tier slot (nibble
    // 15) on container majors marks the indefinite-length form instead.
    private static void checkContainerCount(long n) {
        if (n > 0xFFFFFFFFL) {
            throw new IllegalArgumentException(
                "hako: container count exceeds u32 (" + n + ")");
        }
    }

    public void writeVectorHeader(long n) {
        checkContainerCount(n);
        putSizedTag(Format.M_VEC, n);
    }

    public void writeListHeader(long n) {
        checkContainerCount(n);
        putSizedTag(Format.M_LIST, n);
    }

    public void writeSetHeader(long n) {
        checkContainerCount(n);
        putSizedTag(Format.M_SET, n);
    }

    public void writeMapHeader(long n) {
        checkContainerCount(n);
        putSizedTag(Format.M_MAP, n);
    }

    /**
     * Emit a keyword or symbol, interning first occurrence into the
     * per-message symbol table. Subsequent occurrences emit a symref.
     *
     * @param major Format.M_KW or Format.M_SYM
     * @param internKey object used as the sym-table lookup key —
     *                  Keyword / Symbol instances hash uniquely; plain
     *                  Strings are also valid keys for classname interning.
     * @param ns namespace, or null / empty
     * @param name local name (never null)
     */
    public void writeInterned(int major, Object internKey, String ns, String name) {
        long idx = symTableGet(internKey);
        if (idx != -1L) {
            putSizedTag(Format.M_SYMREF, idx);
            return;
        }
        byte[] payload = IDENT_BYTES_CACHE.get(internKey);
        if (payload == null) {
            payload = encodeIdentPayload(ns, name);
            IDENT_BYTES_CACHE.putIfAbsent(internKey, payload);
        }
        putSizedTag(major, payload.length);
        putBytes(payload);
        symTablePut(internKey, nextSymIdx++);
    }

    /**
     * Global cache of pre-encoded ident payload bytes keyed by Keyword /
     * Symbol / String instance. Skips the repeat UTF-8 encoding cost on
     * every unique first-in-message ident write. Bounded by the app's
     * ident vocabulary (typically thousands, not millions).
     */
    private static final ConcurrentHashMap<Object, byte[]> IDENT_BYTES_CACHE = new ConcurrentHashMap<>();

    private static byte[] encodeIdentPayload(String ns, String name) {
        byte[] nsBs   = (ns == null || ns.isEmpty()) ? EMPTY : ns.getBytes(StandardCharsets.UTF_8);
        byte[] nameBs = name.getBytes(StandardCharsets.UTF_8);
        int nsLen = nsBs.length;
        if (nsLen > 0xFF) {
            throw new IllegalArgumentException("hako: identifier namespace exceeds 255 bytes");
        }
        byte[] out = new byte[1 + nsLen + nameBs.length];
        out[0] = (byte) nsLen;
        System.arraycopy(nsBs, 0, out, 1, nsLen);
        System.arraycopy(nameBs, 0, out, 1 + nsLen, nameBs.length);
        return out;
    }

    // -- Records -----------------------------------------------------------

    public void writeRecord(Object v) {
        Class<?> klass = v.getClass();
        RecordInfo info = RecordRegistry.byClass(klass);
        if (info == null) {
            throw new IllegalStateException(
                "hako: record class not registered: " + klass.getName());
        }
        putExtTag(Format.EXT_RECORD);
        writeInterned(Format.M_SYM, info.className(), null, info.className());
        putTierValue(info.fieldCount());
        if (info.javaRecord()) {
            for (MethodHandle mh : info.accessorMHs()) {
                Object fieldVal;
                try {
                    fieldVal = (Object) mh.invokeExact(v);
                } catch (Throwable t) {
                    throw new IllegalStateException("hako: record accessor failed", t);
                }
                writeAny(fieldVal);
            }
        } else {
            IPersistentMap m = (IPersistentMap) v;
            for (Keyword k : info.fieldKeywords()) {
                writeAny(m.valAt(k));
            }
        }
    }

    // -- Bignumeric --------------------------------------------------------

    public void writeBigInteger(BigInteger x) {
        byte[] bs = x.toByteArray();
        putByte(Format.tag(Format.M_BIGNUM, Format.BIG_BIGINT));
        putTierValue(bs.length);
        putBytes(bs);
    }

    public void writeBigDecimal(BigDecimal x) {
        byte[] bs = x.unscaledValue().toByteArray();
        putByte(Format.tag(Format.M_BIGNUM, Format.BIG_BIGDEC));
        putI32(x.scale());
        putTierValue(bs.length);
        putBytes(bs);
    }

    public void writeRatio(Ratio r) {
        byte[] num = r.numerator.toByteArray();
        byte[] den = r.denominator.toByteArray();
        putByte(Format.tag(Format.M_BIGNUM, Format.BIG_RATIO));
        putTierValue(num.length);
        putBytes(num);
        putTierValue(den.length);
        putBytes(den);
    }

    // -- Container helpers -------------------------------------------------

    private void writeVectorAny(IPersistentVector v) {
        int n = v.count();
        // Single-pass homogeneity detection: optimistically unbox into
        // the prim array and bail to the generic path on first mismatch.
        // Success costs one walk (previous detect-then-fill cost two).
        // A short probe runs before the array alloc so the common
        // mismatch case (heterogeneous head) bails allocation-free.
        if (packHomogeneous && n > 0) {
            Object first = v.nth(0);
            boolean isLong = first instanceof Long;
            if (isLong || first instanceof Double) {
                int probe = Math.min(n, 16);
                int j = 1;
                if (isLong) {
                    for (; j < probe; j++) if (!(v.nth(j) instanceof Long)) break;
                } else {
                    for (; j < probe; j++) if (!(v.nth(j) instanceof Double)) break;
                }
                if (j == probe) {
                    if (isLong) {
                        long[] arr = new long[n];
                        int i = 0;
                        for (; i < n; i++) {
                            Object x = v.nth(i);
                            if (!(x instanceof Long)) break;
                            arr[i] = (Long) x;
                        }
                        if (i == n) { writeLongArray(arr); return; }
                    } else {
                        double[] arr = new double[n];
                        int i = 0;
                        for (; i < n; i++) {
                            Object x = v.nth(i);
                            if (!(x instanceof Double)) break;
                            arr[i] = (Double) x;
                        }
                        if (i == n) { writeDoubleArray(arr); return; }
                    }
                }
            }
        }
        writeVectorHeader(n);
        // PersistentVector's IReduce.reduce is a chunked tail-first walk
        // that beats per-element nth(i) past the first 32 elements (nth
        // does log32 tree descent per index).
        if (v instanceof IReduce) {
            ((IReduce) v).reduce(VEC_WRITER, this);
        } else {
            for (int i = 0; i < n; i++) writeAny(v.nth(i));
        }
    }

    private static final IFn KV_WRITER = new AFn() {
        @Override public Object invoke(Object w, Object k, Object v) {
            Writer wr = (Writer) w;
            wr.writeAny(k);
            wr.writeAny(v);
            return wr;
        }
    };

    private static final IFn VEC_WRITER = new AFn() {
        @Override public Object invoke(Object w, Object x) {
            ((Writer) w).writeAny(x);
            return w;
        }
    };

    private void writeMapAny(IPersistentMap m) {
        writeMapHeader(m.count());
        if (m instanceof IKVReduce) {
            ((IKVReduce) m).kvreduce(KV_WRITER, this);
            return;
        }
        java.util.Iterator<?> it = clojure.lang.RT.iter(m);
        while (it.hasNext()) {
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) it.next();
            writeAny(e.getKey());
            writeAny(e.getValue());
        }
    }

    private void writeSetAny(IPersistentSet s) {
        writeSetHeader(s.count());
        java.util.Iterator<?> it = clojure.lang.RT.iter(s);
        while (it.hasNext()) writeAny(it.next());
    }

    // Default comparator identity — natural ordering that `sorted-set` /
    // `sorted-map` install. Cached at class-init.
    private static final java.util.Comparator<?> DEFAULT_TREESET_CMP =
        PersistentTreeSet.EMPTY.comparator();
    private static final java.util.Comparator<?> DEFAULT_TREEMAP_CMP =
        PersistentTreeMap.EMPTY.comparator();

    /** Callback for the custom-comparator coercion warning (once-per-JVM). */
    public interface CustomComparatorWarner {
        void warn(Object coll);
    }
    private CustomComparatorWarner customComparatorWarner;
    public void setCustomComparatorWarner(CustomComparatorWarner w) {
        this.customComparatorWarner = w;
    }

    private void checkComparator(Object coll, java.util.Comparator<?> actual,
                                 java.util.Comparator<?> expected) {
        if (actual == expected) return;
        if (!coerceCustomComparator) {
            throw new IllegalArgumentException(
                "hako: cannot encode " + coll.getClass().getSimpleName()
                + " with custom comparator: " + actual);
        }
        if (customComparatorWarner != null) customComparatorWarner.warn(coll);
    }

    private void writeSortedSet(PersistentTreeSet s) {
        checkComparator(s, s.comparator(), DEFAULT_TREESET_CMP);
        putExtTag(Format.EXT_SORTED_SET);
        putTierValue(s.count());
        java.util.Iterator<?> it = s.iterator();
        while (it.hasNext()) writeAny(it.next());
    }

    private void writeSortedMap(PersistentTreeMap m) {
        checkComparator(m, m.comparator(), DEFAULT_TREEMAP_CMP);
        putExtTag(Format.EXT_SORTED_MAP);
        putTierValue(m.count());
        m.kvreduce(KV_WRITER, this);
    }

    private void writeQueue(PersistentQueue q) {
        putExtTag(Format.EXT_QUEUE);
        putTierValue(q.count());
        java.util.Iterator<?> it = q.iterator();
        while (it.hasNext()) writeAny(it.next());
    }

    private void writeSeqAny(ISeq s) {
        // Counted → single-pass with known count (PersistentList, etc.).
        if (s instanceof Counted) {
            int n = ((Counted) s).count();
            writeListHeader(n);
            for (ISeq cur = s.seq(); cur != null; cur = cur.next()) writeAny(cur.first());
            return;
        }
        // Lazy/chunked seqs: indefinite-length form — 1-byte marker,
        // streamed elements, 1-byte break. Single pass, no
        // materialization, and the frame is valid incrementally (the
        // count is never a placeholder), so buffers can be flushed
        // mid-container.
        putByte(Format.tag(Format.M_LIST, Format.CONTAINER_INDEFINITE));
        for (ISeq cur = s.seq(); cur != null; cur = cur.next()) writeAny(cur.first());
        putByte(Format.tag(Format.M_SPEC, Format.SPEC_BREAK));
    }

    private void writeIterableAny(Iterable<?> it) {
        if (it instanceof java.util.Collection) {
            java.util.Collection<?> c = (java.util.Collection<?>) it;
            writeListHeader(c.size());
            for (Object x : c) writeAny(x);
            return;
        }
        putByte(Format.tag(Format.M_LIST, Format.CONTAINER_INDEFINITE));
        for (Object x : it) writeAny(x);
        putByte(Format.tag(Format.M_SPEC, Format.SPEC_BREAK));
    }

    // -- Top-level dispatch (hot path) -------------------------------------

    /**
     * Encode any supported value. Falls back to the registered
     * {@link UnknownHandler} for records, sorted collections, queues,
     * user-tagged types, or anything else outside the built-in set.
     *
     * <p><b>Seq / Iterable note</b>: unknown-length {@code ISeq}s and
     * non-{@code Collection} {@code Iterable}s stream as
     * indefinite-length lists (1-byte marker + elements + 1-byte
     * break) — laziness is preserved, encoded prefixes become GC-able
     * as the walk advances, and the frame is valid incrementally.
     */
    public void writeAny(Object v) {
        writeAnyInner(v, false);
    }

    private void writeAnyInner(Object v, boolean skipMeta) {
        // Meta wrapping is the outermost concern: emit tag + inner (with
        // meta skipped for the current value only) + meta map.
        if (!skipMeta && writeMeta && v instanceof IObj) {
            IObj obj = (IObj) v;
            IPersistentMap m = obj.meta();
            if (m != null && m.count() > 0) {
                putExtTag(Format.EXT_WITH_META);
                writeAnyInner(v, true);
                writeAnyInner(m, false);
                return;
            }
        }

        if (v == null) { writeNil(); return; }

        // Ordered by expected frequency in typical Clojure data.
        if (v instanceof Long) { writeLong((Long) v); return; }
        if (v instanceof Keyword) {
            Keyword k = (Keyword) v;
            writeInterned(Format.M_KW, k, k.getNamespace(), k.getName());
            return;
        }
        if (v instanceof String) { writeString((String) v); return; }

        // Boxed scalars can never be records / sorted colls / queues /
        // collections, so they are dispatched before those checks —
        // saves ~9 type tests per Boolean/Double/Integer in mixed data.
        // Consequence: a user-tag registered on a boxed scalar class
        // (pathological) is not honored — the built-in encoding wins.
        if (v instanceof Boolean) { if ((Boolean) v) writeTrue(); else writeFalse(); return; }
        if (v instanceof Double)  { writeDouble((Double) v); return; }
        if (v instanceof Integer) { writeLong((Integer) v); return; }

        // Records dispatch straight to the Java write path — skip the
        // Clojure fallback handler.
        if (v instanceof clojure.lang.IRecord || v.getClass().isRecord()) {
            writeRecord(v);
            return;
        }
        // Sorted-coll + queue — Java-native paths. Preempts the generic
        // IPersistentSet/Map/Vector dispatch below since PersistentTreeSet
        // implements IPersistentSet, PersistentTreeMap implements
        // IPersistentMap, and PersistentQueue implements IPersistentList.
        // Use exact-class comparison (single ref compare + branch) instead
        // of three `instanceof` checks so the common case (PersistentArrayMap,
        // PersistentHashMap, PersistentVector, etc.) skips this branch in
        // one comparison of `Class` refs.
        Class<?> klass = v.getClass();
        if (klass == PersistentTreeSet.class) { writeSortedSet((PersistentTreeSet) v); return; }
        if (klass == PersistentTreeMap.class) { writeSortedMap((PersistentTreeMap) v); return; }
        if (klass == PersistentQueue.class)   { writeQueue((PersistentQueue) v); return; }

        // User-tag registrations win over generic collection / sequence
        // dispatch — otherwise a registered SpillableVector /
        // RoaringBitmap / SpillableMap / SpillableSet would silently
        // encode as a vector / list / map / set and lose its type on
        // decode. `UserTagRegistry.has` is a `ClassValue` slot after
        // the first hit — ~1 ns per call.
        if (UserTagRegistry.has(klass)) {
            fallback(v);
            return;
        }

        if (v instanceof IPersistentVector) { writeVectorAny((IPersistentVector) v); return; }
        if (v instanceof IPersistentMap)    { writeMapAny((IPersistentMap) v); return; }
        if (v instanceof IPersistentSet)    { writeSetAny((IPersistentSet) v); return; }
        if (v instanceof Symbol) {
            Symbol s = (Symbol) v;
            writeInterned(Format.M_SYM, s, s.getNamespace(), s.getName());
            return;
        }

        if (v instanceof Float)   { writeFloat((Float) v); return; }
        if (v instanceof Short)   { writeLong((Short) v); return; }
        if (v instanceof Byte)    { writeLong((Byte) v); return; }
        if (v instanceof Character) { writeChar(((Character) v).charValue()); return; }

        if (v instanceof UUID) {
            UUID u = (UUID) v;
            writeUuid(u.getMostSignificantBits(), u.getLeastSignificantBits());
            return;
        }
        if (v instanceof Instant) {
            Instant t = (Instant) v;
            writeInstant(t.getEpochSecond(), t.getNano());
            return;
        }
        if (v instanceof java.util.Date) {
            writeDate(((java.util.Date) v).getTime());
            return;
        }

        if (v instanceof BigInteger) { writeBigInteger((BigInteger) v); return; }
        if (v instanceof BigInt)     { writeBigInteger(((BigInt) v).toBigInteger()); return; }
        if (v instanceof BigDecimal) { writeBigDecimal((BigDecimal) v); return; }
        if (v instanceof Ratio)      { writeRatio((Ratio) v); return; }

        if (v instanceof byte[])   { writeBytes((byte[]) v); return; }
        if (v instanceof long[])   { writeLongArray((long[]) v); return; }
        if (v instanceof double[]) { writeDoubleArray((double[]) v); return; }
        if (v instanceof int[])    { writeIntArray((int[]) v); return; }
        if (v instanceof float[])  { writeFloatArray((float[]) v); return; }
        if (v instanceof short[])  { writeShortArray((short[]) v); return; }
        if (v instanceof char[])   { writeCharArray((char[]) v); return; }
        if (v instanceof boolean[]) { writeBooleanArray((boolean[]) v); return; }

        if (v instanceof ISeq)     { writeSeqAny((ISeq) v); return; }
        if (v instanceof Iterable) { writeIterableAny((Iterable<?>) v); return; }

        fallback(v);
    }

    private void fallback(Object v) {
        if (unknownHandler != null) {
            unknownHandler.write(this, v);
            return;
        }
        throw new IllegalArgumentException(
            "hako: no writer for value of type " + v.getClass().getName());
    }
}
