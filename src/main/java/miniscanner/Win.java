package miniscanner;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Tlhelp32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import javax.swing.SwingUtilities;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Todo lo que habla con Windows: procesos, módulos, memoria y teclas rápidas globales. */
final class Win {
    private Win() {}

    /** Se carga solo cuando se usa por primera vez. */
    private static final class K { static final Kernel32 K32 = Kernel32.INSTANCE; }
    static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;
    static final long BAD = Long.MIN_VALUE;
    static final int PROCESS_ACCESS = 0x0010 | 0x0020 | 0x0008 | 0x0400 | 0x0800; // VM_READ|VM_WRITE|VM_OPERATION|QUERY_INFO|SUSPEND_RESUME
    static final int MEM_COMMIT = 0x1000, PAGE_NOACCESS = 0x01, PAGE_GUARD = 0x100;
    /** Lo que devuelven las funciones de ntdll (NTSTATUS) cuando salen bien. */
    static final int STATUS_SUCCESS = 0;
    static final int WRITABLE = 0x04 | 0x08 | 0x40 | 0x80;
    static final int PAGE = 0x1000;
    static final int STILL_ACTIVE = 259;

    /** Funciones no documentadas de ntdll para pausar/reanudar un proceso completo. */
    interface NtDll extends com.sun.jna.Library {
        int NtSuspendProcess(HANDLE h);
        int NtResumeProcess(HANDLE h);
    }

    private static final class NtHolder {
        static final NtDll NT = Native.load("ntdll", NtDll.class);
    }

    /** Funciones de kernel32 que JNA no trae. */
    interface Kernel32Ex extends com.sun.jna.Library {
        boolean VirtualProtectEx(HANDLE h, Pointer addr, BaseTSD.SIZE_T size, int newProtect, IntByReference oldProtect);
        /** Devuelve un BOOL que no se usa: si falla, no hay nada que hacer (el byte ya está escrito). */
        void FlushInstructionCache(HANDLE h, Pointer addr, BaseTSD.SIZE_T size);
    }

    private static final class K2 { static final Kernel32Ex K = Native.load("kernel32", Kernel32Ex.class); }

    static final int PAGE_EXECUTE_READWRITE = 0x40;

    record ProcInfo(String name, int pid) {}
    record Region(long base, long size) { long end() { return base + size; } }
    record Module(String name, long base, long size) { long end() { return base + size; } }

