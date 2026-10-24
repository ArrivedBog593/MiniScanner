package miniscanner;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static miniscanner.Ui.*;

/** Pestaña Tabla de trucos: entradas guardables en .CT, con valores editables y congelables. */
final class CheatTab extends JPanel {
    private final MiniScanner app;
    final List<CheatEntry> cheats = new CopyOnWriteArrayList<>();
    private File cheatFile;
    private final CheatTableModel model = new CheatTableModel();
    private final JTable table = new JTable(model);

    CheatTab(MiniScanner app) {
        super(new BorderLayout());
        this.app = app;
        table.getColumnModel().getColumn(0).setMaxWidth(70);
        table.getColumnModel().getColumn(3).setMaxWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(300);
        JPanel south = new JPanel(new GridLayout(0, 1));
        south.add(row(button("Agregar dirección...", this::addManualEntry), button("Eliminar", this::deleteCheats),
                button("Buscar pointers de esta", this::cheatToPointers), button("Ver en memoria", () -> {
                    int r = table.getSelectedRow();
                    long a = r < 0 ? Win.BAD : cheats.get(r).address.resolve(app.proc);
                    if (a != Win.BAD) app.memoryTab.showAddress(a);
                })));
        south.add(row(button("Abrir tabla (.CT)...", this::loadTable), button("Guardar tabla", () -> saveTable(false)),
                button("Guardar como...", () -> saveTable(true))));
        JLabel help = new JLabel(" Doble clic en Descripción o Valor para editarlos. Marca «Congelar» para fijar el valor.");
        add(help, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        Thread freezer = new Thread(this::freezeLoop, "congelar");
        freezer.setDaemon(true);
        freezer.start();
        // refresca los valores mientras la pestaña está a la vista
        new Timer(300, e -> {
            if (isShowing() && !table.isEditing() && model.getRowCount() > 0)
                model.fireTableRowsUpdated(0, model.getRowCount() - 1);
        }).start();
    }

    void addAll(List<CheatEntry> entries) {
        cheats.addAll(entries);
        model.fireTableDataChanged();
    }

    /** Descongela las direcciones absolutas (eran del proceso anterior). Devuelve cuántas. */
    int unfreezeDynamic() {
        int n = 0;
        for (CheatEntry e : cheats)
            if (e.frozenValue != null && e.address.module == null) { e.frozenValue = null; n++; }
        if (n > 0) model.fireTableDataChanged();
        return n;
    }

    // ------------------------------------------------------------ modelo
    final class CheatTableModel extends AbstractTableModel {
        final String[] cols = {"Congelar", "Descripción", "Dirección", "Tipo", "Valor"};
        public int getRowCount() { return cheats.size(); }
        public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 0 || c == 1 || c == 4; }

        public Object getValueAt(int r, int c) {
            if (r >= cheats.size()) return "";
            CheatEntry e = cheats.get(r);
            return switch (c) {
                case 0 -> e.frozenValue != null;
                case 1 -> e.description;
                case 2 -> e.address.toString();
                case 3 -> e.type.ctName;
                default -> app.readText(e.address.resolve(app.proc), e.type);
            };
        }

        @Override public void setValueAt(Object v, int r, int c) {
            CheatEntry e = cheats.get(r);
            Win.Proc proc = app.proc;
            if (c == 1) e.description = String.valueOf(v);
            else if (c == 0) {
                if (Boolean.TRUE.equals(v)) {
                    long a = e.address.resolve(proc);
                    byte[] raw = a == Win.BAD ? null : proc.read(a, e.type.size);
                    if (raw == null) { app.setStatus(" No se pudo leer esa dirección (¿abriste el juego?)."); return; }
                    e.frozenValue = raw;
                } else e.frozenValue = null;
            } else if (c == 4) {
                try {
                    byte[] data = e.type.encode(e.type.parse(String.valueOf(v)));
                    long a = e.address.resolve(proc);
                    if (a == Win.BAD || !proc.write(a, data)) { app.setStatus(" No se pudo escribir en esa dirección."); return; }
                    if (e.frozenValue != null) e.frozenValue = data;
                } catch (NumberFormatException ex) { app.error("Valor inválido para " + e.type.label); }
            }
            fireTableRowsUpdated(r, r);
        }
    }

