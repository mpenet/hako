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
import clojure.lang.LazilyPersistentVector;
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

/**
 * Mutable hako-format decoder over a {@link MemorySegment}.
 *
 * <p>NOT thread-safe. If the source segment is backed by an
 * {@code Arena.ofConfined()}, cross-thread access throws
 * {@code WrongThreadException} at the FFM layer.
 *
 * <p>Reusable across many messages via {@link #reset(MemorySegment)}.
 * The registered {@link ExtensionHandler} and array-map thresholds
 * survive resets.
 *
 * <p>Static caches ({@code KW_CACHE}, {@code SYM_CACHE}) used when
 * {@code :cache-idents true} is set are {@link
 * java.util.concurrent.ConcurrentHashMap} and safe for concurrent
 * decode from multiple Reader instances.
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
     * Handler invoked for extension subtypes that need the Clojure
     * user-tag registry. Records are handled entirely in Java (see
     * {@link RecordRegistry}), so this interface only covers user-tags.
     */
    public interface ExtensionHandler {
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
        // Preserve extensionHandler and arrayMapThresholds — they are
        // one-time setup for the lifetime of the Reader instance.
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

    private byte[] stringDecodeBuf = new byte[128];
    // Above this cap, oversized strings decode through a one-shot buffer
    // that goes to GC instead of pinning the reusable scratch at the
    // largest string ever seen. Prevents a pathological 10 MB payload
    // from wedging 10 MB per thread for the JVM lifetime.
    private static final int STRING_DECODE_BUF_MAX = 1 << 20;  // 1 MiB

    public String getString(int n) {
        if (n == 0) return "";
        need(n);
        byte[] buf;
        if (n > STRING_DECODE_BUF_MAX) {
            buf = new byte[n];
        } else {
            buf = stringDecodeBuf;
            if (buf.length < n) {
                buf = new byte[Math.max(n, buf.length * 2)];
                stringDecodeBuf = buf;
            }
        }
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, pos, buf, 0, n);
        pos += n;
        return new String(buf, 0, n, StandardCharsets.UTF_8);
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

    public short[] readShortArray(int n) {
        long bytes = (long) n * 2L;
        need(bytes);
        short[] arr = new short[n];
        MemorySegment.copy(seg, Format.LE_SHORT, pos, arr, 0, n);
        pos += bytes;
        return arr;
    }

    public char[] readCharArray(int n) {
        long bytes = (long) n * 2L;
        need(bytes);
        char[] arr = new char[n];
        MemorySegment.copy(seg, Format.LE_CHAR, pos, arr, 0, n);
        pos += bytes;
        return arr;
    }

    public boolean[] readBooleanArray(int n) {
        need(n);
        boolean[] arr = new boolean[n];
        for (int i = 0; i < n; i++) {
            arr[i] = seg.get(ValueLayout.JAVA_BYTE, pos + i) != 0;
        }
        pos += n;
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
        need(5);
        if (seg.get(Format.LE_INT, pos) != Format.MAGIC_LE) {
            throw new IllegalStateException("hako: bad magic");
        }
        int v = seg.get(ValueLayout.JAVA_BYTE, pos + 4) & 0xFF;
        if (v != 0) throw new IllegalStateException("hako: unsupported version " + v);
        pos += 5;
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

    /**
     * Byte-keyed open-addressing table: keys are UTF-8 byte payloads of
     * non-namespaced ident names, values are the interned Keyword/Symbol.
     * Volatile snapshot pattern — lock-free reads, synchronized writes
     * that clone-and-swap the whole table. Avoids the String allocation
     * that a {@code CHM<String, Keyword>} would force on every lookup.
     */
    private static final class IdentCache<V> {
        final byte[][] keys;
        final V[]      values;
        final int      mask;
        final int      size;

        IdentCache(byte[][] keys, V[] values, int mask, int size) {
            this.keys = keys; this.values = values;
            this.mask = mask; this.size = size;
        }

        @SuppressWarnings("unchecked")
        static <V> IdentCache<V> empty(int cap) {
            return new IdentCache<>(new byte[cap][], (V[]) new Object[cap], cap - 1, 0);
        }

        V lookupSeg(MemorySegment seg, long off, int len, int hash) {
            int i = hash & mask;
            while (true) {
                byte[] k = keys[i];
                if (k == null) return null;
                if (k.length == len && bytesEqualSeg(seg, off, k)) return values[i];
                i = (i + 1) & mask;
            }
        }

        V lookupArr(byte[] arr, int hash) {
            int i = hash & mask;
            while (true) {
                byte[] k = keys[i];
                if (k == null) return null;
                if (k.length == arr.length && Arrays.equals(k, arr)) return values[i];
                i = (i + 1) & mask;
            }
        }

        @SuppressWarnings("unchecked")
        IdentCache<V> withEntry(byte[] key, V val) {
            int cap = keys.length;
            int newCap = ((size + 1) << 1) > cap ? cap << 1 : cap;
            byte[][] nk = new byte[newCap][];
            V[]      nv = (V[]) new Object[newCap];
            int mask2  = newCap - 1;
            for (int j = 0; j < cap; j++) {
                byte[] k = keys[j];
                if (k != null) insertRaw(nk, nv, mask2, k, values[j]);
            }
            insertRaw(nk, nv, mask2, key, val);
            return new IdentCache<>(nk, nv, mask2, size + 1);
        }

        private static <V> void insertRaw(byte[][] nk, V[] nv, int mask, byte[] key, V val) {
            int i = bytesHashArr(key) & mask;
            while (nk[i] != null) i = (i + 1) & mask;
            nk[i] = key;
            nv[i] = val;
        }
    }

    private static volatile IdentCache<Keyword> KW_CACHE = IdentCache.empty(1024);
    private static volatile IdentCache<Symbol>  SYM_CACHE = IdentCache.empty(256);
    private static final Object KW_LOCK  = new Object();
    private static final Object SYM_LOCK = new Object();

    private static final java.lang.invoke.VarHandle LONG_VIEW =
        java.lang.invoke.MethodHandles.byteArrayViewVarHandle(
            long[].class, java.nio.ByteOrder.LITTLE_ENDIAN);

    // Chunked mix over 8-byte LE words + byte tail. NOT
    // Arrays.hashCode-compatible — bytesHashSeg (segment side) and
    // bytesHashArr (byte[] side) MUST stay in lockstep; they feed the
    // same IdentCache tables.
    // murmur3 fmix64 finalizer — avalanches the chunked mix so keys
    // sharing long common prefixes don't cluster in the table.
    private static int fmix(long h) {
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (int) h;
    }

    private static int bytesHashSeg(MemorySegment seg, long off, int len) {
        long h = 1;
        int i = 0;
        for (; i + 8 <= len; i += 8) {
            h = h * 0x9E3779B97F4A7C15L + seg.get(Format.LE_LONG, off + i);
        }
        for (; i < len; i++) {
            h = h * 31 + seg.get(ValueLayout.JAVA_BYTE, off + i);
        }
        return fmix(h);
    }

    private static int bytesHashArr(byte[] bs) {
        long h = 1;
        int i = 0;
        int n = bs.length;
        for (; i + 8 <= n; i += 8) {
            h = h * 0x9E3779B97F4A7C15L + (long) LONG_VIEW.get(bs, i);
        }
        for (; i < n; i++) {
            h = h * 31 + bs[i];
        }
        return fmix(h);
    }

    private static boolean bytesEqualSeg(MemorySegment seg, long off, byte[] target) {
        int n = target.length;
        int i = 0;
        for (; i + 8 <= n; i += 8) {
            if (seg.get(Format.LE_LONG, off + i) != (long) LONG_VIEW.get(target, i)) return false;
        }
        for (; i < n; i++) {
            if (seg.get(ValueLayout.JAVA_BYTE, off + i) != target[i]) return false;
        }
        return true;
    }

    /**
     * Pre-populate the global keyword cache used by
     * {@code :cache-idents true} decode. Callers with a known
     * vocabulary (schemas, protocol tags) can warm the cache at
     * startup to avoid the first-decode intern cost. Only affects
     * non-namespaced keywords — namespaced keywords bypass this cache
     * and go through {@code Keyword.intern} directly.
     */
    public static void primeKwCache(String name, Keyword kw) {
        byte[] bs = identBytes(null, name);
        synchronized (KW_LOCK) {
            if (KW_CACHE.lookupArr(bs, bytesHashArr(bs)) == null) {
                KW_CACHE = KW_CACHE.withEntry(bs, kw);
            }
        }
    }

    /** Symbol counterpart of {@link #primeKwCache}. */
    public static void primeSymCache(String name, Symbol sym) {
        byte[] bs = identBytes(null, name);
        synchronized (SYM_LOCK) {
            if (SYM_CACHE.lookupArr(bs, bytesHashArr(bs)) == null) {
                SYM_CACHE = SYM_CACHE.withEntry(bs, sym);
            }
        }
    }

    /**
     * Build the wire-format ident payload: [nsLen byte][ns bytes][name bytes].
     * Used to construct cache keys that match on-wire byte layout.
     */
    private static byte[] identBytes(String ns, String name) {
        byte[] nsB   = ns != null ? ns.getBytes(StandardCharsets.UTF_8) : EMPTY_BYTES;
        byte[] nameB = name.getBytes(StandardCharsets.UTF_8);
        byte[] out   = new byte[1 + nsB.length + nameB.length];
        out[0] = (byte) nsB.length;
        System.arraycopy(nsB, 0, out, 1, nsB.length);
        System.arraycopy(nameB, 0, out, 1 + nsB.length, nameB.length);
        return out;
    }

    private static final byte[] EMPTY_BYTES = new byte[0];

    // -- Ident payload parsing ---------------------------------------------

    private Keyword readKeyword(int tierCode) {
        // Cache-idents hot path: hash over the full ident payload
        // (nsLen byte + ns + name bytes — inherently unique per (ns, name))
        // and look up the cached Keyword. Only materialize Strings on miss.
        long totalLen = checkCount(readTierPayload(tierCode), "identifier length");
        int payloadLen = (int) totalLen;
        need(payloadLen);
        int nsLen = seg.get(ValueLayout.JAVA_BYTE, pos) & 0xFF;
        if (nsLen + 1 > totalLen) {
            throw new IllegalStateException(
                "hako: identifier ns-length " + nsLen
                + " exceeds declared payload length " + totalLen);
        }
        int nameLen = payloadLen - 1 - nsLen;
        Keyword kw;
        if (cacheIdents) {
            int hash = bytesHashSeg(seg, pos, payloadLen);
            Keyword hit = KW_CACHE.lookupSeg(seg, pos, payloadLen, hash);
            if (hit != null) {
                pos += payloadLen;
                kw = hit;
            } else {
                pos += 1;   // skip nsLen byte
                String ns   = nsLen > 0 ? getString(nsLen) : null;
                String name = getString(nameLen);
                kw = Keyword.intern(ns, name);
                byte[] bs = new byte[payloadLen];
                MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, pos - payloadLen, bs, 0, payloadLen);
                synchronized (KW_LOCK) {
                    if (KW_CACHE.lookupArr(bs, hash) == null) {
                        KW_CACHE = KW_CACHE.withEntry(bs, kw);
                    }
                }
            }
        } else {
            pos += 1;
            String ns   = nsLen > 0 ? getString(nsLen) : null;
            String name = getString(nameLen);
            kw = Keyword.intern(ns, name);
        }
        symTable.add(kw);
        return kw;
    }

    private Symbol readSymbol(int tierCode) {
        long totalLen = checkCount(readTierPayload(tierCode), "identifier length");
        int payloadLen = (int) totalLen;
        need(payloadLen);
        int nsLen = seg.get(ValueLayout.JAVA_BYTE, pos) & 0xFF;
        if (nsLen + 1 > totalLen) {
            throw new IllegalStateException(
                "hako: identifier ns-length " + nsLen
                + " exceeds declared payload length " + totalLen);
        }
        int nameLen = payloadLen - 1 - nsLen;
        Symbol sym;
        if (cacheIdents) {
            int hash = bytesHashSeg(seg, pos, payloadLen);
            Symbol hit = SYM_CACHE.lookupSeg(seg, pos, payloadLen, hash);
            if (hit != null) {
                pos += payloadLen;
                sym = hit;
            } else {
                pos += 1;
                String ns   = nsLen > 0 ? getString(nsLen) : null;
                String name = getString(nameLen);
                sym = Symbol.intern(ns, name);
                byte[] bs = new byte[payloadLen];
                MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, pos - payloadLen, bs, 0, payloadLen);
                synchronized (SYM_LOCK) {
                    if (SYM_CACHE.lookupArr(bs, hash) == null) {
                        SYM_CACHE = SYM_CACHE.withEntry(bs, sym);
                    }
                }
            }
        } else {
            pos += 1;
            String ns   = nsLen > 0 ? getString(nsLen) : null;
            String name = getString(nameLen);
            sym = Symbol.intern(ns, name);
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
        if (tierCode == Format.CONTAINER_INDEFINITE) {
            clojure.lang.ITransientCollection t = PersistentVector.EMPTY.asTransient();
            int b;
            while ((b = getByte()) != BREAK_TAG) t = t.conj(readAnyTag(b));
            return t.persistent();
        }
        int n = (int) checkCount(readTierPayload(tierCode), "vector count");
        if (n == 0) return PersistentVector.EMPTY;
        // n <= 32 fits in a single PersistentVector tail: `createOwning`
        // wraps the Object[] directly (zero extra alloc) instead of building
        // the transient node structure.
        if (n <= 32) {
            Object[] arr = new Object[n];
            for (int i = 0; i < n; i++) arr[i] = readAny();
            return LazilyPersistentVector.createOwning(arr);
        }
        clojure.lang.ITransientCollection t = PersistentVector.EMPTY.asTransient();
        for (int i = 0; i < n; i++) t = t.conj(readAny());
        return t.persistent();
    }

    private Object readList(int tierCode) {
        if (tierCode == Format.CONTAINER_INDEFINITE) {
            ArrayList<Object> tmp = new ArrayList<>();
            int b;
            while ((b = getByte()) != BREAK_TAG) tmp.add(readAnyTag(b));
            clojure.lang.IPersistentCollection ret = PersistentList.EMPTY;
            for (int i = tmp.size() - 1; i >= 0; i--) ret = ret.cons(tmp.get(i));
            return ret;
        }
        int n = (int) checkCount(readTierPayload(tierCode), "list count");
        if (n == 0) return PersistentList.EMPTY;
        // Every element is at least 1 byte on the wire, so a count
        // larger than the remaining bytes is malformed. Guards the
        // Object[n] preallocation against crafted counts (OOM DoS).
        need(n);
        Object[] arr = new Object[n];
        for (int i = 0; i < n; i++) arr[i] = readAny();
        // Cons backward from the array — skips `Arrays.asList` wrapper and
        // the `ListIterator` that `PersistentList.create(List)` allocates.
        clojure.lang.IPersistentCollection ret = PersistentList.EMPTY;
        for (int i = n - 1; i >= 0; i--) ret = ret.cons(arr[i]);
        return ret;
    }

    private Object readSet(int tierCode) {
        if (tierCode == Format.CONTAINER_INDEFINITE) {
            clojure.lang.ITransientCollection t = PersistentHashSet.EMPTY.asTransient();
            int b;
            while ((b = getByte()) != BREAK_TAG) t = t.conj(readAnyTag(b));
            return t.persistent();
        }
        int n = (int) checkCount(readTierPayload(tierCode), "set count");
        if (n == 0) return PersistentHashSet.EMPTY;
        clojure.lang.ITransientCollection t = PersistentHashSet.EMPTY.asTransient();
        for (int i = 0; i < n; i++) t = t.conj(readAny());
        return t.persistent();
    }

    private Object readMap(int tierCode) {
        if (tierCode == Format.CONTAINER_INDEFINITE) {
            // Break is only legal at a key position; a break where a
            // value is expected surfaces as "break outside indefinite
            // container" from readSpecial via the value's readAny.
            clojure.lang.ITransientMap t =
                (clojure.lang.ITransientMap) PersistentHashMap.EMPTY.asTransient();
            int b;
            while ((b = getByte()) != BREAK_TAG) {
                Object k = readAnyTag(b);
                t = t.assoc(k, readAny());
            }
            return t.persistent();
        }
        int n = (int) checkCount(readTierPayload(tierCode), "map count");
        if (n == 0) return PersistentArrayMap.EMPTY;
        // Definitely-hashmap: skip the Object[n*2] intermediate and
        // read straight into a transient PersistentHashMap.
        if (n > arrayMapKwThreshold) {
            clojure.lang.ITransientMap t = (clojure.lang.ITransientMap) PersistentHashMap.EMPTY.asTransient();
            for (int i = 0; i < n; i++) {
                Object k = readAny();
                Object val = readAny();
                t = t.assoc(k, val);
            }
            return t.persistent();
        }
        Object[] arr = new Object[n * 2];
        // Track allKw during the fill loop so we skip the separate
        // `allKeywordKeys` re-scan below.
        boolean allKw = true;
        for (int i = 0; i < n; i++) {
            Object k = readAny();
            if (allKw && !(k instanceof Keyword)) allKw = false;
            arr[2 * i] = k;
            arr[2 * i + 1] = readAny();
        }
        if (n <= arrayMapThreshold || allKw) return new PersistentArrayMap(arr);
        return PersistentHashMap.create(arr);
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
            case Format.SPEC_DATE:
                return new java.util.Date(getI64());
            case Format.SPEC_BREAK:
                throw new IllegalStateException(
                    "hako: break tag outside an indefinite-length container");
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

    private Object readRecord() {
        Object classnameSym = readAny();
        String className = classnameSym.toString();
        RecordInfo info = RecordRegistry.byName(className);
        if (info == null) {
            throw new IllegalStateException("hako: unknown record class: " + className);
        }
        long n = checkCount(readTierValue(), "record field count");
        if (n != info.fieldCount()) {
            throw new IllegalStateException(
                "hako: record field count mismatch (expected " + info.fieldCount()
                + ", got " + n + ")");
        }
        Object[] args = new Object[(int) n];
        for (int i = 0; i < n; i++) args[i] = readAny();
        try {
            return (Object) info.spreadCtorMH().invokeExact(args);
        } catch (Throwable t) {
            throw new IllegalStateException("hako: record ctor failed", t);
        }
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
                return readRecord();
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
            case Format.EXT_PRIM_SHORTS: {
                int n = (int) checkCount(readTierValue(), "prim-shorts count");
                return readShortArray(n);
            }
            case Format.EXT_PRIM_CHARS: {
                int n = (int) checkCount(readTierValue(), "prim-chars count");
                return readCharArray(n);
            }
            case Format.EXT_PRIM_BOOLS: {
                int n = (int) checkCount(readTierValue(), "prim-booleans count");
                return readBooleanArray(n);
            }
            case Format.EXT_OBJECT_ARRAY: {
                int n = (int) checkCount(readTierValue(), "Object[] count");
                need(n);  // every element is ≥ 1 byte on wire
                Object[] arr = new Object[n];
                for (int i = 0; i < n; i++) arr[i] = readAny();
                return arr;
            }
            case Format.EXT_REGEX: {
                Object src = readAny();
                if (!(src instanceof String)) {
                    throw new IllegalStateException(
                        "hako: regex pattern payload must be a string, got "
                        + (src == null ? "nil" : src.getClass().getName()));
                }
                int flags = getI32();
                return java.util.regex.Pattern.compile((String) src, flags);
            }
            case Format.EXT_URI: {
                Object src = readAny();
                if (!(src instanceof String)) {
                    throw new IllegalStateException(
                        "hako: URI payload must be a string, got "
                        + (src == null ? "nil" : src.getClass().getName()));
                }
                try {
                    return new java.net.URI((String) src);
                } catch (java.net.URISyntaxException e) {
                    throw new IllegalStateException("hako: malformed URI in wire payload", e);
                }
            }
            case Format.EXT_DURATION: {
                long secs = getI64();
                int nanos = getI32();
                return java.time.Duration.ofSeconds(secs, nanos);
            }
            case Format.EXT_PERIOD: {
                int y = getI32();
                int m = getI32();
                int d = getI32();
                return java.time.Period.of(y, m, d);
            }
            case Format.EXT_LOCAL_DATE:
                return java.time.LocalDate.ofEpochDay(getI64());
            case Format.EXT_LOCAL_TIME:
                return java.time.LocalTime.ofNanoOfDay(getI64());
            case Format.EXT_LOCAL_DATE_TIME: {
                long epochDay = getI64();
                long nanoOfDay = getI64();
                return java.time.LocalDateTime.of(
                    java.time.LocalDate.ofEpochDay(epochDay),
                    java.time.LocalTime.ofNanoOfDay(nanoOfDay));
            }
            case Format.EXT_ZONED_DATE_TIME: {
                long secs = getI64();
                long nanos = getU32();
                Object zoneId = readAny();
                if (!(zoneId instanceof String)) {
                    throw new IllegalStateException(
                        "hako: zone-id payload must be a string, got "
                        + (zoneId == null ? "nil" : zoneId.getClass().getName()));
                }
                return java.time.ZonedDateTime.ofInstant(
                    Instant.ofEpochSecond(secs, nanos),
                    java.time.ZoneId.of((String) zoneId));
            }
            case Format.EXT_OFFSET_DATE_TIME: {
                long epochDay = getI64();
                long nanoOfDay = getI64();
                int offsetSecs = getI32();
                return java.time.OffsetDateTime.of(
                    java.time.LocalDate.ofEpochDay(epochDay),
                    java.time.LocalTime.ofNanoOfDay(nanoOfDay),
                    java.time.ZoneOffset.ofTotalSeconds(offsetSecs));
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
        return readAnyTag(getByte());
    }

    private static final int BREAK_TAG = Format.M_SPEC | Format.SPEC_BREAK;

    private Object readAnyTag(int tag) {
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
            case Format.M_EXT: {
                // Extension frame: tag byte is exactly 0xE0, subtype
                // lives in the following u8 (uniform 256-id namespace).
                if (low != 0) {
                    throw new IllegalStateException(
                        "hako: malformed extension tag 0x" + Integer.toHexString(tag));
                }
                return readExtension(getByte());
            }
            case Format.M_SPEC:   return readSpecial(low);
            default:
                throw new IllegalStateException("hako: unknown major type 0x" + Integer.toHexString(major));
        }
    }
}
