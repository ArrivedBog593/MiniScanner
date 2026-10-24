package miniscanner;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Tlhelp32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Todo lo que habla con Windows: procesos, módulos y memoria. */
final class Win {
    private Win() {}

    /** Se carga solo cuando se usa por primera vez. */
    private static final class K { static final Kernel32 K32 = Kernel32.INSTANCE; }
    static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;
    static final long BAD = Long.MIN_VALUE;
    static final int PROCESS_ACCESS = 0x0010 | 0x0020 | 0x0008 | 0x0400 | 0x0800; // VM_READ|VM_WRITE|VM_OPERATION|QUERY_INFO|SUSPEND_RESUME
    static final int MEM_COMMIT = 0x1000, MEM_IMAGE = 0x1000000, PAGE_NOACCESS = 0x01, PAGE_GUARD = 0x100;
    static final int WRITABLE = 0x04 | 0x08 | 0x40 | 0x80;
    static final int PAGE = 0x1000;

    /** Funciones no documentadas de ntdll para pausar/reanudar un proceso completo. */
    interface NtDll extends com.sun.jna.Library {
        int NtSuspendProcess(HANDLE h);
        int NtResumeProcess(HANDLE h);
    }

    private static final class NtHolder {
        static final NtDll NT = Native.load("ntdll", NtDll.class);
    }

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
        private HANDLE h;
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

        void close() { resume(); if (h != null) { K.K32.CloseHandle(h); h = null; } }

        /** Pausa todo el juego (para escanear valores que cambian muy rápido). */
        synchronized void suspend() {
            if (!suspended && h != null) { NtHolder.NT.NtSuspendProcess(h); suspended = true; }
        }

        synchronized void resume() {
            if (suspended && h != null) { NtHolder.NT.NtResumeProcess(h); suspended = false; }
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
            return out;
        }

        /** Lee hasta size bytes; si una parte no se puede leer, devuelve cuántos bytes seguidos sí se leyeron. */
        public int readInto(long addr, Memory mem, int size) {
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

        boolean write(long addr, byte[] data) {
            if (addr <= 0) return false;
            Memory mem = new Memory(data.length);
            mem.write(0, data, 0, data.length);
            return K.K32.WriteProcessMemory(h, new Pointer(addr), mem, data.length, new IntByReference());
        }
    }
}
