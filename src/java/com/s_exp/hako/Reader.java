package com.s_exp.hako;

import clojure.lang.IObj;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentHashSet;
import clojure.lang.PersistentList;
import clojure.lang.PersistentQueue;
import clojure.lang.PersistentTreeMap;
import clojure.lang.PersistentTreeSet;
import clojure.lang.PersistentVector;
import clojure.lang.Symbol;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable hako-format decoder over a MemorySegment. NOT thread-safe.
 * One-shot per message.
 */
public final class Reader {

    private MemorySegment seg;
    private long limit;
    private long pos;
    private final ArrayList<Object> symTable = new ArrayList<>();
    private boolean zeroCopy = false;

    public Reader(MemorySegment seg) {
        this.seg = seg;
        this.limit = seg.byteSize();
        this.pos = 0;
    }

    public boolean isZeroCopy() { return zeroCopy; }

    public void setZeroCopy(boolean b) { this.zeroCopy = b; }

    private boolean tolerant = false;
    private boolean cacheIdents = false;

    public boolean isTolerant() { return tolerant; }

    public void setTolerant(boolean b) { this.tolerant = b; }

    public boolean isCacheIdents() { return cacheIdents; }

    public void setCacheIdents(boolean b) { this.cacheIdents = b; }

    private int arrayMapThreshold = 8;
    private int arrayMapKwThreshold = 8;

    public void setArrayMapThresholds(int nonKw, int kw) {
        this.arrayMapThreshold = nonKw;
        this.arrayMapKwThreshold = kw;
    }

    public int arrayMapThreshold() { return arrayMapThreshold; }

    public int arrayMapKwThreshold() { return arrayMapKwThreshold; }

    /**
     * Handler invoked for extension subtypes that require the Clojure
     * registry lookup: records (subtype 3) and user-tags (subtype 15).
     */
    public interface ExtensionHandler {
        Object readRecord(Reader r);
        Object readUserTag(Reader r);
    }

    private ExtensionHandler extensionHandler;

    public void setExtensionHandler(ExtensionHandler h) { this.extensionHandler = h; }

    /** Advance the cursor by n bytes without materializing them. */
    public void skip(long n) {
        need(n);
        pos += n;
    }

    /**
     * Rebind the reader to a new segment and reset all per-message state.
     * Cheaper than allocating a new Reader — the sym-table backing list
     * is reused.
     */
    public void reset(MemorySegment newSeg) {
        this.seg = newSeg;
        this.limit = newSeg.byteSize();
        this.pos = 0;
        this.symTable.clear();
        this.zeroCopy = false;
        this.tolerant = false;
        this.cacheIdents = false;
    }

    public long pos() { return pos; }

    public long remaining() { return limit - pos; }

    public MemorySegment segment() { return seg; }

    private void need(long n) {
        if (pos + n > limit) {
            throw new IllegalStateException(
                "hako: unexpected end of message at pos " + pos
                + " (needed " + n + ", have " + (limit - pos) + ")");
        }
    }

    public int getByte() {
        need(1);
        int v = seg.get(ValueLayout.JAVA_BYTE, pos) & 0xFF;
        pos += 1;
        return v;
    }

    public int getU16() {
        need(2);
        int v = seg.get(Format.LE_SHORT, pos) & 0xFFFF;
        pos += 2;
        return v;
    }

    public long getU32() {
        need(4);
        long v = seg.get(Format.LE_INT, pos) & 0xFFFFFFFFL;
        pos += 4;
        return v;
    }

    public int getI32() {
        need(4);
        int v = seg.get(Format.LE_INT, pos);
        pos += 4;
        return v;
    }

    public long getI64() {
        need(8);
        long v = seg.get(Format.LE_LONG, pos);
        pos += 8;
        return v;
    }

    public float getF32() {
        need(4);
        float v = seg.get(Format.LE_FLOAT, pos);
        pos += 4;
        return v;
    }

    public double getF64() {
        need(8);
        double v = seg.get(Format.LE_DOUBLE, pos);
        pos += 8;
        return v;
    }

