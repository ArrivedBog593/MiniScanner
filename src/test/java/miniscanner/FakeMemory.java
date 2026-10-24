package miniscanner;

import com.sun.jna.Memory;

import java.util.List;

/** Memoria simulada de un "juego": una sola región escribible, de la que se puede leer solo una parte. */
final class FakeMemory implements Win.MemorySource {
    final long base;
    final int size;
    final Memory mem;
    /** Bytes legibles desde el inicio (para simular páginas que el juego liberó). */
    int readable;

    FakeMemory(long base, int size) {
        this.base = base;
        this.size = size;
        this.readable = size;
        mem = new Memory(size);
        mem.clear();
    }

    public List<Win.Region> regions(boolean writableOnly) { return List.of(new Win.Region(base, size)); }

    public int readInto(long addr, Memory dst, int n) {
        long off = addr - base;
        if (off < 0 || off >= readable) return 0;
        int got = (int) Math.min(n, readable - off);
        dst.write(0, mem.getByteArray(off, got), 0, got);
        return got;
    }

    public byte[] read(long addr, int n) {
        long off = addr - base;
        return off < 0 || off + n > readable ? null : mem.getByteArray(off, n);
    }
}
