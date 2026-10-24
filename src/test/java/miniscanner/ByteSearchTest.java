package miniscanner;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ByteSearchTest {
    static final long BASE = 0x20000000L;
    static final Progress NONE = (done, total) -> {};

    static void put(FakeMemory m, long off, byte[] b) { m.mem.write(off, b, 0, b.length); }

    @Test
    void parseaAob() {
        ByteSearch.Pattern p = ByteSearch.parseAob("89 46 ?? 8b 0D");
        assertArrayEquals(new boolean[]{true, true, false, true, true}, p.mask());
        assertEquals((byte) 0x8B, p.bytes()[3]);
        assertArrayEquals(p.mask(), ByteSearch.parseAob("8946??8B0D").mask());
        assertArrayEquals(p.mask(), ByteSearch.parseAob("89,46,*,8B,0D").mask());
        assertEquals(2, ByteSearch.parseAob("?? 90").firstFixed() + 1);
        assertThrows(IllegalArgumentException.class, () -> ByteSearch.parseAob(""));
        assertThrows(IllegalArgumentException.class, () -> ByteSearch.parseAob("?? ??"));
        assertThrows(IllegalArgumentException.class, () -> ByteSearch.parseAob("GG"));
    }

    @Test
    void encuentraAobConComodines() {
        FakeMemory m = new FakeMemory(BASE, 64 * 1024);
        put(m, 0x100, new byte[]{(byte) 0x89, 0x46, 0x11, (byte) 0x8B, 0x0D});
        put(m, 0x900, new byte[]{(byte) 0x89, 0x46, 0x22, (byte) 0x8B, 0x0D});
        put(m, 0xA00, new byte[]{(byte) 0x89, 0x46, 0x22, (byte) 0x8B, 0x0E}); // el último no coincide
        List<Long> r = ByteSearch.scan(m, ByteSearch.parseAob("89 46 ?? 8B 0D"), true, NONE);
        assertEquals(List.of(BASE + 0x100, BASE + 0x900), r);
    }

    @Test
    void encuentraLoQueCruzaElLimiteDeBloque() {
        FakeMemory m = new FakeMemory(BASE, ByteSearch.CHUNK * 2);
        put(m, ByteSearch.CHUNK - 2, new byte[]{1, 2, 3, 4});
        assertEquals(List.of(BASE + ByteSearch.CHUNK - 2), ByteSearch.scan(m, ByteSearch.parseAob("01 02 03 04"), true, NONE));
    }

    @Test
    void textoConYSinMayusculas() {
        FakeMemory m = new FakeMemory(BASE, 64 * 1024);
        put(m, 0x40, "Hola Mario".getBytes(StandardCharsets.UTF_8));
        put(m, 0x400, "MARIO".getBytes(StandardCharsets.UTF_16LE));
        assertEquals(List.of(BASE + 0x45), ByteSearch.scan(m, ByteSearch.text("Mario", StandardCharsets.UTF_8, false), true, NONE));
        assertEquals(List.of(), ByteSearch.scan(m, ByteSearch.text("mario", StandardCharsets.UTF_8, false), true, NONE));
        assertEquals(List.of(BASE + 0x45), ByteSearch.scan(m, ByteSearch.text("mario", StandardCharsets.UTF_8, true), true, NONE));
        assertEquals(List.of(BASE + 0x400), ByteSearch.scan(m, ByteSearch.text("mario", StandardCharsets.UTF_16LE, true), true, NONE));
    }

    @Test
    void parcheDejaLosComodines() {
        byte[] cur = {1, 2, 3, 4};
        assertArrayEquals(new byte[]{(byte) 0x90, 2, (byte) 0x90, 4}, ByteSearch.apply(ByteSearch.parseAob("90 ?? 90"), cur));
        assertEquals("01 FF", ByteSearch.hex(new byte[]{1, (byte) 0xFF}));
        assertEquals("Hi..", ByteSearch.preview(new byte[]{'H', 'i', 0, 7}, StandardCharsets.UTF_8));
    }

    @Test
    void escribeEnMemoriaDeSoloLectura() {
        // Una página de "código" (solo lectura y ejecución) en este mismo proceso
        Kernel32 k = Kernel32.INSTANCE;
        Pointer page = k.VirtualAllocEx(k.GetCurrentProcess(), null, new BaseTSD.SIZE_T(4096), 0x3000 /* COMMIT|RESERVE */, 0x20 /* PAGE_EXECUTE_READ */);
        assertNotNull(page);
        Win.Proc p = WinProcTest.openSelf();
        try {
            long a = Pointer.nativeValue(page);
            assertTrue(p.write(a, new byte[]{(byte) 0x90, (byte) 0xC3}));
            assertEquals((byte) 0x90, page.getByte(0));
            assertEquals((byte) 0xC3, page.getByte(1));
            // y se encuentra con «incluir código», pero no buscando solo en memoria escribible
            ByteSearch.Pattern pat = ByteSearch.parseAob("90 C3 00 00 00 00 00 00 00 00");
            assertTrue(ByteSearch.scan(p, pat, false, NONE).contains(a));
            assertFalse(ByteSearch.scan(p, pat, true, NONE).contains(a));
        } finally {
            p.close();
            k.VirtualFreeEx(k.GetCurrentProcess(), page, new BaseTSD.SIZE_T(0), 0x8000 /* MEM_RELEASE */);
        }
    }
}