    // ------------------------------------------------------------ acciones
    void addManualEntry() {
        JTextField desc = new JTextField("Sin nombre", 20), base = new JTextField(20), offs = new JTextField(20);
        JComboBox<ValueType> type = new JComboBox<>(ValueType.values());
        type.setSelectedItem(ValueType.INT4);
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.add(new JLabel("Descripción:")); form.add(desc);
        form.add(new JLabel("Dirección (ej. \"Juego.exe\"+1A2B):")); form.add(base);
        form.add(new JLabel("Offsets en hex, separados por coma:")); form.add(offs);
        form.add(new JLabel("Tipo:")); form.add(type);
        if (JOptionPane.showConfirmDialog(this, form, "Agregar dirección", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            addAll(List.of(new CheatEntry(desc.getText().trim(), AddressExpr.parse(base.getText(), offs.getText()), (ValueType) type.getSelectedItem())));
        } catch (Exception ex) { app.error(ex.getMessage()); }
    }

    void deleteCheats() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) return;
        if (table.isEditing()) table.getCellEditor().cancelCellEditing();
        List<CheatEntry> victims = new ArrayList<>();
        for (int r : rows) victims.add(cheats.get(r));
        cheats.removeAll(victims);
        model.fireTableDataChanged();
    }

    void cheatToPointers() {
        int r = table.getSelectedRow();
        if (r < 0 || app.proc == null) { app.info("Abre el juego y selecciona una entrada."); return; }
        CheatEntry e = cheats.get(r);
        long a = e.address.resolve(app.proc);
        if (a == Win.BAD) { app.error("Esa entrada no apunta a nada válido ahora mismo."); return; }
        app.pointerTab.setTarget(a, e.type);
    }

    void loadTable() {
        JFileChooser fc = app.chooser("Tablas de Cheat Engine (*.CT)", "ct");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        app.prefs.put("lastDir", f.getParent());
        try {
            CtFile.LoadResult res = CtFile.load(f);
            if (!cheats.isEmpty() && JOptionPane.showConfirmDialog(this, "¿Reemplazar la tabla actual? (No = agregar al final)",
                    "Abrir tabla", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) cheats.clear();
            cheatFile = f;
            addAll(res.entries());
            String msg = "Se cargaron " + res.entries().size() + " entradas de " + f.getName() + ".";
            if (res.skipped() > 0) msg += "\n" + res.skipped() + " entradas se omitieron porque usan scripts de Cheat Engine "
                    + "(Auto Assembler / Lua) u otros tipos que MiniScanner no soporta.";
            app.info(msg);
        } catch (Exception ex) { app.error("No se pudo abrir la tabla:\n" + ex.getMessage()); }
    }

    void saveTable(boolean saveAs) {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        File f = cheatFile;
        if (f == null || saveAs) {
            JFileChooser fc = app.chooser("Tablas de Cheat Engine (*.CT)", "ct");
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            f = fc.getSelectedFile();
            if (!f.getName().toLowerCase().endsWith(".ct")) f = new File(f.getPath() + ".CT");
        }
        try {
            CtFile.save(f, cheats);
            cheatFile = f;
            app.prefs.put("lastDir", f.getParent());
            long dynamic = cheats.stream().filter(e -> e.address.module == null).count();
            app.setStatus(" Tabla guardada: " + f.getName() + (dynamic > 0
                    ? " (" + dynamic + " entradas son direcciones dinámicas y no funcionarán al reiniciar el juego)" : ""));
        } catch (Exception ex) { app.error("No se pudo guardar:\n" + ex.getMessage()); }
    }

    void freezeLoop() {
        while (true) {
            Win.Proc p = app.proc;
            if (p != null) for (CheatEntry e : cheats) {
                byte[] v = e.frozenValue;
                if (v == null) continue;
                long a = e.address.resolve(p);
                if (a != Win.BAD) p.write(a, v);
            }
            try { Thread.sleep(100); } catch (InterruptedException ex) { return; }
        }
    }
}
