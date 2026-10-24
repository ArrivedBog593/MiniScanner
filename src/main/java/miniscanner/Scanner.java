package miniscanner;

import com.sun.jna.Memory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * Escáner de valores con dos formas de guardar resultados:
 *  - Lista ("sparse"): direcciones + valor anterior. Para cuando hay pocos resultados.
 *  - Instantánea ("dense"): copia de toda la memoria en un archivo temporal + un mapa de bits
 *    de qué direcciones siguen siendo candidatas. Para "valor desconocido" y escaneos con
 *    muchísimos resultados. Cuando quedan pocos, se convierte sola a lista.
 */
final class Scanner implements Closeable {
    static final int CHUNK = 16 * 1024 * 1024, BLOCK = 64 * 1024;
    static final int MAX_RESULTS = 20_000_000, SPARSE_LIMIT = 2_000_000;
    /** Bytes extra que se leen tras cada bloque (el tipo más grande) para ver valores que cruzan al siguiente. */
    static final int OVERLAP = 8;

    enum Mode {
        EXACT("Valor exacto", 1), GT("Mayor que", 1), LT("Menor que", 1), BETWEEN("Entre", 2),
        UNKNOWN("Valor desconocido", 0), INC("Aumentó", 0), DEC("Disminuyó", 0),
        INC_BY("Aumentó en...", 1), DEC_BY("Disminuyó en...", 1), CHANGED("Cambió", 0), UNCHANGED("Sin cambio", 0);

        final String label;
        final int values; // cuántos números necesita (0, 1 o 2)

        Mode(String label, int values) { this.label = label; this.values = values; }

        static final Mode[] FIRST = {EXACT, GT, LT, BETWEEN, UNKNOWN};
        static final Mode[] NEXT = {EXACT, INC, DEC, CHANGED, UNCHANGED, INC_BY, DEC_BY, GT, LT, BETWEEN};

        @Override public String toString() { return label; }
    }

    // ------------------------------------------------------------ estados
    static final class Results {
        long[] addr = new long[1024];
        double[] val = new double[1024];
        int n;

        void add(long a, double v) {
            if (n == addr.length) {
                if (n >= MAX_RESULTS) throw new IllegalStateException("Demasiados resultados (más de "
                        + String.format("%,d", MAX_RESULTS) + "). Prueba con «Valor desconocido» o con otro valor/tipo.");
                int cap = Math.min(n * 2, MAX_RESULTS);
                addr = Arrays.copyOf(addr, cap);
                val = Arrays.copyOf(val, cap);
            }
            addr[n] = a; val[n] = v; n++;
        }
    }

    private interface State {}
    private record Sparse(Results r) implements State {}
    private record Dense(BitSet[] bits, int file, long count) implements State {}
    private record Block(long base, int len, long fileOff) {}

    final Win.MemorySource proc;
    final ValueType type;
    /** Tolerancia para floats (si buscas 100, acepta de 99.5 a 100.5). */
    volatile double tol = 0.5;

    private State state, prev;
    private List<Block> blocks;
    private FileChannel[] files;
    private Path[] paths;

    Scanner(Win.MemorySource proc, ValueType type) { this.proc = proc; this.type = type; }

    boolean hasResults() { return state != null; }
    boolean canUndo() { return prev != null; }
    boolean isSnapshot() { return state instanceof Dense; }

    long count() {
        if (state instanceof Sparse s) return s.r().n;
        if (state instanceof Dense d) return d.count();
        return 0;
    }

    // ------------------------------------------------------------ comparación
    private boolean eq(double a, double b) { return type.isFloat ? Math.abs(a - b) <= tol : a == b; }

    boolean test(Mode m, double cur, double prev, double v1, double v2) {
        double t = type.isFloat ? tol : 0;
        return switch (m) {
            case EXACT -> eq(cur, v1);
            case GT -> cur > v1;
            case LT -> cur < v1;
            case BETWEEN -> cur >= Math.min(v1, v2) - t && cur <= Math.max(v1, v2) + t;
            case UNKNOWN -> true;
            case INC -> cur > prev;
            case DEC -> cur < prev;
            case INC_BY -> eq(cur - prev, v1);
            case DEC_BY -> eq(prev - cur, v1);
            case CHANGED -> Double.compare(cur, prev) != 0;
            case UNCHANGED -> Double.compare(cur, prev) == 0;
        };
    }

    // ------------------------------------------------------------ escaneos
    void firstScan(Mode m, double v1, double v2, Progress p) throws IOException {
        close();
        prev = null;
        try {
            Results r = m == Mode.EXACT ? sparseExact(v1, p) : null;
            // Un valor muy común (0, 1...) no cabe en lista: se usa la instantánea en disco
            state = r != null ? new Sparse(r) : denseFirst(m, v1, v2, p);
            maybeSparsify();
        } catch (IOException | RuntimeException ex) {
            close();
            state = null;
            throw ex;
        }
    }

