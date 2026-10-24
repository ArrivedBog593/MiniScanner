package miniscanner;

import com.sun.jna.Memory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Pruebas contra un proceso real: este mismo (la JVM de los tests). */
class WinProcTest {
    static Win.Proc openSelf() {
        int pid = (int) ProcessHandle.current().pid();
        return new Win.Proc(new Win.ProcInfo("java.exe", pid));
    }

    @Test
    void leeYEscribeSuPropiaMemoria() {
        Memory m = new Memory(16);
        m.setInt(0, 12345);
        long addr = com.sun.jna.Pointer.nativeValue(m);
        Win.Proc p = openSelf();
        try {
            assertTrue(p.isAlive());
            assertEquals(12345, ValueType.INT4.decode(p.read(addr, 4)));
            assertTrue(p.write(addr, ValueType.INT4.encode(777)));
            assertEquals(777, m.getInt(0));
            assertFalse(p.regions(true).isEmpty());
        } finally {
            p.close();
        }
    }

    @Test
    void despuesDeCerrarNoUsaElHandle() {
        Memory m = new Memory(16);
        long addr = com.sun.jna.Pointer.nativeValue(m);
        Win.Proc p = openSelf();
        p.close();
        p.close(); // cerrar dos veces no pasa nada
        assertFalse(p.isAlive());
        assertNull(p.read(addr, 4));
        assertFalse(p.write(addr, new byte[4]));
        assertTrue(p.regions(true).isEmpty());
    }

    @Test
    void cerrarMientrasOtrosHilosLeen() throws Exception {
        Memory m = new Memory(64);
        long addr = com.sun.jna.Pointer.nativeValue(m);
        for (int round = 0; round < 20; round++) {
            Win.Proc p = openSelf();
            AtomicReference<Throwable> err = new AtomicReference<>();
            List<Thread> ts = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                Thread th = new Thread(() -> {
                    try {
                        for (int i = 0; i < 2000; i++) { p.read(addr, 8); p.write(addr, new byte[8]); p.isAlive(); }
                    } catch (Throwable e) { err.set(e); }
                });
                ts.add(th);
                th.start();
            }
            Thread.sleep(2);
            // (sin p.suspend(): pausaría la propia JVM de los tests, incluido este hilo)
            p.close();
            for (Thread th : ts) th.join();
            assertNull(err.get());
            assertNull(p.read(addr, 8));
        }
    }
}
