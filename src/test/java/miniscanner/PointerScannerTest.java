package miniscanner;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.*;

class PointerScannerTest {
    static final Win.Module GAME = new Win.Module("game.exe", 0x400000, 0x1000);
    static final LongFunction<Win.Module> MODULE_AT = a -> a >= GAME.base() && a < GAME.end() ? GAME : null;
    static final Progress NONE = (done, total) -> {};

    /** (valor del pointer, dirección donde está guardado), ya ordenados por valor como los deja scan(). */
    static List<AddressExpr> chains(long target, int maxLevel, long maxOffset, long[]... pairs) {
        long[] vals = new long[pairs.length], addrs = new long[pairs.length];
        for (int i = 0; i < pairs.length; i++) { vals[i] = pairs[i][0]; addrs[i] = pairs[i][1]; }
        PointerScanner.sort(vals, addrs, 0, pairs.length - 1);
        return PointerScanner.findChains(vals, addrs, pairs.length, target, maxLevel, maxOffset, MODULE_AT, NONE);
    }

    static boolean has(List<AddressExpr> list, long baseOff, long... offsets) {
        return list.stream().anyMatch(e -> "game.exe".equals(e.module) && e.offset == baseOff && Arrays.equals(e.offsets, offsets));
    }

    @Test
    void encuentraCadenasDeUnoYDosNiveles() {
        long target = 0x5000;
        List<AddressExpr> r = chains(target, 3, 0x1000,
                new long[]{0x4FF0, 0x3000},   // heap: [0x3000] + 10 = objetivo
                new long[]{0x2F00, 0x400100}, // estático: [game.exe+100] + 100 = 0x3000
                new long[]{0x4FF8, 0x400200}, // estático directo: [game.exe+200] + 8 = objetivo
                new long[]{0x9000, 0x3100});  // ruido
        assertEquals(2, r.size());
        assertTrue(has(r, 0x200, 0x8));
        assertTrue(has(r, 0x100, 0x100, 0x10), "offsets del primero (cerca de la base) al último");
    }

    @Test
    void respetaOffsetYNivelMaximos() {
        long target = 0x5000;
        long[] heap = {0x4FF0, 0x3000}, stat = {0x2F00, 0x400100};
        assertTrue(chains(target, 1, 0x1000, heap, stat).isEmpty(), "con nivel 1 no llega al estático");
        assertTrue(chains(target, 3, 0x8, heap, stat).isEmpty(), "0x10 y 0x100 pasan del offset máximo");
    }

    @Test
    void ordenaYMueveLosPares() {
        Random rnd = new Random(1);
        int n = 10_000;
        long[] k = new long[n], v = new long[n];
        for (int i = 0; i < n; i++) { k[i] = rnd.nextInt(500); v[i] = k[i] * 31 + 7; }
        PointerScanner.sort(k, v, 0, n - 1);
        for (int i = 0; i < n; i++) {
            if (i > 0) assertTrue(k[i - 1] <= k[i]);
            assertEquals(k[i] * 31 + 7, v[i]);
        }
    }

    @Test
    void busquedasBinarias() {
        long[] a = {10, 20, 20, 30};
        assertEquals(0, PointerScanner.lowerBound(a, 4, 5));
        assertEquals(1, PointerScanner.lowerBound(a, 4, 20));
        assertEquals(4, PointerScanner.lowerBound(a, 4, 31));

        long[] rb = {0x1000, 0x5000}, re = {0x2000, 0x6000};
        assertTrue(PointerScanner.inRegions(rb, re, 0x1000));
        assertFalse(PointerScanner.inRegions(rb, re, 0x2000));
        assertTrue(PointerScanner.inRegions(rb, re, 0x5FFF));
        assertFalse(PointerScanner.inRegions(rb, re, 0x0FFF));
    }

    @Test
    void sePuedeCancelar() {
        long[] vals = {0x4FF0}, addrs = {0x3000};
        Progress cancel = (done, total) -> { throw new Progress.Cancelled(); };
        assertThrows(Progress.Cancelled.class,
                () -> PointerScanner.findChains(vals, addrs, 1, 0x5000, 3, 0x1000, MODULE_AT, cancel));
    }

    @Test
    void limiteDeMemoriaEsRazonable() {
        long limit = PointerScanner.pointerLimit();
        assertTrue(limit > 0 && limit <= PointerScanner.MAX_ARRAY);
    }
}