    void nextScan(Mode m, double v1, double v2, Progress p) throws IOException {
        State s = state;
        if (s == null) return;
        State ns;
        try {
            ns = s instanceof Sparse sp ? new Sparse(sparseNext(sp.r(), m, v1, v2, p)) : denseNext((Dense) s, m, v1, v2, p);
        } catch (IOException | RuntimeException ex) {
            // denseNext pudo dejar a medias el archivo de la instantánea anterior: ya no se puede volver a ella
            if (s instanceof Dense && prev instanceof Dense) prev = null;
            throw ex;
        }
        prev = s;
        state = ns;
        maybeSparsify();
    }

    /** Regresa al resultado anterior (un nivel). */
    void undo() {
        if (prev != null) { state = prev; prev = null; }
    }

    /** Primeras direcciones de los resultados (para mostrarlas en la lista). */
    long[] firstAddresses(int limit) {
        if (state instanceof Sparse s) return Arrays.copyOf(s.r().addr, Math.min(limit, s.r().n));
        if (!(state instanceof Dense d)) return new long[0];
        long[] out = new long[(int) Math.min(limit, d.count())];
        int n = 0, al = type.align();
        for (int i = 0; i < blocks.size() && n < out.length; i++)
            for (int k = d.bits()[i].nextSetBit(0); k >= 0 && n < out.length; k = d.bits()[i].nextSetBit(k + 1))
                out[n++] = blocks.get(i).base() + (long) k * al;
        return out;
    }

    // ---- lista
    /** Devuelve null si hay demasiados resultados para una lista (más de SPARSE_LIMIT). */
    private Results sparseExact(double value, Progress p) {
        Results res = new Results();
        ByteBuffer t = ByteBuffer.wrap(type.encode(value)).order(Win.LE);
        int sz = type.size, align = type.align();
        Memory mem = new Memory(CHUNK + OVERLAP);
        List<Win.Region> regs = proc.regions(true);
        for (int r = 0; r < regs.size(); r++) {
            long base = regs.get(r).base(), size = regs.get(r).size();
            for (long off = 0; off < size; off += CHUNK) {
                if (res.n > SPARSE_LIMIT) return null;
                p.update(r, regs.size()); // también es donde se nota si se canceló, en regiones enormes
                int got = proc.readInto(base + off, mem, (int) Math.min(CHUNK + OVERLAP, size - off));
                if (got < sz) continue;
                ByteBuffer b = mem.getByteBuffer(0, got).order(Win.LE);
                int last = Math.min(got - sz, CHUNK - 1); // lo que pasa de CHUNK es del siguiente bloque
                long a0 = base + off;
                if (type.isFloat) {
                    for (int i = 0; i <= last; i += align) {
                        double v = type.get(b, i);
                        if (eq(v, value)) res.add(a0 + i, v);
                    }
                } else {
                    double stored = type.get(t, 0);
                    switch (sz) {
                        case 1 -> { byte x = t.get(0); for (int i = 0; i <= last; i += align) if (b.get(i) == x) res.add(a0 + i, stored); }
                        case 2 -> { short x = t.getShort(0); for (int i = 0; i <= last; i += align) if (b.getShort(i) == x) res.add(a0 + i, stored); }
                        case 4 -> { int x = t.getInt(0); for (int i = 0; i <= last; i += align) if (b.getInt(i) == x) res.add(a0 + i, stored); }
                        default -> { long x = t.getLong(0); for (int i = 0; i <= last; i += align) if (b.getLong(i) == x) res.add(a0 + i, stored); }
                    }
                }
            }
            p.update(r + 1, regs.size());
        }
        return res;
    }

    private Results sparseNext(Results old, Mode m, double v1, double v2, Progress p) {
        Results res = new Results();
        Memory mem = new Memory(BLOCK);
        int sz = type.size, i = 0;
        while (i < old.n) {
            long start = old.addr[i];
            int j = i;
            while (j < old.n && old.addr[j] + sz <= start + BLOCK) j++;
            int got = proc.readInto(start, mem, (int) (old.addr[j - 1] + sz - start));
            ByteBuffer b = got > 0 ? mem.getByteBuffer(0, got).order(Win.LE) : null;
            for (int k = i; k < j; k++) {
                long a = old.addr[k];
                int rel = (int) (a - start);
                double cur;
                if (b != null && rel + sz <= got) cur = type.get(b, rel);
                else {
                    byte[] raw = proc.read(a, sz);
                    if (raw == null) continue;
                    cur = type.decode(raw);
                }
                if (test(m, cur, old.val[k], v1, v2)) res.add(a, cur);
            }
            i = j;
            p.update(i, old.n);
        }
        return res;
    }

