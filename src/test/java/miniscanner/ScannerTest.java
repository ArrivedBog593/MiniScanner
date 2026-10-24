package miniscanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScannerTest {
    static final long BASE = 0x10000000L;
    static final Progress NONE = (done, total) -> {};

    static long rel(Scanner s) { return s.firstAddresses(1)[0] - BASE; }

    @Test
    void exactoEncuentraTodasLasCoincidencias() throws Exception {
        FakeMemory m = new FakeMemory(BASE, 64 * 1024);
        m.mem.setInt(0x40, 50);
        m.mem.setInt(0x80, 50);
        m.mem.setInt(0xC0, 51);
        try (Scanner s = new Scanner(m, ValueType.INT4)) {
            s.firstScan(Scanner.Mode.EXACT, 50, 0, NONE);
            assertFalse(s.isSnapshot());
            assertArrayEquals(new long[]{BASE + 0x40, BASE + 0x80}, s.firstAddresses(10));
        }
    }

    @Test
    void siguientesEscaneosEnLista() throws Exception {
        FakeMemory m = new FakeMemory(BASE, 64 * 1024);
        m.mem.setInt(0x40, 50);
        m.mem.setInt(0x80, 50);
        try (Scanner s = new Scanner(m, ValueType.INT4)) {
            s.firstScan(Scanner.Mode.EXACT, 50, 0, NONE);
            m.mem.setInt(0x40, 60);
            s.nextScan(Scanner.Mode.INC_BY, 10, 0, NONE);
            assertEquals(1, s.count());
            assertEquals(0x40, rel(s));

            m.mem.setInt(0x40, 55);
            s.nextScan(Scanner.Mode.DEC, 0, 0, NONE);
            assertEquals(1, s.count());

            s.nextScan(Scanner.Mode.UNCHANGED, 0, 0, NONE);
            assertEquals(1, s.count());
            m.mem.setInt(0x40, 70);
            s.nextScan(Scanner.Mode.UNCHANGED, 0, 0, NONE);
            assertEquals(0, s.count());

            assertTrue(s.canUndo());
            s.undo();
            assertEquals(1, s.count());
            assertFalse(s.canUndo());
        }
    }

    @Test
    void floatUsaTolerancia() throws Exception {
        FakeMemory m = new FakeMemory(BASE, 64 * 1024);
        m.mem.setFloat(0x10, 1.52f);
        m.mem.setFloat(0x20, 1.56f);
        try (Scanner s = new Scanner(m, ValueType.FLOAT)) {
            s.tol = MiniScanner.tolFor("1.5"); // ±0.05
            s.firstScan(Scanner.Mode.EXACT, 1.5, 0, NONE);
            assertEquals(1, s.count());
            assertEquals(0x10, rel(s));
        }
    }

    @Test
    void valorQueCruzaElLimiteDeBloque() throws Exception {
        FakeMemory m = new FakeMemory(BASE, Scanner.CHUNK * 2);
        m.mem.setLong(Scanner.CHUNK - 4, 123456789L);
        try (Scanner s = new Scanner(m, ValueType.INT8)) {
            s.firstScan(Scanner.Mode.EXACT, 123456789, 0, NONE);
            assertEquals(1, s.count());
            assertEquals(Scanner.CHUNK - 4, rel(s));
        }
        // Mismo valor por la instantánea: "Entre" es exacto en enteros y no ve las lecturas desalineadas
        try (Scanner s = new Scanner(m, ValueType.INT8)) {
            s.firstScan(Scanner.Mode.BETWEEN, 123456789, 123456789, NONE);
            assertEquals(1, s.count());
            assertEquals(Scanner.CHUNK - 4, rel(s));
        }
    }

    @Test
    void valorComunPasaAInstantanea() throws Exception {
        // 16 MB + 4 KB de ceros: más de SPARSE_LIMIT resultados para «Valor exacto 0»
        FakeMemory m = new FakeMemory(BASE, Scanner.CHUNK + 4096);
        try (Scanner s = new Scanner(m, ValueType.INT4)) {
            s.firstScan(Scanner.Mode.EXACT, 0, 0, NONE);
            assertTrue(s.isSnapshot());
            long all = (Scanner.CHUNK + 4096) / 4;
            assertEquals(all, s.count());

            m.mem.setInt(0x100, 7);
            s.nextScan(Scanner.Mode.CHANGED, 0, 0, NONE);
            assertFalse(s.isSnapshot(), "con pocos resultados vuelve a lista");
            assertEquals(1, s.count());
            assertEquals(0x100, rel(s));

            s.undo();
            assertTrue(s.isSnapshot());
            assertEquals(all, s.count());
        }
    }

    @Test
    void valorDesconocidoYLuegoAumento() throws Exception {
        FakeMemory m = new FakeMemory(BASE, 64 * 1024);
        try (Scanner s = new Scanner(m, ValueType.INT4)) {
            s.firstScan(Scanner.Mode.UNKNOWN, 0, 0, NONE);
            assertFalse(s.isSnapshot(), "16 384 resultados caben en lista: se convierte enseguida");
            assertEquals(64 * 1024 / 4, s.count());
            m.mem.setInt(0x200, 3);
            m.mem.setInt(0x300, -3);
            s.nextScan(Scanner.Mode.INC, 0, 0, NONE);
            assertEquals(1, s.count());
            assertEquals(0x200, rel(s));
        }
    }

    @Test
    void ignoraLoQueNoSePuedeLeer() throws Exception {
        FakeMemory m = new FakeMemory(BASE, 64 * 1024);
        m.readable = 4096;
        m.mem.setInt(0x100, 42);
        m.mem.setInt(0x2000, 42); // fuera de lo legible
        try (Scanner s = new Scanner(m, ValueType.INT4)) {
            s.firstScan(Scanner.Mode.EXACT, 42, 0, NONE);
            assertEquals(1, s.count());
            assertEquals(0x100, rel(s));
        }
    }

    @Test
    void enterosSinSignoCoincidenConLoLeido() throws Exception {
        FakeMemory m = new FakeMemory(BASE, 64 * 1024);
        m.mem.setInt(0x10, -1);       // FFFFFFFF
        m.mem.setByte(0x21, (byte) -1); // FF
        try (Scanner s = new Scanner(m, ValueType.INT4)) {
            s.firstScan(Scanner.Mode.BETWEEN, ValueType.INT4.parse("4294967295"), ValueType.INT4.parse("4294967295"), NONE);
            assertEquals(1, s.count());
            assertEquals(0x10, rel(s));
        }
        try (Scanner s = new Scanner(m, ValueType.BYTE)) {
            s.firstScan(Scanner.Mode.GT, ValueType.BYTE.parse("-2"), 0, NONE); // > 254
            // FF en 0x10..0x13 y en 0x21
            assertEquals(5, s.count());
        }
    }
}
