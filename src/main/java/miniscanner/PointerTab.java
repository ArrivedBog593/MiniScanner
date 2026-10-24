package miniscanner;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static miniscanner.Ui.*;

/** Pestaña Pointers: encontrar direcciones que sobreviven a reiniciar el juego. */
final class PointerTab extends JPanel {
    private final MiniScanner app;
    private List<AddressExpr> pointers = new ArrayList<>();
    private final JTextField ptrTarget = new JTextField(14), ptrMaxOffset = new JTextField("1000", 6), ptrValue = new JTextField(10);
    private final JSpinner ptrLevel = new JSpinner(new SpinnerNumberModel(5, 1, 8, 1));
    private final JComboBox<ValueType> ptrType = new JComboBox<>(ValueType.values());
    private final DefaultTableModel ptrModel = readOnlyModel("Base", "Offsets", "Apunta a", "Valor");
    private final JTable ptrTable = new JTable(ptrModel);

    PointerTab(MiniScanner app) {
        super(new BorderLayout());
        this.app = app;
        ptrType.setSelectedItem(ValueType.INT4);
        JTextArea help = new JTextArea("""
                Cómo encontrar una dirección permanente:
                 1) Encuentra el valor con el Escáner y pulsa «Buscar pointers de esta dirección».
                 2) Pulsa «Escanear pointers» (puede tardar y usar bastante RAM).
                 3) Cierra y vuelve a abrir el juego, ábrelo aquí otra vez y encuentra de nuevo la dirección con el Escáner.
                 4) Pon la nueva dirección arriba y pulsa «Filtrar por dirección». Repite 3-4 hasta que queden pocos.
                 5) Agrega uno a la tabla de trucos. ¡Ya funcionará cada vez que abras el juego!""");
        help.setEditable(false);
        help.setOpaque(false);
        help.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        JPanel north = new JPanel(new GridLayout(0, 1));
        north.add(row(new JLabel("Dirección objetivo (hex):"), ptrTarget, new JLabel("Tipo:"), ptrType,
                new JLabel("Nivel máx:"), ptrLevel, new JLabel("Offset máx (hex):"), ptrMaxOffset));
        north.add(row(button("Escanear pointers", this::scanPointers), button("Filtrar por dirección", this::filterPtrByAddress),
                new JLabel("  Valor:"), ptrValue, button("Filtrar por valor", this::filterPtrByValue)));
        JPanel top = new JPanel(new BorderLayout());
        top.add(help, BorderLayout.NORTH);
        top.add(north, BorderLayout.CENTER);
        JPanel south = row(button("Agregar seleccionados a la tabla", this::addPtrToTable),
                button("Guardar lista...", this::savePtrList), button("Cargar lista...", this::loadPtrList),
                button("Actualizar valores", this::showPointers));
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(ptrTable), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
    }

    /** Pone la dirección (desde el Escáner o la tabla de trucos) y muestra esta pestaña. */
    void setTarget(long addr, ValueType type) {
        ptrTarget.setText(AddressExpr.hex(addr));
        ptrType.setSelectedItem(type);
        app.showTab(this);
    }

    static long parseHex(String s) { return Long.parseUnsignedLong(s.trim().replaceFirst("^(?i)0x", ""), 16); }

    void scanPointers() {
        if (!app.needProc()) return;
        long target, maxOff;
        try { target = parseHex(ptrTarget.getText()); maxOff = parseHex(ptrMaxOffset.getText()); }
        catch (NumberFormatException ex) { app.error("Escribe la dirección y el offset máximo en hexadecimal."); return; }
        int level = (Integer) ptrLevel.getValue();
        Win.Proc p = app.proc;
        app.runBg("Leyendo memoria", () -> PointerScanner.scan(p, target, level, maxOff, app::setProg, s -> app.phase = s),
                list -> { pointers = list; showPointers(); });
    }

    void filterPtrByAddress() {
        if (!app.needProc() || pointers.isEmpty()) return;
        long target;
        try { target = parseHex(ptrTarget.getText()); } catch (NumberFormatException ex) { app.error("Dirección inválida."); return; }
        Win.Proc p = app.proc;
        List<AddressExpr> src = pointers;
        app.runBg("Filtrando", () -> PointerScanner.filterByAddress(p, src, target, app::setProg), list -> { pointers = list; showPointers(); });
    }

    void filterPtrByValue() {
        if (!app.needProc() || pointers.isEmpty()) return;
        ValueType t = (ValueType) ptrType.getSelectedItem();
        double v;
        try { v = t.parse(ptrValue.getText()); } catch (NumberFormatException ex) { app.error("Valor inválido."); return; }
        double tol = ValueType.tolFor(ptrValue.getText());
        Win.Proc p = app.proc;
        List<AddressExpr> src = pointers;
        app.runBg("Filtrando", () -> PointerScanner.filterByValue(p, src, t, v, tol, app::setProg), list -> { pointers = list; showPointers(); });
    }

    void showPointers() {
        ptrModel.setRowCount(0);
        ValueType t = (ValueType) ptrType.getSelectedItem();
        int count = Math.min(pointers.size(), MiniScanner.MAX_SHOWN);
        for (int i = 0; i < count; i++) {
            AddressExpr e = pointers.get(i);
            long a = e.resolve(app.proc);
            ptrModel.addRow(new Object[]{e.baseText(), e.offsetsText(), a == Win.BAD ? "??" : AddressExpr.hex(a), app.readText(a, t)});
        }
        String extra = pointers.size() > MiniScanner.MAX_SHOWN ? " (mostrando los primeros " + MiniScanner.MAX_SHOWN + ")" : "";
        app.setStatus(String.format(" %,d pointers%s.", pointers.size(), extra));
    }

    void addPtrToTable() {
        int[] rows = ptrTable.getSelectedRows();
        if (rows.length == 0) { app.info("Selecciona uno o más pointers."); return; }
        String name = JOptionPane.showInputDialog(this, "Nombre para la(s) entrada(s):", "Dinero");
        if (name == null) return;
        List<CheatEntry> added = new ArrayList<>();
        for (int r : rows) added.add(new CheatEntry(name, pointers.get(r), (ValueType) ptrType.getSelectedItem()));
        app.cheatTab.addAll(added);
        app.setStatus(" Agregado a la tabla de trucos. No olvides guardar la tabla.");
    }

    void savePtrList() {
        if (pointers.isEmpty()) return;
        JFileChooser fc = app.chooser("Listas de pointers (*.ptrs)", "ptrs");
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".ptrs")) f = new File(f.getPath() + ".ptrs");
        try {
            List<String> lines = new ArrayList<>(pointers.size());
            for (AddressExpr e : pointers) lines.add(e.serialize());
            Files.write(f.toPath(), lines, StandardCharsets.UTF_8);
            app.prefs.put("lastDir", f.getParent());
            app.setStatus(" Lista guardada: " + f.getName());
        } catch (Exception ex) { app.error("No se pudo guardar:\n" + ex.getMessage()); }
    }

    void loadPtrList() {
        JFileChooser fc = app.chooser("Listas de pointers (*.ptrs)", "ptrs");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            List<AddressExpr> list = new ArrayList<>();
            for (String line : Files.readAllLines(fc.getSelectedFile().toPath(), StandardCharsets.UTF_8))
                if (!line.isBlank()) list.add(AddressExpr.deserialize(line.trim()));
            pointers = list;
            app.prefs.put("lastDir", fc.getSelectedFile().getParent());
            showPointers();
        } catch (Exception ex) { app.error("No se pudo cargar:\n" + ex.getMessage()); }
    }
}