    // ---- instantánea
    private void openFiles() throws IOException {
        paths = new Path[2];
        files = new FileChannel[2];
        for (int i = 0; i < 2; i++) {
            paths[i] = Files.createTempFile("miniscanner-", ".snap");
            paths[i].toFile().deleteOnExit();
            files[i] = FileChannel.open(paths[i], StandardOpenOption.READ, StandardOpenOption.WRITE);
        }
    }

    private static void writeFully(FileChannel ch, ByteBuffer src, long pos) throws IOException {
        while (src.hasRemaining()) pos += ch.write(src, pos);
    }

    private static void readFully(FileChannel ch, ByteBuffer dst, long pos) throws IOException {
        while (dst.hasRemaining()) {
            int n = ch.read(dst, pos);
            if (n < 0) break;
            pos += n;
        }
    }

    private Dense denseFirst(Mode m, double v1, double v2, Progress p) throws IOException {
        openFiles();
        blocks = new ArrayList<>();
        long fileOff = 0;
        for (Win.Region r : proc.regions(true))
            for (long off = 0; off < r.size(); off += CHUNK) {
                int len = (int) Math.min(CHUNK + OVERLAP, r.size() - off);
                blocks.add(new Block(r.base() + off, len, fileOff));
                fileOff += len;
            }
        BitSet[] bits = new BitSet[blocks.size()];
        Memory mem = new Memory(CHUNK + OVERLAP);
        int sz = type.size, al = type.align();
        long count = 0;
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            BitSet bs = new BitSet();
            int got = proc.readInto(b.base(), mem, b.len());
            if (got >= sz) {
                ByteBuffer buf = mem.getByteBuffer(0, got).order(Win.LE);
                writeFully(files[0], mem.getByteBuffer(0, got), b.fileOff());
                int slots = Math.min((got - sz) / al + 1, CHUNK / al); // los bytes de OVERLAP son del siguiente bloque
                if (m == Mode.UNKNOWN) bs.set(0, slots);
                else for (int k = 0; k < slots; k++) if (test(m, type.get(buf, k * al), 0, v1, v2)) bs.set(k);
                count += bs.cardinality();
            }
            bits[i] = bs;
            p.update(i + 1, blocks.size());
        }
        return new Dense(bits, 0, count);
    }

    private Dense denseNext(Dense old, Mode m, double v1, double v2, Progress p) throws IOException {
        int nf = 1 - old.file();
        BitSet[] nb = new BitSet[blocks.size()];
        Memory mem = new Memory(CHUNK + OVERLAP);
        ByteBuffer prevBuf = ByteBuffer.allocate(CHUNK + OVERLAP).order(Win.LE);
        int sz = type.size, al = type.align();
        long count = 0;
        for (int i = 0; i < blocks.size(); i++) {
            BitSet ob = old.bits()[i];
            if (ob.isEmpty()) { nb[i] = ob; continue; }
            Block b = blocks.get(i);
            BitSet bs = (BitSet) ob.clone();
            int got = proc.readInto(b.base(), mem, b.len());
            if (got < sz) bs.clear();
            else {
                ByteBuffer cur = mem.getByteBuffer(0, got).order(Win.LE);
                prevBuf.clear();
                prevBuf.limit(b.len());
                readFully(files[old.file()], prevBuf, b.fileOff());
                for (int k = bs.nextSetBit(0); k >= 0; k = bs.nextSetBit(k + 1)) {
                    int off = k * al;
                    if (off + sz > got) { bs.clear(k, Integer.MAX_VALUE); break; }
                    if (!test(m, type.get(cur, off), type.get(prevBuf, off), v1, v2)) bs.clear(k);
                }
                if (!bs.isEmpty()) writeFully(files[nf], mem.getByteBuffer(0, got), b.fileOff());
            }
            nb[i] = bs;
            count += bs.cardinality();
            p.update(i + 1, blocks.size());
        }
        return new Dense(nb, nf, count);
    }

    /** Si la instantánea ya tiene pocos resultados, la convierte en lista (más rápida). */
    private void maybeSparsify() throws IOException {
        if (!(state instanceof Dense d) || d.count() > SPARSE_LIMIT) return;
        Results r = new Results();
        ByteBuffer buf = ByteBuffer.allocate(CHUNK + OVERLAP).order(Win.LE);
        int al = type.align();
        for (int i = 0; i < blocks.size(); i++) {
            BitSet bs = d.bits()[i];
            if (bs.isEmpty()) continue;
            Block b = blocks.get(i);
            buf.clear();
            buf.limit(b.len());
            readFully(files[d.file()], buf, b.fileOff());
            for (int k = bs.nextSetBit(0); k >= 0; k = bs.nextSetBit(k + 1))
                r.add(b.base() + (long) k * al, type.get(buf, k * al));
        }
        state = new Sparse(r);
    }

    @Override public void close() {
        if (files != null) for (FileChannel f : files) try { f.close(); } catch (IOException ignored) {}
        if (paths != null) for (Path p : paths) try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        files = null;
        paths = null;
    }
}
