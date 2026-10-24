package miniscanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CtFileTest {
    @TempDir Path dir;

    File write(String name, String xml) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, xml, StandardCharsets.UTF_8);
        return p.toFile();
    }

    @Test
    void cargaTablaDeCheatEngine() throws Exception {
        File f = write("juego.CT", """
                <?xml version="1.0" encoding="utf-8"?>
                <CheatTable CheatEngineTableVersion="45">
                  <CheatEntries>
                    <CheatEntry>
                      <ID>0</ID><Description>"Dinero"</Description><VariableType>4 Bytes</VariableType>
                      <Address>"game.exe"+00123ABC</Address>
                      <Offsets><Offset>10</Offset><Offset>2C</Offset></Offsets>
                    </CheatEntry>
                    <CheatEntry>
                      <ID>1</ID><Description>"Script"</Description><VariableType>Auto Assembler Script</VariableType>
                      <AssemblerScript>[ENABLE]</AssemblerScript>
                    </CheatEntry>
                    <CheatEntry>
                      <ID>2</ID><Description>"Jugador"</Description><GroupHeader>1</GroupHeader>
                      <CheatEntries>
                        <CheatEntry><ID>3</ID><Description>"Vida"</Description><VariableType>Float</VariableType><Address>7FF600001000</Address></CheatEntry>
                        <CheatEntry><ID>4</ID><Description>"Nombre"</Description><VariableType>String</VariableType><Address>7FF600002000</Address></CheatEntry>
                      </CheatEntries>
                    </CheatEntry>
                  </CheatEntries>
                </CheatTable>
                """);
        CtFile.LoadResult r = CtFile.load(f);
        assertEquals(2, r.skipped(), "el script y el String se omiten");
        assertEquals(2, r.entries().size());

        CheatEntry dinero = r.entries().get(0);
        assertEquals("Dinero", dinero.description);
        assertEquals(ValueType.INT4, dinero.type);
        assertEquals("game.exe", dinero.address.module);
        assertEquals(0x123ABC, dinero.address.offset);
        // En el .CT van del último al primero
        assertArrayEquals(new long[]{0x2C, 0x10}, dinero.address.offsets);

        CheatEntry vida = r.entries().get(1);
        assertEquals("Jugador / Vida", vida.description);
        assertEquals(ValueType.FLOAT, vida.type);
        assertNull(vida.address.module);
        assertEquals(0x7FF600001000L, vida.address.offset);
    }

    @Test
    void guardarYCargarIdaYVuelta() throws Exception {
        List<CheatEntry> in = List.of(
                new CheatEntry("Dinero <&>", new AddressExpr("Juego.exe", 0x1234, new long[]{0x10, 0x2C, 0x8}), ValueType.INT4),
                new CheatEntry("Vida", new AddressExpr(null, 0x7FF6A1B2C3D0L, new long[0]), ValueType.DOUBLE),
                new CheatEntry("Balas", new AddressExpr("Juego.exe", 0x20, new long[0]), ValueType.INT2));
        in.get(0).hotkey = new Hotkey(Hotkey.MOD_CONTROL, 0x70, Hotkey.Action.TOGGLE, null);
        in.get(2).hotkey = new Hotkey(0, '5', Hotkey.Action.SET, "999");
        File f = dir.resolve("salida.CT").toFile();
        CtFile.save(f, in);
        CtFile.LoadResult r = CtFile.load(f);
        assertEquals(0, r.skipped());
        assertEquals(in.size(), r.entries().size());
        for (int i = 0; i < in.size(); i++) {
            CheatEntry a = in.get(i), b = r.entries().get(i);
            assertEquals(a.description, b.description);
            assertEquals(a.type, b.type);
            assertEquals(a.address.module, b.address.module);
            assertEquals(a.address.offset, b.address.offset);
            assertArrayEquals(a.address.offsets, b.address.offsets);
            assertEquals(a.hotkey, b.hotkey);
        }
    }

    @Test
    void cargaTeclasRapidasDeCheatEngine() throws Exception {
        File f = write("teclas.CT", """
                <CheatTable>
                  <CheatEntries>
                    <CheatEntry>
                      <ID>0</ID><Description>"Vida"</Description><VariableType>4 Bytes</VariableType><Address>game.exe+10</Address>
                      <Hotkeys>
                        <Hotkey><Action>Increase Value</Action><Keys><Key>107</Key></Keys><Value>1</Value><ID>0</ID></Hotkey>
                        <Hotkey><Action>Set Value</Action><Keys><Key>17</Key><Key>112</Key></Keys><Value>999</Value><ID>1</ID></Hotkey>
                      </Hotkeys>
                    </CheatEntry>
                    <CheatEntry>
                      <ID>1</ID><Description>"Sin tecla"</Description><VariableType>Float</VariableType><Address>game.exe+20</Address>
                    </CheatEntry>
                  </CheatEntries>
                </CheatTable>
                """);
        CtFile.LoadResult r = CtFile.load(f);
        // «Increase Value» no se soporta: se usa la siguiente
        assertEquals(new Hotkey(Hotkey.MOD_CONTROL, 0x70, Hotkey.Action.SET, "999"), r.entries().get(0).hotkey);
        assertNull(r.entries().get(1).hotkey);
    }

    @Test
    void rechazaDoctype() throws Exception {
        File f = write("xxe.CT", """
                <?xml version="1.0"?>
                <!DOCTYPE CheatTable [<!ENTITY x SYSTEM "file:///C:/Windows/win.ini">]>
                <CheatTable><CheatEntries/></CheatTable>
                """);
        assertThrows(Exception.class, () -> CtFile.load(f));
    }

    @Test
    void rechazaOtroXml() throws Exception {
        File f = write("otro.CT", "<Otra/>");
        assertThrows(IllegalArgumentException.class, () -> CtFile.load(f));
    }
}