    public byte[] getBytes(int n) {
        need(n);
        byte[] arr = new byte[n];
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, pos, arr, 0, n);
        pos += n;
        return arr;
    }

    /** Zero-copy slice view of the next n bytes. Advances cursor. */
    public MemorySegment sliceBytes(long n) {
        need(n);
        MemorySegment s = seg.asSlice(pos, n);
        pos += n;
        return s;
    }

    public String getString(int n) {
        return new String(getBytes(n), StandardCharsets.UTF_8);
    }

    public long[] readLongArray(int n) {
        long bytes = (long) n * 8L;
        need(bytes);
        long[] arr = new long[n];
        MemorySegment.copy(seg, Format.LE_LONG, pos, arr, 0, n);
        pos += bytes;
        return arr;
    }

    public double[] readDoubleArray(int n) {
        long bytes = (long) n * 8L;
        need(bytes);
        double[] arr = new double[n];
        MemorySegment.copy(seg, Format.LE_DOUBLE, pos, arr, 0, n);
        pos += bytes;
        return arr;
    }

    public int[] readIntArray(int n) {
        long bytes = (long) n * 4L;
        need(bytes);
        int[] arr = new int[n];
        MemorySegment.copy(seg, Format.LE_INT, pos, arr, 0, n);
        pos += bytes;
        return arr;
    }

    public float[] readFloatArray(int n) {
        long bytes = (long) n * 4L;
        need(bytes);
        float[] arr = new float[n];
        MemorySegment.copy(seg, Format.LE_FLOAT, pos, arr, 0, n);
        pos += bytes;
        return arr;
    }

    public long readTierPayload(int code) {
        if (code <= Format.TIER_INLINE_MAX) return code;
        return switch (code) {
            case Format.TIER_U8 -> getByte();
            case Format.TIER_U16 -> getU16();
            case Format.TIER_U32 -> getU32();
            case Format.TIER_U64 -> getI64();
            default -> throw new IllegalStateException("hako: bad tier code " + code);
        };
    }

    /** Read a raw size-tier value (tier code byte + optional payload). */
    public long readTierValue() {
        int code = getByte();
        return readTierPayload(code);
    }

    public void readEnvelope() {
        int b0 = getByte();
        int b1 = getByte();
        int b2 = getByte();
        int b3 = getByte();
        if (b0 != (Format.MAGIC_0 & 0xFF)
            || b1 != (Format.MAGIC_1 & 0xFF)
            || b2 != (Format.MAGIC_2 & 0xFF)
            || b3 != (Format.MAGIC_3 & 0xFF)) {
            throw new IllegalStateException("hako: bad magic");
        }
        int v = getByte();
        if (v != 0) throw new IllegalStateException("hako: unsupported version " + v);
    }

    public void internAdd(Object o) {
        symTable.add(o);
    }

    public Object internGet(int idx) {
        return symTable.get(idx);
    }

    public int internSize() {
        return symTable.size();
    }

    // -- Global ident caches (opt-in via setCacheIdents) --------------------

    private static final ConcurrentHashMap<String, Keyword> KW_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Symbol> SYM_CACHE = new ConcurrentHashMap<>();

    // -- Ident payload parsing ---------------------------------------------

    /** Returns a 2-element array {ns, name}. Either may be null (empty ns). */
    private String[] readIdentPayload(int tierCode) {
        long totalLen = checkCount(readTierPayload(tierCode), "identifier length");
        int nsLen = getByte();
        int nameLen = (int) totalLen - 1 - nsLen;
        String ns = nsLen > 0 ? getString(nsLen) : null;
        String name = getString(nameLen);
        return new String[] { ns, name };
    }

    private Keyword readKeyword(int tierCode) {
        String[] parts = readIdentPayload(tierCode);
        Keyword kw;
        if (cacheIdents) {
            String key = parts[0] != null ? parts[0] + "/" + parts[1] : parts[1];
            Keyword hit = KW_CACHE.get(key);
            if (hit != null) {
                kw = hit;
            } else {
                kw = Keyword.intern(parts[0], parts[1]);
                Keyword prev = KW_CACHE.putIfAbsent(key, kw);
                if (prev != null) kw = prev;
            }
        } else {
            kw = Keyword.intern(parts[0], parts[1]);
        }
        symTable.add(kw);
        return kw;
    }

    private Symbol readSymbol(int tierCode) {
        String[] parts = readIdentPayload(tierCode);
        Symbol sym;
        if (cacheIdents) {
            String key = parts[0] != null ? parts[0] + "/" + parts[1] : parts[1];
            Symbol hit = SYM_CACHE.get(key);
            if (hit != null) {
                sym = hit;
            } else {
                sym = Symbol.intern(parts[0], parts[1]);
                Symbol prev = SYM_CACHE.putIfAbsent(key, sym);
                if (prev != null) sym = prev;
            }
        } else {
            sym = Symbol.intern(parts[0], parts[1]);
        }
        symTable.add(sym);
        return sym;
    }

    // -- Count guard --------------------------------------------------------

    private static long checkCount(long n, String what) {
        if (n < 0 || n > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                "hako: " + what + " exceeds Integer/MAX_VALUE (" + n + ")");
        }
        return n;
    }

    // -- Collection reads --------------------------------------------------

    private Object readVector(int tierCode) {
        int n = (int) checkCount(readTierPayload(tierCode), "vector count");
        if (n == 0) return PersistentVector.EMPTY;
        clojure.lang.ITransientCollection t = PersistentVector.EMPTY.asTransient();
        for (int i = 0; i < n; i++) t = t.conj(readAny());
        return t.persistent();
    }

    @SuppressWarnings("unchecked")
    private Object readList(int tierCode) {
        int n = (int) checkCount(readTierPayload(tierCode), "list count");
        if (n == 0) return PersistentList.EMPTY;
        Object[] arr = new Object[n];
        for (int i = 0; i < n; i++) arr[i] = readAny();
        return PersistentList.create(Arrays.asList(arr));
    }

    private Object readSet(int tierCode) {
        int n = (int) checkCount(readTierPayload(tierCode), "set count");
        if (n == 0) return PersistentHashSet.EMPTY;
        clojure.lang.ITransientCollection t = PersistentHashSet.EMPTY.asTransient();
        for (int i = 0; i < n; i++) t = t.conj(readAny());
        return t.persistent();
    }

    private Object readMap(int tierCode) {
        int n = (int) checkCount(readTierPayload(tierCode), "map count");
        if (n == 0) return PersistentArrayMap.EMPTY;
        Object[] arr = new Object[n * 2];
        for (int i = 0; i < n; i++) {
            arr[2 * i] = readAny();
            arr[2 * i + 1] = readAny();
        }
        if (n <= arrayMapThreshold) return new PersistentArrayMap(arr);
        if (n <= arrayMapKwThreshold && allKeywordKeys(arr, n)) {
            return new PersistentArrayMap(arr);
        }
        return PersistentHashMap.create(arr);
    }

    private static boolean allKeywordKeys(Object[] arr, int n) {
        for (int i = 0; i < n; i++) {
            if (!(arr[2 * i] instanceof Keyword)) return false;
        }
        return true;
    }

    // -- Special / bignumeric / extension reads ----------------------------

    private Object readSpecial(int low) {
        switch (low) {
            case Format.SPEC_NIL: return null;
            case Format.SPEC_TRUE: return Boolean.TRUE;
            case Format.SPEC_FALSE: return Boolean.FALSE;
            case Format.SPEC_NAN: return Double.NaN;
            case Format.SPEC_PINF: return Double.POSITIVE_INFINITY;
            case Format.SPEC_NINF: return Double.NEGATIVE_INFINITY;
            case Format.SPEC_UUID: {
                long hi = getI64();
                long lo = getI64();
                return new UUID(hi, lo);
            }
            case Format.SPEC_INST: {
                long s = getI64();
                long ns = getU32();
                return Instant.ofEpochSecond(s, ns);
            }
            case Format.SPEC_CHAR:
                return Character.valueOf((char) getU16());
            default:
                throw new IllegalStateException("hako: unknown special subtype " + low);
        }
    }

    private BigInteger readBigIntBytes() {
        int n = (int) checkCount(readTierValue(), "bignumeric byte-count");
        return new BigInteger(getBytes(n));
    }

    private Object readBignumeric(int low) {
        switch (low) {
            case Format.BIG_BIGINT:
                return clojure.lang.BigInt.fromBigInteger(readBigIntBytes());
            case Format.BIG_BIGDEC: {
                int scale = getI32();
                return new BigDecimal(readBigIntBytes(), scale);
            }
            case Format.BIG_RATIO: {
                BigInteger num = readBigIntBytes();
                BigInteger den = readBigIntBytes();
                return new clojure.lang.Ratio(num, den);
            }
            default:
                throw new IllegalStateException("hako: unknown bignumeric subtype " + low);
        }
    }

    private Object readSortedSet() {
        int n = (int) checkCount(readTierValue(), "sorted-set count");
        PersistentTreeSet s = PersistentTreeSet.EMPTY;
        for (int i = 0; i < n; i++) {
            s = (PersistentTreeSet) s.cons(readAny());
        }
        return s;
    }

    private Object readSortedMap() {
        int n = (int) checkCount(readTierValue(), "sorted-map count");
        PersistentTreeMap m = PersistentTreeMap.EMPTY;
        for (int i = 0; i < n; i++) {
            Object k = readAny();
            Object v = readAny();
            m = m.assoc(k, v);
        }
        return m;
    }

    private Object readQueue() {
        int n = (int) checkCount(readTierValue(), "queue count");
        PersistentQueue q = PersistentQueue.EMPTY;
        for (int i = 0; i < n; i++) {
            q = q.cons(readAny());
        }
        return q;
    }

    private Object readWithMeta() {
        Object v = readAny();
        Object m = readAny();
        if (v instanceof IObj && m instanceof IPersistentMap) {
            return ((IObj) v).withMeta((IPersistentMap) m);
        }
        return v;
    }

    private Object readExtension(int low) {
        switch (low) {
            case Format.EXT_SORTED_SET: return readSortedSet();
            case Format.EXT_SORTED_MAP: return readSortedMap();
            case Format.EXT_QUEUE:      return readQueue();
            case Format.EXT_RECORD:
                if (extensionHandler == null) {
                    throw new IllegalStateException(
                        "hako: record extension seen but no ExtensionHandler installed");
                }
                return extensionHandler.readRecord(this);
            case Format.EXT_WITH_META:  return readWithMeta();
            case Format.EXT_PRIM_LONGS: {
                int n = (int) checkCount(readTierValue(), "prim-longs count");
                return readLongArray(n);
            }
            case Format.EXT_PRIM_DOUBLES: {
                int n = (int) checkCount(readTierValue(), "prim-doubles count");
                return readDoubleArray(n);
            }
            case Format.EXT_PRIM_INTS: {
                int n = (int) checkCount(readTierValue(), "prim-ints count");
                return readIntArray(n);
            }
            case Format.EXT_PRIM_FLOATS: {
                int n = (int) checkCount(readTierValue(), "prim-floats count");
                return readFloatArray(n);
            }
            case Format.EXT_USER_TAG:
                if (extensionHandler == null) {
                    throw new IllegalStateException(
                        "hako: user-tag seen but no ExtensionHandler installed");
                }
                return extensionHandler.readUserTag(this);
            default:
                throw new IllegalStateException("hako: unknown extension subtype " + low);
        }
    }

    // -- Top-level dispatch (hot path) -------------------------------------

    public Object readAny() {
        int tag = getByte();
        int major = tag & 0xF0;
        int low = tag & 0x0F;
        switch (major) {
            case Format.M_UINT: return Long.valueOf(readTierPayload(low));
            case Format.M_SINT: return Long.valueOf(Format.zigZagDecode(readTierPayload(low)));
            case Format.M_FLOAT:
                if (low == Format.FLOAT_F32) return Float.valueOf(getF32());
                if (low == Format.FLOAT_F64) return Double.valueOf(getF64());
                throw new IllegalStateException("hako: unknown float subtype " + low);
            case Format.M_BYTES: {
                long n = readTierPayload(low);
                if (zeroCopy) return sliceBytes(n);
                return getBytes((int) checkCount(n, "byte string length"));
            }
            case Format.M_STRING:
                return getString((int) checkCount(readTierPayload(low), "string length"));
            case Format.M_KW:     return readKeyword(low);
            case Format.M_SYM:    return readSymbol(low);
            case Format.M_VEC:    return readVector(low);
            case Format.M_LIST:   return readList(low);
            case Format.M_SET:    return readSet(low);
            case Format.M_MAP:    return readMap(low);
            case Format.M_SYMREF:
                return symTable.get((int) checkCount(readTierPayload(low), "symref index"));
            case Format.M_BIGNUM: return readBignumeric(low);
            case Format.M_EXT:    return readExtension(low);
            case Format.M_SPEC:   return readSpecial(low);
            default:
                throw new IllegalStateException("hako: unknown major type 0x" + Integer.toHexString(major));
        }
    }
}