    static List<ProcInfo> listProcesses() {
        List<ProcInfo> out = new ArrayList<>();
        HANDLE snap = K.K32.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, new WinDef.DWORD(0));
        if (snap == null || WinNT.INVALID_HANDLE_VALUE.equals(snap)) return out;
        Tlhelp32.PROCESSENTRY32.ByReference e = new Tlhelp32.PROCESSENTRY32.ByReference();
        if (K.K32.Process32First(snap, e)) {
            do out.add(new ProcInfo(Native.toString(e.szExeFile), e.th32ProcessID.intValue()));
            while (K.K32.Process32Next(snap, e));
        }
        K.K32.CloseHandle(snap);
        out.sort(Comparator.comparing(p -> p.name().toLowerCase()));
        return out;
    }

    /** Lo mínimo que necesita el escáner para leer memoria (permite probarlo con memoria simulada). */
    interface MemorySource {
        List<Region> regions(boolean writableOnly);
        int readInto(long addr, Memory mem, int size);
        byte[] read(long addr, int size);
    }

    static final class Proc implements MemorySource {
        final ProcInfo info;
        final int ptrSize;
        /**
         * Lo usan a la vez el escaneo, el congelado, las teclas rápidas y la ventana. Cada uso toma el candado
         * compartido, y close() toma el exclusivo: así nadie usa un handle ya cerrado (que Windows podría haber
         * reutilizado para otra cosa).
         */
        private final ReadWriteLock lock = new ReentrantReadWriteLock();
        private HANDLE h; // protegido por lock; null = cerrado
        volatile List<Module> modules = List.of();

        Proc(ProcInfo info) {
            this.info = info;
            h = K.K32.OpenProcess(PROCESS_ACCESS, false, info.pid());
            if (h == null) throw new IllegalStateException("No se pudo abrir el proceso (error "
                    + K.K32.GetLastError() + ").\nPrueba ejecutar como administrador.");
            IntByReference wow = new IntByReference();
            ptrSize = K.K32.IsWow64Process(h, wow) && wow.getValue() != 0 ? 4 : 8;
            refreshModules();
        }

        volatile boolean suspended;

        /**
         * Espera a que terminen las lecturas/escrituras en curso, reanuda el juego si estaba pausado y cierra.
         * Es synchronized como suspend()/resume(): siempre se toma primero el monitor y luego el candado.
         */
        synchronized void close() {
            lock.writeLock().lock();
            try {
                resume();
                if (h != null) { K.K32.CloseHandle(h); h = null; }
            } finally {
                lock.writeLock().unlock();
            }
        }

        /** false cuando el juego ya se cerró. */
        boolean isAlive() {
            lock.readLock().lock();
            try {
                IntByReference code = new IntByReference();
                return h != null && K.K32.GetExitCodeProcess(h, code) && code.getValue() == STILL_ACTIVE;
            } finally {
                lock.readLock().unlock();
            }
        }

        /**
         * Pausa todo el juego (para escanear valores que cambian muy rápido).
         * Devuelve false si Windows no lo permitió (p. ej. un anti-cheat o un proceso protegido).
         */
        synchronized boolean suspend() {
            lock.readLock().lock();
            try {
                if (suspended) return true;
                if (h == null || NtHolder.NT.NtSuspendProcess(h) != STATUS_SUCCESS) return false;
                suspended = true;
                return true;
            } finally {
                lock.readLock().unlock();
            }
        }

        synchronized void resume() {
            lock.readLock().lock(); // close() ya tiene el exclusivo y puede tomarlo igual
            try {
                // Aunque falle (el juego ya se cerró), deja de contar como pausado: no hay nada más que hacer
                if (suspended && h != null && NtHolder.NT.NtResumeProcess(h) != STATUS_SUCCESS)
                    System.err.println("MiniScanner: no se pudo reanudar " + info.name());
                suspended = false;
            } finally {
                lock.readLock().unlock();
            }
        }

        void refreshModules() {
            List<Module> out = new ArrayList<>();
            WinDef.DWORD flags = new WinDef.DWORD(Tlhelp32.TH32CS_SNAPMODULE.intValue() | Tlhelp32.TH32CS_SNAPMODULE32.intValue());
            for (int attempt = 0; attempt < 3 && out.isEmpty(); attempt++) {
                HANDLE snap = K.K32.CreateToolhelp32Snapshot(flags, new WinDef.DWORD(info.pid()));
                if (snap == null || WinNT.INVALID_HANDLE_VALUE.equals(snap)) continue;
                Tlhelp32.MODULEENTRY32W e = new Tlhelp32.MODULEENTRY32W();
                if (K.K32.Module32FirstW(snap, e)) {
                    do out.add(new Module(Native.toString(e.szModule), Pointer.nativeValue(e.modBaseAddr), e.modBaseSize.longValue()));
                    while (K.K32.Module32NextW(snap, e));
                }
                K.K32.CloseHandle(snap);
            }
            out.sort(Comparator.comparingLong(Module::base));
            modules = List.copyOf(out);
        }

        Module moduleByName(String name) {
            for (Module m : modules) if (m.name().equalsIgnoreCase(name)) return m;
            return null;
        }

        /** Módulo (exe/dll) que contiene la dirección, o null si está en memoria dinámica. */
        Module moduleAt(long addr) {
            List<Module> ms = modules;
            int lo = 0, hi = ms.size() - 1, found = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (ms.get(mid).base() <= addr) { found = mid; lo = mid + 1; } else hi = mid - 1;
            }
            if (found < 0) return null;
            Module m = ms.get(found);
            return addr < m.end() ? m : null;
        }

        public List<Region> regions(boolean writableOnly) {
            List<Region> out = new ArrayList<>();
            lock.readLock().lock();
            try {
                if (h == null) return out;
                WinNT.MEMORY_BASIC_INFORMATION mbi = new WinNT.MEMORY_BASIC_INFORMATION();
                long addr = 0;
                while (K.K32.VirtualQueryEx(h, new Pointer(addr), mbi, new BaseTSD.SIZE_T(mbi.size())).longValue() != 0) {
                    long base = mbi.baseAddress == null ? 0 : Pointer.nativeValue(mbi.baseAddress);
                    long size = mbi.regionSize.longValue();
                    int prot = mbi.protect.intValue();
                    boolean ok = mbi.state.intValue() == MEM_COMMIT && (prot & (PAGE_GUARD | PAGE_NOACCESS)) == 0
                            && (!writableOnly || (prot & WRITABLE) != 0);
                    if (ok) out.add(new Region(base, size));
                    if (size <= 0) break;
                    addr = base + size;
                }
            } finally {
                lock.readLock().unlock();
            }
            return out;
        }

        /** Lee hasta size bytes; si una parte no se puede leer, devuelve cuántos bytes seguidos sí se leyeron. */
        public int readInto(long addr, Memory mem, int size) {
            lock.readLock().lock();
            try {
                if (h == null) return 0;
                IntByReference n = new IntByReference();
                if (K.K32.ReadProcessMemory(h, new Pointer(addr), mem, size, n)) return n.getValue();
                // Falló (p. ej. el juego liberó una página después de VirtualQueryEx): se lee página por página
                int done = 0;
                while (done < size) {
                    int len = (int) Math.min(size - done, PAGE - ((addr + done) & (PAGE - 1)));
                    if (!K.K32.ReadProcessMemory(h, new Pointer(addr + done), mem.share(done), len, n) || n.getValue() != len) break;
                    done += len;
                }
                return done;
            } finally {
                lock.readLock().unlock();
            }
        }

        public byte[] read(long addr, int size) {
            if (addr <= 0) return null;
            Memory mem = new Memory(size);
            int got = readInto(addr, mem, size);
            return got == size ? mem.getByteArray(0, got) : null;
        }

        long readPtr(long addr) {
            byte[] b = read(addr, ptrSize);
            if (b == null) return BAD;
            ByteBuffer bb = ByteBuffer.wrap(b).order(LE);
            return ptrSize == 8 ? bb.getLong() : bb.getInt() & 0xFFFFFFFFL;
        }

        /** Escribe en el juego; también en código o memoria de solo lectura (permite escribir un momento). */
        boolean write(long addr, byte[] data) {
            if (addr <= 0 || data.length == 0) return false;
            Memory mem = new Memory(data.length);
            mem.write(0, data, 0, data.length);
            lock.readLock().lock();
            try {
                if (h == null) return false;
                Pointer p = new Pointer(addr);
                if (K.K32.WriteProcessMemory(h, p, mem, data.length, new IntByReference())) return true;
                // Código (p. ej. al parchear un AOB) o datos de solo lectura: se cambia la protección y se restaura
                BaseTSD.SIZE_T size = new BaseTSD.SIZE_T(data.length);
                IntByReference old = new IntByReference();
                if (!K2.K.VirtualProtectEx(h, p, size, PAGE_EXECUTE_READWRITE, old)) return false;
                try {
                    boolean ok = K.K32.WriteProcessMemory(h, p, mem, data.length, new IntByReference());
                    if (ok) K2.K.FlushInstructionCache(h, p, size); // por si era código que ya está en caché
                    return ok;
                } finally {
                    K2.K.VirtualProtectEx(h, p, size, old.getValue(), new IntByReference());
                }
            } finally {
                lock.readLock().unlock();
            }
        }
    }

    /**
     * Teclas rápidas globales (RegisterHotKey). Windows avisa de cada pulsación al hilo que registró la tecla,
     * así que todo pasa en un hilo propio con su bucle de mensajes; los avisos se entregan en el hilo de Swing.
     */
    static final class Hotkeys {
        private static final int WM_HOTKEY = 0x0312, WM_APP = 0x8000, MOD_NOREPEAT = 0x4000;

        private final IntConsumer onPress;
        private final Consumer<Set<Integer>> onFailed;
        private final AtomicReference<Set<Integer>> pending = new AtomicReference<>();
        private final CountDownLatch ready = new CountDownLatch(1);
        private volatile int threadId;

        /**
         * @param onPress  recibe la combinación pulsada ({@link Hotkey#combo()})
         * @param onFailed recibe las combinaciones que no se pudieron registrar (otro programa ya las usa)
         */
        Hotkeys(IntConsumer onPress, Consumer<Set<Integer>> onFailed) {
            this.onPress = onPress;
            this.onFailed = onFailed;
            Thread t = new Thread(this::loop, "teclas rápidas");
            t.setDaemon(true);
            t.start();
            try { ready.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        /** Deja registradas exactamente estas combinaciones (suelta las demás). */
        void set(Set<Integer> combos) {
            pending.set(Set.copyOf(combos));
            User32.INSTANCE.PostThreadMessage(threadId, WM_APP, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
        }

        private void loop() {
            threadId = K.K32.GetCurrentThreadId();
            WinUser.MSG msg = new WinUser.MSG();
            User32.INSTANCE.PeekMessage(msg, null, 0, 0, 0); // crea la cola de mensajes antes de recibir set()
            ready.countDown();
            Set<Integer> registered = new HashSet<>();
            while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                if (msg.message == WM_HOTKEY) {
                    int combo = msg.wParam.intValue();
                    SwingUtilities.invokeLater(() -> onPress.accept(combo));
                } else if (msg.message == WM_APP) {
                    Set<Integer> want = pending.getAndSet(null);
                    if (want == null) continue;
                    for (int c : registered) User32.INSTANCE.UnregisterHotKey(null, c);
                    registered.clear();
                    Set<Integer> failed = new HashSet<>();
                    for (int c : want) {
                        // El ID es la propia combinación: (mods << 8 | vk) siempre cabe en el rango 0..0xBFFF
                        if (User32.INSTANCE.RegisterHotKey(null, c, (c >> 8) | MOD_NOREPEAT, c & 0xFF)) registered.add(c);
                        else failed.add(c);
                    }
                    if (!failed.isEmpty()) SwingUtilities.invokeLater(() -> onFailed.accept(failed));
                }
            }
        }
    }
}
