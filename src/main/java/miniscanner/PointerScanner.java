package miniscanner;

import com.sun.jna.Memory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Escáner de pointers: busca cadenas que empiezan en una dirección estática de un módulo
 * (exe/dll) y, saltando de pointer en pointer, llegan a la dirección objetivo.
 * Como los módulos siempre cargan su contenido en el mismo lugar relativo, esas cadenas
 * siguen funcionando aunque reinicies el juego.
 */
final class PointerScanner {
    static final int MAX_RESULTS = 200_000, MAX_NODES = 3_000_000, CHUNK = 4 * 1024 * 1024;
    /** Tamaño máximo de un arreglo en Java. */
    static final int MAX_ARRAY = Integer.MAX_VALUE - 8;
    /** RAM por pointer en el peor caso: listas por hilo (2 × 8 bytes, hasta ×2 de margen al crecer) + arreglos finales (16). */
    static final long BYTES_PER_POINTER = 48;
    /** RAM que se deja libre para la búsqueda de cadenas (nodos, visitados) y el resto del programa. */
    static final long RESERVE = 256L * 1024 * 1024;

    private PointerScanner() {}

    // ------------------------------------------------------------ listas primitivas
    static final class LongList {
        long[] a = new long[256]; int n;
        void add(long v) {
            if (n == a.length) {
                if (n >= MAX_ARRAY) throw new IllegalStateException("Lista de pointers demasiado grande.");
                a = Arrays.copyOf(a, (int) Math.min((long) n * 2, MAX_ARRAY));
            }
            a[n++] = v;
        }
    }

    static final class IntList {
        int[] a = new int[256]; int n;
        void add(int v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; }
    }

    // ------------------------------------------------------------ escaneo
    /**
     * @param phase recibe textos de fase para mostrar ("Leyendo memoria", etc.)
     */
    static List<AddressExpr> scan(Win.Proc proc, long target, int maxLevel, long maxOffset,
                                  Progress p, java.util.function.Consumer<String> phase) throws Exception {
        proc.refreshModules();
        // 1) Mapa de todos los pointers de la memoria escribible: (valor → dirección donde está guardado)
        phase.accept("Leyendo memoria");
        List<Win.Region> regs = proc.regions(true);
        if (regs.isEmpty()) return List.of();
        long[] rb = new long[regs.size()], re = new long[regs.size()];
        for (int i = 0; i < regs.size(); i++) { rb[i] = regs.get(i).base(); re[i] = regs.get(i).end(); }
        long min = rb[0], max = re[re.length - 1];
        int ps = proc.ptrSize;

        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        AtomicLong done = new AtomicLong(), found = new AtomicLong();
        long limit = pointerLimit();
        List<Future<LongList[]>> futures = new ArrayList<>();
        for (Win.Region r : regs) {
            futures.add(ex.submit(() -> {
                LongList vals = new LongList(), addrs = new LongList();
                Memory mem = new Memory(Math.min(CHUNK, r.size()));
                for (long off = 0; off < r.size(); off += CHUNK) {
                    // Si ya no caben (lo detectó este hilo u otro), se para todo antes de quedarse sin RAM
                    if (found.get() > limit) throw new IllegalStateException(tooMany(limit));
                    p.update(done.get(), regs.size()); // permite cancelar sin esperar al final de la región
                    int got = proc.readInto(r.base() + off, mem, (int) Math.min(CHUNK, r.size() - off));
                    if (got <= 0) continue;
                    ByteBuffer b = mem.getByteBuffer(0, got).order(Win.LE);
                    int before = vals.n;
                    for (int i = 0; i + ps <= got; i += ps) {
                        long v = ps == 8 ? b.getLong(i) : b.getInt(i) & 0xFFFFFFFFL;
                        if (v < min || v >= max || !inRegions(rb, re, v)) continue;
                        vals.add(v);
                        addrs.add(r.base() + off + i);
                    }
                    found.addAndGet(vals.n - before);
                }
                p.update(done.incrementAndGet(), regs.size());
                return new LongList[]{vals, addrs};
            }));
        }
        ex.shutdown();
        long total = 0;
        List<LongList[]> parts = new ArrayList<>();
        try {
            for (Future<LongList[]> f : futures) { LongList[] part = f.get(); parts.add(part); total += part[0].n; }
        } catch (ExecutionException e) {
            ex.shutdownNow();
            // Para que la ventana muestre el mensaje y no "java.lang.IllegalStateException: ..."
            if (e.getCause() instanceof RuntimeException rt) throw rt;
            if (e.getCause() instanceof Error err) throw err;
            throw e;
        }
        if (total > limit) throw new IllegalStateException(tooMany(limit));
        int n = (int) total;
        long[] vals = new long[n], addrs = new long[n];
        int pos = 0;
        for (int i = 0; i < parts.size(); i++) {
            LongList[] part = parts.get(i);
            System.arraycopy(part[0].a, 0, vals, pos, part[0].n);
            System.arraycopy(part[1].a, 0, addrs, pos, part[1].n);
            pos += part[0].n;
            parts.set(i, null); // que el GC la libere ya
        }
        parts.clear();

        phase.accept("Ordenando " + String.format("%,d", n) + " pointers");
        p.update(0, 1);
        sort(vals, addrs, 0, n - 1);

        phase.accept("Buscando cadenas");
        List<AddressExpr> results = findChains(vals, addrs, n, target, maxLevel, maxOffset, proc::moduleAt, p);
        p.update(1, 1);
        // Las cadenas más cortas primero: suelen ser las más estables
        results.sort(Comparator.comparingInt((AddressExpr e) -> e.offsets.length).thenComparingLong(e -> e.offset));
        return results;
    }

