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
    static final int MAX_RESULTS = 1_000_000, CHUNK = 4 * 1024 * 1024;
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

    // ------------------------------------------------------------ escaneo
    /**
     * @param phase recibe textos de fase para mostrar ("Leyendo memoria", etc.)
     */
    static Result scan(Win.Proc proc, long target, int maxLevel, long maxOffset,
                       Progress p, java.util.function.Consumer<String> phase) throws Exception {
        proc.refreshModules();
        // 1) Mapa de todos los pointers de la memoria escribible: (valor → dirección donde está guardado)
        phase.accept("Leyendo memoria");
        List<Win.Region> regs = proc.regions(true);
        if (regs.isEmpty()) return new Result(new ArrayList<>(), false, false);
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
        // Cada nodo intermedio usa unos 64 bytes (dirección, valor, conjunto de vistos y listas con margen)
        Runtime rt = Runtime.getRuntime();
        long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory()) - RESERVE;
        long nodeLimit = Math.max(100_000, Math.min(free / 64, MAX_ARRAY / 4));
        Result res = findChains(vals, addrs, n, target, maxLevel, maxOffset, proc::moduleAt, p, nodeLimit, MAX_RESULTS);
        p.update(1, 1);
        // Las cadenas más cortas primero: suelen ser las más estables
        res.chains().sort(Comparator.comparingInt((AddressExpr e) -> e.offsets.length).thenComparingLong(e -> e.offset));
        return res;
    }

    /**
     * Resultado de buscar cadenas. Los avisos dicen si se llegó a un tope: en ese caso la cadena buena puede faltar.
     * @param nodesCapped   no cupieron todos los pasos intermedios en la RAM
     * @param resultsCapped había más cadenas de las que caben y se guardaron las más cortas
     */
    record Result(List<AddressExpr> chains, boolean nodesCapped, boolean resultsCapped) {}

    /** Para guardar en un solo long el valor de un pointer (como mucho 48 bits en Windows) y su nivel. */
    private static final long VAL_MASK = (1L << 56) - 1;

    /**
     * Busca todas las cadenas que van de una dirección estática (dentro de un exe/dll) hasta el objetivo.
     * <ol>
     *  <li>Hacia atrás desde el objetivo: ¿qué pointers apuntan cerca? ¿y cerca de esos?... Así se sabe a cuántos
     *      pasos del objetivo está cada dirección intermedia ("nodo"), y qué direcciones estáticas apuntan a cuáles.</li>
     *  <li>Hacia adelante desde cada dirección estática: se recorren TODOS los caminos hasta el objetivo dentro del
     *      nivel máximo. No basta con el primero que se encontró: si dos campos de una estructura apuntan cerca del
     *      objetivo, uno puede ser estable y el otro temporal, y hay que conservar los dos para filtrar después.</li>
     * </ol>
     * Si hay más de maxResults cadenas se guardan las más cortas, que son las que más suelen sobrevivir: por eso la
     * fase 2 va por largo (primero todas las de 1 offset, luego todas las de 2...) y no base por base.
     * @param nodeLimit cuántos nodos caben en la RAM
     */
    static Result findChains(long[] vals, long[] addrs, int total, long target, int maxLevel, long maxOffset,
                             java.util.function.LongFunction<Win.Module> moduleAt, Progress p, long nodeLimit, int maxResults) {
        // Fase 1 en su propio método: sus estructuras temporales (como el conjunto de vistos) se liberan al volver
        Backward bw = backward(vals, addrs, total, target, maxLevel, maxOffset, moduleAt, p, nodeLimit);
        long[] na = bw.nodeAddr(), np = bw.nodePacked();
        LongList statAddr = bw.statAddr(), statTo = bw.statTo(), statOff = bw.statOff();

        // ---- 2) hacia adelante, por largo
        Forward f = new Forward(na, np, target, maxOffset, maxLevel, moduleAt, maxResults);
        long steps = (long) maxLevel * Math.max(1, statAddr.n);
        for (int len = 1; len <= maxLevel && !f.capped; len++) {
            for (int e = 0; e < statAddr.n && !f.capped; e++) {
                long s = statAddr.a[e], to = statTo.a[e];
                f.path[0] = statOff.a[e];
                Win.Module m = moduleAt.apply(s);
                if (to == target) {
                    if (len == 1) f.emit(m, s, 1);
                } else if (len >= 2) {
                    int j = Arrays.binarySearch(na, to);
                    if (j >= 0 && (int) (np[j] >>> 56) <= len - 1) f.walk(m, s, j, len - 1, 1);
                }
                if ((e & 255) == 0)
                    p.update(maxLevel * 1000L + ((len - 1L) * statAddr.n + e) * 1000L / steps, (maxLevel + 1) * 1000L);
            }
        }
        return new Result(f.results, bw.nodesCapped(), f.capped);
    }

    /**
     * Lo que deja la fase 1: los nodos ordenados por dirección (con su valor | nivel << 56) y las direcciones
     * estáticas que apuntan cerca de cada nodo (statTo) o del objetivo, con su offset.
     */
    private record Backward(long[] nodeAddr, long[] nodePacked, LongList statAddr, LongList statTo, LongList statOff,
                            boolean nodesCapped) {}

    /** Fase 1 de findChains: hacia atrás desde el objetivo, nivel por nivel. */
    private static Backward backward(long[] vals, long[] addrs, int total, long target, int maxLevel, long maxOffset,
                                     java.util.function.LongFunction<Win.Module> moduleAt, Progress p, long nodeLimit) {
        LongList nodeAddr = new LongList(), nodePacked = new LongList();
        LongList statAddr = new LongList(), statTo = new LongList(), statOff = new LongList();
        LongSet seen = new LongSet();
        seen.add(target);
        LongList frontier = new LongList();
        frontier.add(target);
        boolean nodesCapped = false;
        for (int level = 1; level <= maxLevel && frontier.n > 0; level++) {
            LongList next = new LongList();
            for (int ci = 0; ci < frontier.n; ci++) {
                long b = frontier.a[ci];
                for (int k = lowerBound(vals, total, b - maxOffset); k < total && vals[k] <= b; k++) {
                    long a = addrs[k];
                    if (moduleAt.apply(a) != null) {
                        // Dirección estática que apunta cerca de b: empieza una (o varias) cadenas
                        statAddr.add(a); statTo.add(b); statOff.add(b - vals[k]);
                    } else if (level < maxLevel && !seen.contains(a)) {
                        if (nodeAddr.n >= nodeLimit) { nodesCapped = true; continue; }
                        seen.add(a);
                        nodeAddr.add(a);
                        nodePacked.add(vals[k] | (long) level << 56);
                        next.add(a);
                    }
                }
                if ((ci & 1023) == 0) p.update((long) (level - 1) * 1000 + ci * 1000L / frontier.n, (maxLevel + 1) * 1000L);
            }
            frontier = next;
        }
        int n = nodeAddr.n;
        long[] na = Arrays.copyOf(nodeAddr.a, n), np = Arrays.copyOf(nodePacked.a, n);
        sort(na, np, 0, n - 1);
        return new Backward(na, np, statAddr, statTo, statOff, nodesCapped);
    }

    /** La segunda fase de findChains: recorre los caminos desde una dirección estática hasta el objetivo. */
    private static final class Forward {
        final long[] na, np;
        final long target, maxOffset;
        final java.util.function.LongFunction<Win.Module> moduleAt;
        final long[] path;
        final List<AddressExpr> results = new ArrayList<>();
        final int maxResults;
        boolean capped;

        Forward(long[] na, long[] np, long target, long maxOffset, int maxLevel,
                java.util.function.LongFunction<Win.Module> moduleAt, int maxResults) {
            this.na = na; this.np = np; this.target = target; this.maxOffset = maxOffset; this.moduleAt = moduleAt;
            this.maxResults = maxResults;
            path = new long[maxLevel];
        }

        /**
         * Sigue desde el nodo j, cuando ya hay len offsets en path y faltan exactamente remaining (contando el
         * último, el que llega al objetivo). Solo se entra en nodos que pueden llegar con lo que falta.
         */
        void walk(Win.Module m, long base, int j, int remaining, int len) {
            if (capped) return;
            long v = np[j] & VAL_MASK;
            if (remaining == 1) {
                if (target >= v && target - v <= maxOffset) {
                    path[len] = target - v;
                    emit(m, base, len + 1);
                }
                return;
            }
            for (int c = lowerBound(na, na.length, v); c < na.length && na[c] - v <= maxOffset && !capped; c++) {
                if ((int) (np[c] >>> 56) > remaining - 1) continue;
                path[len] = na[c] - v;
                walk(m, base, c, remaining - 1, len + 1);
            }
        }

        void emit(Win.Module m, long base, int len) {
            results.add(new AddressExpr(m.name(), base - m.base(), Arrays.copyOf(path, len)));
            if (results.size() >= maxResults) capped = true;
        }
    }

    /** Conjunto de direcciones (long distinto de 0) sin cajas de Long: con millones de nodos, ahorra mucha RAM. */
    static final class LongSet {
        private long[] t = new long[1024];
        private int n;

        boolean contains(long v) {
            for (int i = slot(v); ; i = (i + 1) & (t.length - 1)) {
                if (t[i] == v) return true;
                if (t[i] == 0) return false;
            }
        }

        void add(long v) {
            if (n * 2 >= t.length) grow();
            int i = slot(v);
            while (t[i] != 0 && t[i] != v) i = (i + 1) & (t.length - 1);
            if (t[i] == 0) { t[i] = v; n++; }
        }

        private int slot(long v) {
            long h = v * 0x9E3779B97F4A7C15L;
            return Long.hashCode(h) & (t.length - 1);
        }

        private void grow() {
            long[] old = t;
            t = new long[old.length * 2];
            n = 0;
            for (long v : old) if (v != 0) add(v);
        }
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
