package com.s_exp.hako;

import clojure.lang.BigInt;
import clojure.lang.IObj;
import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentSet;
import clojure.lang.IPersistentVector;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.Ratio;
import clojure.lang.Symbol;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mutable hako-format encoder. Owns an internal Arena and grows the
 * MemorySegment by doubling on overflow. NOT thread-safe. One-shot.
 */
public final class Writer implements AutoCloseable {

    private static final byte[] EMPTY = new byte[0];

    private final Arena arena;
    private MemorySegment seg;
    private long pos;
    private long cap;
    private final HashMap<Object, Long> symTable = new HashMap<>();
    private long nextSymIdx = 0;
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
        symTable.clear();
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
        if (n < 0 || n > MAX_CAP - pos) {
            throw new IllegalStateException(
                "hako: write exceeds max buffer capacity (" + MAX_CAP + " bytes)");
        }
        long need = pos + n;
        if (need <= cap) return;
        long newCap = cap;
        while (newCap < need) newCap <<= 1;
        MemorySegment newSeg = arena.allocate(newCap, 1);
        MemorySegment.copy(seg, 0L, newSeg, 0L, pos);
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

    public void writeLong(long n) {
        if (n >= 0) {
            putSizedTag(Format.M_UINT, n);
        } else {
            putSizedTag(Format.M_SINT, Format.zigZagEncode(n));
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
        putByte(Format.tag(Format.M_FLOAT, Format.FLOAT_F64));
        putF64(d);
    }

    public void writeFloat(float f) {
        putByte(Format.tag(Format.M_FLOAT, Format.FLOAT_F32));
        putF32(f);
    }

    public void writeString(String s) {
        byte[] bs = s.getBytes(StandardCharsets.UTF_8);
        putSizedTag(Format.M_STRING, bs.length);
        putBytes(bs);
    }

    public void writeBytes(byte[] bs) {
        putSizedTag(Format.M_BYTES, bs.length);
        putBytes(bs);
    }

    public void writeUuid(long msb, long lsb) {
        putByte(Format.tag(Format.M_SPEC, Format.SPEC_UUID));
        putU64(msb);
        putU64(lsb);
    }

    public void writeInstant(long epochSec, int nano) {
        putByte(Format.tag(Format.M_SPEC, Format.SPEC_INST));
        putU64(epochSec);
        putU32(nano);
    }

    public void writeChar(int codeUnit) {
        putByte(Format.tag(Format.M_SPEC, Format.SPEC_CHAR));
        putU16(codeUnit);
    }

    public void writeLongArray(long[] arr) {
        int n = arr.length;
        putByte(Format.tag(Format.M_EXT, Format.EXT_PRIM_LONGS));
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
        putByte(Format.tag(Format.M_EXT, Format.EXT_USER_TAG));
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
        putByte(Format.tag(Format.M_EXT, Format.EXT_PRIM_DOUBLES));
        putTierValue(n);
        long bytes = (long) n * 8L;
        ensure(bytes);
        MemorySegment.copy(arr, 0, seg, Format.LE_DOUBLE, pos, n);
        pos += bytes;
    }

    public void writeIntArray(int[] arr) {
        int n = arr.length;
        putByte(Format.tag(Format.M_EXT, Format.EXT_PRIM_INTS));
        putTierValue(n);
        long bytes = (long) n * 4L;
        ensure(bytes);
        MemorySegment.copy(arr, 0, seg, Format.LE_INT, pos, n);
        pos += bytes;
    }

    public void writeFloatArray(float[] arr) {
        int n = arr.length;
        putByte(Format.tag(Format.M_EXT, Format.EXT_PRIM_FLOATS));
        putTierValue(n);
        long bytes = (long) n * 4L;
        ensure(bytes);
        MemorySegment.copy(arr, 0, seg, Format.LE_FLOAT, pos, n);
        pos += bytes;
    }

    public void writeVectorHeader(long n) {
        putSizedTag(Format.M_VEC, n);
    }

    public void writeListHeader(long n) {
        putSizedTag(Format.M_LIST, n);
    }

    public void writeSetHeader(long n) {
        putSizedTag(Format.M_SET, n);
    }

    public void writeMapHeader(long n) {
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
        Long idx = symTable.get(internKey);
        if (idx != null) {
            putSizedTag(Format.M_SYMREF, idx);
            return;
        }
        byte[] nsBs = (ns == null || ns.isEmpty()) ? EMPTY : ns.getBytes(StandardCharsets.UTF_8);
        byte[] nameBs = name.getBytes(StandardCharsets.UTF_8);
        int nsLen = nsBs.length;
        if (nsLen > 0xFF) {
            throw new IllegalArgumentException("hako: identifier namespace exceeds 255 bytes");
        }
        int payloadLen = 1 + nsLen + nameBs.length;
        putSizedTag(major, payloadLen);
        putByte(nsLen);
        if (nsLen > 0) putBytes(nsBs);
        putBytes(nameBs);
        symTable.put(internKey, nextSymIdx++);
    }

    // -- Records -----------------------------------------------------------

    public void writeRecord(Object v) {
        Class<?> klass = v.getClass();
        RecordInfo info = RecordRegistry.byClass(klass);
        if (info == null) {
            throw new IllegalStateException(
                "hako: record class not registered: " + klass.getName());
        }
        putByte(Format.tag(Format.M_EXT, Format.EXT_RECORD));
        writeInterned(Format.M_SYM, info.className(), null, info.className());
        putTierValue(info.fieldCount());
        if (info.javaRecord()) {
            Object[] singleArg = { v };
            for (MethodHandle mh : info.accessorMHs()) {
                Object fieldVal;
                try {
                    fieldVal = mh.invokeWithArguments(singleArg);
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
        if (packHomogeneous && n > 0) {
            Class<?> homo = homogeneousClass(v, n);
            if (homo == Long.class) {
                long[] arr = new long[n];
                for (int i = 0; i < n; i++) arr[i] = (Long) v.nth(i);
                writeLongArray(arr);
                return;
            }
            if (homo == Double.class) {
                double[] arr = new double[n];
                for (int i = 0; i < n; i++) arr[i] = (Double) v.nth(i);
                writeDoubleArray(arr);
                return;
            }
        }
        writeVectorHeader(n);
        for (int i = 0; i < n; i++) writeAny(v.nth(i));
    }

    private static Class<?> homogeneousClass(IPersistentVector v, int n) {
        Object first = v.nth(0);
        if (!(first instanceof Long) && !(first instanceof Double)) return null;
        Class<?> target = first.getClass();
        for (int i = 1; i < n; i++) {
            if (v.nth(i).getClass() != target) return null;
        }
        return target;
    }

    private void writeMapAny(IPersistentMap m) {
        writeMapHeader(m.count());
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

    private void writeSeqAny(ISeq s) {
        // Materialize once to know count. Normalize via .seq() so empty
        // seqs / lists yield null (skip the loop) rather than looping
        // once with a null element.
        java.util.ArrayList<Object> tmp = new java.util.ArrayList<>();
        for (ISeq cur = s.seq(); cur != null; cur = cur.next()) tmp.add(cur.first());
        int n = tmp.size();
        writeListHeader(n);
        for (int i = 0; i < n; i++) writeAny(tmp.get(i));
    }

    private void writeIterableAny(Iterable<?> it) {
        java.util.ArrayList<Object> tmp = new java.util.ArrayList<>();
        for (Object x : it) tmp.add(x);
        int n = tmp.size();
        writeListHeader(n);
        for (int i = 0; i < n; i++) writeAny(tmp.get(i));
    }

    // -- Top-level dispatch (hot path) -------------------------------------

    /**
     * Encode any supported value. Falls back to the registered
     * `UnknownHandler` for records, sorted collections, queues,
     * user-tagged types, or anything else outside the built-in set.
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
                putByte(Format.tag(Format.M_EXT, Format.EXT_WITH_META));
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

        // Records dispatch straight to the Java write path — skip the
        // Clojure fallback handler.
        if (v instanceof clojure.lang.IRecord || v.getClass().isRecord()) {
            writeRecord(v);
            return;
        }
        // Delegate remaining types that overlap generic interfaces below
        // to the Clojure fallback (sorted colls, queue, user-tags).
        if (v instanceof clojure.lang.PersistentTreeSet
            || v instanceof clojure.lang.PersistentTreeMap
            || v instanceof clojure.lang.PersistentQueue) {
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

        if (v instanceof Boolean) { if ((Boolean) v) writeTrue(); else writeFalse(); return; }
        if (v instanceof Double)  { writeDouble((Double) v); return; }
        if (v instanceof Float)   { writeFloat((Float) v); return; }
        if (v instanceof Integer) { writeLong((Integer) v); return; }
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

        if (v instanceof BigInteger) { writeBigInteger((BigInteger) v); return; }
        if (v instanceof BigInt)     { writeBigInteger(((BigInt) v).toBigInteger()); return; }
        if (v instanceof BigDecimal) { writeBigDecimal((BigDecimal) v); return; }
        if (v instanceof Ratio)      { writeRatio((Ratio) v); return; }

        if (v instanceof byte[])   { writeBytes((byte[]) v); return; }
        if (v instanceof long[])   { writeLongArray((long[]) v); return; }
        if (v instanceof double[]) { writeDoubleArray((double[]) v); return; }
        if (v instanceof int[])    { writeIntArray((int[]) v); return; }
        if (v instanceof float[])  { writeFloatArray((float[]) v); return; }

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
