package miniscanner;

import javax.swing.*;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static miniscanner.Ui.*;

/** Pestaña Tabla de trucos: entradas guardables en .CT, con valores editables y congelables. */
final class CheatTab extends JPanel {
    private final MiniScanner app;
    final List<CheatEntry> cheats = new CopyOnWriteArrayList<>();
    private File cheatFile;
    private final CheatTableModel model = new CheatTableModel();
    private final JTable table = new JTable(model);
    private final Win.Hotkeys hotkeys = new Win.Hotkeys(this::hotkeyPressed, this::hotkeysFailed);

    CheatTab(MiniScanner app) {
        super(new BorderLayout());
        this.app = app;
        table.getColumnModel().getColumn(0).setMaxWidth(70);
        table.getColumnModel().getColumn(3).setMaxWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(300);
        JPanel south = new JPanel(new GridLayout(0, 1));
        south.add(row(button("Agregar dirección...", this::addManualEntry), button("Eliminar", this::deleteCheats),
                button("Tecla rápida...", this::editHotkey),
                button("Buscar pointers de esta", this::cheatToPointers), button("Ver en memoria", () -> {
                    int r = table.getSelectedRow();
                    long a = r < 0 ? Win.BAD : cheats.get(r).address.resolve(app.proc);
                    if (a != Win.BAD) app.memoryTab.showAddress(a);
                })));
        south.add(row(button("Abrir tabla (.CT)...", this::loadTable), button("Guardar tabla", () -> saveTable(false)),
                button("Guardar como...", () -> saveTable(true))));
        JLabel help = new JLabel(" Doble clic en Descripción o Valor para editarlos. Marca «Congelar» para fijar el valor."
                + " Las teclas rápidas funcionan aunque estés dentro del juego.");
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
        changed();
    }

    /** Las filas cambiaron (se agregaron, quitaron o cambiaron sus teclas). */
    private void changed() {
        model.fireTableDataChanged();
        updateHotkeys();
    }