    /** Búsqueda hacia atrás: ¿qué pointers apuntan cerca del objetivo? ¿y cerca de esos?... */
    static List<AddressExpr> findChains(long[] vals, long[] addrs, int total, long target, int maxLevel, long maxOffset,
                                        java.util.function.LongFunction<Win.Module> moduleAt, Progress p) {
        List<AddressExpr> results = new ArrayList<>();
        long[] nAddr = new long[1024], nOff = new long[1024];
        int[] nPar = new int[1024];
        int nodes = 0;
        nAddr[0] = target; nPar[0] = -1; nodes = 1;
        IntList current = new IntList();
        current.add(0);
        HashSet<Long> visited = new HashSet<>();
        visited.add(target);

        outer:
        for (int level = 1; level <= maxLevel && current.n > 0; level++) {
            IntList next = new IntList();
            for (int ci = 0; ci < current.n; ci++) {
                int t = current.a[ci];
                long tAddr = nAddr[t];
                for (int k = lowerBound(vals, total, tAddr - maxOffset); k < total && vals[k] <= tAddr; k++) {
                    long a = addrs[k], off = tAddr - vals[k];
                    Win.Module m = moduleAt.apply(a);
                    if (m != null) {
                        // Llegamos a una dirección estática: cadena completa
                        List<Long> offs = new ArrayList<>();
                        offs.add(off);
                        for (int x = t; nPar[x] != -1; x = nPar[x]) offs.add(nOff[x]);
                        results.add(new AddressExpr(m.name(), a - m.base(), offs.stream().mapToLong(Long::longValue).toArray()));
                        if (results.size() >= MAX_RESULTS) break outer;
                    } else if (level < maxLevel && nodes < MAX_NODES && visited.add(a)) {
                        if (nodes == nAddr.length) {
                            int cap = nodes * 2;
                            nAddr = Arrays.copyOf(nAddr, cap); nOff = Arrays.copyOf(nOff, cap); nPar = Arrays.copyOf(nPar, cap);
                        }
                        nAddr[nodes] = a; nOff[nodes] = off; nPar[nodes] = t;
                        next.add(nodes++);
                    }
                }
                if ((ci & 1023) == 0) p.update((long) (level - 1) * 1000 + ci * 1000L / current.n, maxLevel * 1000L);
            }
            current = next;
        }
        return results;
    }

    static List<AddressExpr> filterByAddress(Win.Proc proc, List<AddressExpr> list, long addr, Progress p) {
        proc.refreshModules();
        List<AddressExpr> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).resolve(proc) == addr) out.add(list.get(i));
            if ((i & 255) == 0) p.update(i, list.size());
        }
        return out;
    }

    /** @param tol tolerancia para floats, la misma que usa el escáner (MiniScanner.tolFor) */
    static List<AddressExpr> filterByValue(Win.Proc proc, List<AddressExpr> list, ValueType type, double value, double tol, Progress p) {
        proc.refreshModules();
        List<AddressExpr> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            long a = list.get(i).resolve(proc);
            byte[] raw = a == Win.BAD ? null : proc.read(a, type.size);
            if (raw != null) {
                double v = type.decode(raw);
                if (type.isFloat ? Math.abs(v - value) <= tol : v == value) out.add(list.get(i));
            }
            if ((i & 255) == 0) p.update(i, list.size());
        }
        return out;
    }

    // ------------------------------------------------------------ utilidades
    /** Cuántos pointers caben en la RAM libre de Java antes de arriesgar un OutOfMemoryError. */
    static long pointerLimit() {
        Runtime rt = Runtime.getRuntime();
        long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory()) - RESERVE;
        return Math.max(0, Math.min(free / BYTES_PER_POINTER, MAX_ARRAY));
    }

    static String tooMany(long limit) {
        return "El juego tiene demasiados pointers para la memoria de Java (caben unos "
                + String.format("%,d", limit) + ").\nCierra MiniScanner y ábrelo con más RAM, por ejemplo:\n"
                + "java -Xmx8g -jar MiniScanner.jar";
    }

    static boolean inRegions(long[] rb, long[] re, long v) {
        int lo = 0, hi = rb.length - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (rb[mid] <= v) { found = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return found >= 0 && v < re[found];
    }

    static int lowerBound(long[] a, int n, long key) {
        int lo = 0, hi = n;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (a[mid] < key) lo = mid + 1; else hi = mid; }
        return lo;
    }

    /** Quicksort que ordena k[] y mueve v[] en paralelo. */
    static void sort(long[] k, long[] v, int lo, int hi) {
        while (hi - lo > 16) {
            int mid = (lo + hi) >>> 1;
            if (k[mid] < k[lo]) swap(k, v, mid, lo);
            if (k[hi] < k[lo]) swap(k, v, hi, lo);
            if (k[hi] < k[mid]) swap(k, v, hi, mid);
            long pivot = k[mid];
            int i = lo, j = hi;
            while (i <= j) {
                while (k[i] < pivot) i++;
                while (k[j] > pivot) j--;
                if (i <= j) { swap(k, v, i, j); i++; j--; }
            }
            if (j - lo < hi - i) { sort(k, v, lo, j); lo = i; } else { sort(k, v, i, hi); hi = j; }
        }
        for (int i = lo + 1; i <= hi; i++) {
            long kk = k[i], vv = v[i];
            int j = i - 1;
            while (j >= lo && k[j] > kk) { k[j + 1] = k[j]; v[j + 1] = v[j]; j--; }
            k[j + 1] = kk; v[j + 1] = vv;
        }
    }

    private static void swap(long[] k, long[] v, int a, int b) {
        long t = k[a]; k[a] = k[b]; k[b] = t;
        t = v[a]; v[a] = v[b]; v[b] = t;
    }
}
