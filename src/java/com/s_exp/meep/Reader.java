package com.s_exp.meep;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Mutable meep-format decoder over a MemorySegment. NOT thread-safe.
 * One-shot per message.
 */
public final class Reader {

    private final MemorySegment seg;
    private final long limit;
    private long pos;
    private final ArrayList<Object> symTable = new ArrayList<>();

    public Reader(MemorySegment seg) {
        this.seg = seg;
        this.limit = seg.byteSize();
        this.pos = 0;
    }

    public long pos() { return pos; }

    public long remaining() { return limit - pos; }

    public MemorySegment segment() { return seg; }

    private void need(long n) {
        if (pos + n > limit) {
            throw new IllegalStateException(
                "meep: unexpected end of message at pos " + pos
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

    public long readTierPayload(int code) {
        if (code <= Format.TIER_INLINE_MAX) return code;
        return switch (code) {
            case Format.TIER_U8 -> getByte();
            case Format.TIER_U16 -> getU16();
            case Format.TIER_U32 -> getU32();
            case Format.TIER_U64 -> getI64();
            default -> throw new IllegalStateException("meep: bad tier code " + code);
        };
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
            throw new IllegalStateException("meep: bad magic");
        }
        int v = getByte();
        if (v != 0) throw new IllegalStateException("meep: unsupported version " + v);
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
}
