package com.s_exp.meep;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * Mutable meep-format encoder. Owns an internal Arena and grows the
 * MemorySegment by doubling on overflow. NOT thread-safe. One-shot.
 */
public final class Writer implements AutoCloseable {

    private static final byte[] EMPTY = new byte[0];

    private final Arena arena;
    private MemorySegment seg;
    private long pos;
    private long cap;
    private final HashMap<String, Long> symTable = new HashMap<>();
    private long nextSymIdx = 0;
    private boolean writeMeta = false;
    private boolean packHomogeneous = false;

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
    }

    @Override
    public void close() {
        arena.close();
    }

    private void ensure(long n) {
        long need = pos + n;
        if (need <= cap) return;
        long newCap = cap;
        while (newCap < need) newCap <<= 1;
        MemorySegment newSeg = arena.allocate(newCap, 1);
        MemorySegment.copy(seg, 0L, newSeg, 0L, pos);
        seg = newSeg;
        cap = newCap;
    }

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
            throw new IllegalStateException("meep: user-tag payload exceeds 4 GiB");
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
     * @param ns namespace, or null / empty
     * @param name local name (never null)
     */
    public void writeInterned(int major, String ns, String name) {
        String key = (ns == null ? "" : ns) + " " + name;
        Long idx = symTable.get(key);
        if (idx != null) {
            putSizedTag(Format.M_SYMREF, idx);
            return;
        }
        byte[] nsBs = (ns == null || ns.isEmpty()) ? EMPTY : ns.getBytes(StandardCharsets.UTF_8);
        byte[] nameBs = name.getBytes(StandardCharsets.UTF_8);
        int nsLen = nsBs.length;
        if (nsLen > 0xFF) {
            throw new IllegalArgumentException("meep: identifier namespace exceeds 255 bytes");
        }
        int payloadLen = 1 + nsLen + nameBs.length;
        putSizedTag(major, payloadLen);
        putByte(nsLen);
        if (nsLen > 0) putBytes(nsBs);
        putBytes(nameBs);
        symTable.put(key, nextSymIdx++);
    }
}