    private void updateHotkeys() {
        Set<Integer> combos = new HashSet<>();
        for (CheatEntry e : cheats) if (e.hotkey != null) combos.add(e.hotkey.combo());
        hotkeys.set(combos);
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
        final String[] cols = {"Congelar", "Descripción", "Dirección", "Tipo", "Valor", "Tecla"};
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
                case 4 -> app.readText(e.address.resolve(app.proc), e.type);
                default -> e.hotkey == null ? "" : e.hotkey.toString();
            };
        }

        @Override public void setValueAt(Object v, int r, int c) {
            CheatEntry e = cheats.get(r);
            if (c == 1) e.description = String.valueOf(v);
            else if (c == 0) {
                if (!setFrozen(e, Boolean.TRUE.equals(v))) app.setStatus(" No se pudo leer esa dirección (¿abriste el juego?).");
            } else if (c == 4) {
                try {
                    if (!writeValue(e, String.valueOf(v))) app.setStatus(" No se pudo escribir en esa dirección.");
                } catch (NumberFormatException ex) { app.error("Valor inválido para " + e.type.label); }
            }
            fireTableRowsUpdated(r, r);
        }
    }

    /** Congela la entrada en su valor actual, o la descongela. false si no se pudo leer. */
    private boolean setFrozen(CheatEntry e, boolean on) {
        if (!on) { e.frozenValue = null; return true; }
        Win.Proc proc = app.proc;
        long a = e.address.resolve(proc);
        byte[] raw = a == Win.BAD ? null : proc.read(a, e.type.size);
        if (raw == null) return false;
        e.frozenValue = raw;
        return true;
    }

    /** Escribe el valor (y si está congelada, la deja congelada en él). false si no se pudo escribir. */
    private boolean writeValue(CheatEntry e, String text) {
        byte[] data = e.type.encode(e.type.parse(text));
        Win.Proc proc = app.proc;
        long a = e.address.resolve(proc);
        if (a == Win.BAD || !proc.write(a, data)) return false;
        if (e.frozenValue != null) e.frozenValue = data;
        return true;
    }

    // ------------------------------------------------------------ teclas rápidas
    private void hotkeyPressed(int combo) {
        List<String> done = new ArrayList<>();
        for (CheatEntry e : cheats) {
            Hotkey hk = e.hotkey;
            if (hk == null || hk.combo() != combo) continue;
            boolean ok = hk.action() == Hotkey.Action.TOGGLE ? setFrozen(e, e.frozenValue == null) : writeValue(e, hk.value());
            done.add(e.description + (!ok ? " (no se pudo)" : hk.action() == Hotkey.Action.SET ? " = " + hk.value()
                    : e.frozenValue != null ? " congelado" : " descongelado"));
        }
        if (done.isEmpty()) return;
        model.fireTableDataChanged();
        app.setStatus(" " + Hotkey.keyText(combo >> 8, combo & 0xFF) + ": " + String.join(", ", done));
    }

    private void hotkeysFailed(Set<Integer> combos) {
        List<String> names = new ArrayList<>();
        for (int c : combos) names.add(Hotkey.keyText(c >> 8, c & 0xFF));
        app.setStatus(" No se pudo usar " + String.join(", ", names) + ": otro programa ya usa esa tecla. Elige otra.");
    }

    void editHotkey() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) { app.info("Selecciona una o más entradas de la tabla."); return; }
        List<CheatEntry> sel = new ArrayList<>();
        for (int r : rows) sel.add(cheats.get(r));
        Hotkey cur = sel.get(0).hotkey;

        int[] key = cur == null ? new int[]{0, 0} : new int[]{cur.mods(), cur.vk()}; // {mods, vk}; vk 0 = ninguna
        JTextField keyField = new JTextField(cur == null ? "" : cur.keyText(), 14);
        keyField.setEditable(false);
        keyField.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent ev) {
                int vk = ev.getKeyCode();
                if (vk == KeyEvent.VK_DELETE || vk == KeyEvent.VK_BACK_SPACE) { key[0] = key[1] = 0; keyField.setText(""); }
                else if (Hotkey.supported(vk)) {
                    key[0] = Hotkey.modsOf(ev);
                    key[1] = vk;
                    keyField.setText(Hotkey.keyText(key[0], vk));
                }
                ev.consume();
            }
        });
        // Que el campo tenga el foco al abrir el diálogo, para pulsar la tecla directamente
        keyField.addAncestorListener(new AncestorListener() {
            public void ancestorAdded(AncestorEvent ev) { keyField.requestFocusInWindow(); }
            public void ancestorRemoved(AncestorEvent ev) {}
            public void ancestorMoved(AncestorEvent ev) {}
        });
        JRadioButton toggle = new JRadioButton("Congelar / descongelar", cur == null || cur.action() == Hotkey.Action.TOGGLE);
        JRadioButton set = new JRadioButton("Poner el valor:", !toggle.isSelected());
        ButtonGroup group = new ButtonGroup();
        group.add(toggle);
        group.add(set);
        JTextField valueField = new JTextField(cur != null && cur.value() != null ? cur.value() : "", 10);

        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.add(new JLabel("Pulsa la combinación (F1-F12, letras, números o numpad, con Ctrl/Alt/Shift)."));
        form.add(new JLabel("Supr o Retroceso para quitarla."));
        form.add(keyField);
        form.add(toggle);
        form.add(row(set, valueField));

        // Mientras el diálogo está abierto se sueltan las teclas, para poder volver a elegir una que ya se usa
        hotkeys.set(Set.of());
        int answer = JOptionPane.showConfirmDialog(this, form, "Tecla rápida (" + sel.size() + " entrada(s))", JOptionPane.OK_CANCEL_OPTION);
        try {
            if (answer != JOptionPane.OK_OPTION) return;
            Hotkey hk = null;
            if (key[1] != 0) {
                String value = valueField.getText().trim();
                if (set.isSelected())
                    for (CheatEntry e : sel) {
                        try { e.type.parse(value); }
                        catch (NumberFormatException ex) { app.error("Valor inválido para " + e.type.label + ": " + value); return; }
                    }
                hk = new Hotkey(key[0], key[1], set.isSelected() ? Hotkey.Action.SET : Hotkey.Action.TOGGLE, set.isSelected() ? value : null);
            }
            for (CheatEntry e : sel) e.hotkey = hk;
            model.fireTableDataChanged();
            app.setStatus(hk == null ? " Tecla rápida quitada." : " Tecla rápida: " + hk + ". No olvides guardar la tabla.");
        } finally {
            updateHotkeys();
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
        changed();
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
