package miniscanner;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

import static miniscanner.Ui.*;

/**
 * Pestaña Tabla de trucos: entradas guardables en .CT, con valores editables y congelables,
 * organizadas en grupos que se expanden y contraen como en Cheat Engine.
 */
final class CheatTab extends JPanel {
    private final MiniScanner app;
    /** Todas las entradas (el orden de la tabla sale de CheatTree, que las agrupa). */
    final List<CheatEntry> cheats = new CopyOnWriteArrayList<>();
    private File cheatFile;

    /** Una fila visible: un grupo (group != null) o una entrada, con su nivel de sangría. */
    private record Row(int depth, CheatTree group, CheatEntry entry) {}
    private List<Row> rows = List.of();
    private final Set<List<String>> collapsed = new HashSet<>();

    private final CheatTableModel model = new CheatTableModel();
    private final JTable table = new JTable(model);
    private final Win.Hotkeys hotkeys = new Win.Hotkeys(this::hotkeyPressed, this::hotkeysFailed);

    CheatTab(MiniScanner app) {
        super(new BorderLayout());
        this.app = app;
        table.getColumnModel().getColumn(0).setMaxWidth(70);
        table.getColumnModel().getColumn(1).setPreferredWidth(220);
        table.getColumnModel().getColumn(3).setMaxWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(300);
        table.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                Row row = r < rows.size() ? rows.get(r) : null;
                setBorder(new EmptyBorder(0, 4 + (row == null ? 0 : row.depth()) * 18, 0, 0));
                comp.setFont(row != null && row.group() != null ? t.getFont().deriveFont(Font.BOLD) : t.getFont());
                return comp;
            }
        });
        // Un clic en el nombre de un grupo lo expande o lo contrae
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent ev) {
                int r = table.rowAtPoint(ev.getPoint()), c = table.columnAtPoint(ev.getPoint());
                if (ev.getClickCount() != 1) return; // con doble clic se abriría y cerraría enseguida
                if (r < 0 || c != 1 || r >= rows.size() || rows.get(r).group() == null) return;
                List<String> path = rows.get(r).group().path;
                if (!collapsed.remove(path)) collapsed.add(path);
                rebuild();
                table.setRowSelectionInterval(r, r);
            }
        });

        JPanel south = new JPanel(new GridLayout(0, 1));
        south.add(row(button("Agregar dirección...", this::addManualEntry), button("Eliminar", this::deleteCheats),
                button("Mover a grupo...", this::moveToGroup), button("Tecla rápida...", this::editHotkey),
                button("Buscar pointers de esta", this::cheatToPointers), button("Ver en memoria", () -> {
                    CheatEntry e = selectedEntry();
                    long a = e == null ? Win.BAD : e.address.resolve(app.proc);
                    if (a != Win.BAD) app.memoryTab.showAddress(a);
                })));
        south.add(row(button("Abrir tabla (.CT)...", this::loadTable), button("Guardar tabla", () -> saveTable(false)),
                button("Guardar como...", () -> saveTable(true))));
        JLabel help = new JLabel(" Doble clic en Descripción o Valor para editarlos. Marca «Congelar» para fijar el valor"
                + " (en un grupo, todo lo que tiene). Clic en un grupo para abrirlo o cerrarlo.");
        add(help, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        Thread freezer = new Thread(this::freezeLoop, "congelar");
        freezer.setDaemon(true);
        freezer.start();
        // refresca los valores mientras la pestaña está a la vista
        new Timer(300, e -> {
            if (isShowing() && !table.isEditing()) refreshRows();
        }).start();
    }

    void addAll(List<CheatEntry> entries) {
        cheats.addAll(entries);
        changed();
    }

    /** Las entradas cambiaron (se agregaron, quitaron, movieron o cambiaron sus teclas). */
    private void changed() {
        rebuild();
        updateHotkeys();
    }

    /** Vuelve a armar las filas visibles a partir de los grupos. */
    private void rebuild() {
        List<Row> out = new ArrayList<>();
        addRows(CheatTree.of(cheats), 0, out);
        rows = out;
        model.fireTableDataChanged();
    }

    private void addRows(CheatTree node, int depth, List<Row> out) {
        for (Object o : node.children) {
            if (o instanceof CheatEntry e) { out.add(new Row(depth, null, e)); continue; }
            CheatTree g = (CheatTree) o;
            out.add(new Row(depth, g, null));
            if (!collapsed.contains(g.path)) addRows(g, depth + 1, out);
        }
    }

    /** Repinta valores y casillas sin perder la selección. */
    private void refreshRows() {
        if (!rows.isEmpty()) model.fireTableRowsUpdated(0, rows.size() - 1);
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
        if (n > 0) refreshRows();
        return n;
    }

    // ------------------------------------------------------------ selección
    /** Entradas seleccionadas; un grupo seleccionado cuenta con todas sus entradas. */
    private List<CheatEntry> selectedEntries() {
        Set<CheatEntry> out = new LinkedHashSet<>();
        for (int r : table.getSelectedRows()) {
            if (r >= rows.size()) continue;
            Row row = rows.get(r);
            if (row.entry() != null) out.add(row.entry());
            else out.addAll(row.group().entries());
        }
        return new ArrayList<>(out);
    }

    /** La primera entrada (no grupo) seleccionada, o null. */
    private CheatEntry selectedEntry() {
        for (int r : table.getSelectedRows()) if (r < rows.size() && rows.get(r).entry() != null) return rows.get(r).entry();
        return null;
    }

    // ------------------------------------------------------------ modelo
    final class CheatTableModel extends AbstractTableModel {
        final String[] cols = {"Congelar", "Descripción", "Dirección", "Tipo", "Valor", "Tecla"};
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }

        @Override public boolean isCellEditable(int r, int c) {
            if (r >= rows.size()) return false;
            return rows.get(r).group() != null ? c == 0 : c == 0 || c == 1 || c == 4;
        }

        public Object getValueAt(int r, int c) {
            if (r >= rows.size()) return c == 0 ? Boolean.FALSE : "";
            Row row = rows.get(r);
            if (row.group() != null) {
                List<CheatEntry> all = row.group().entries();
                return switch (c) {
                    case 0 -> !all.isEmpty() && all.stream().allMatch(e -> e.frozenValue != null);
                    case 1 -> (collapsed.contains(row.group().path) ? "▶ " : "▼ ") + row.group().name + "  (" + all.size() + ")";
                    default -> "";
                };
            }
            CheatEntry e = row.entry();
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
            if (r >= rows.size()) return;
            Row row = rows.get(r);
            if (row.group() != null) {
                if (c != 0) return;
                int failed = 0;
                for (CheatEntry e : row.group().entries()) if (!setFrozen(e, Boolean.TRUE.equals(v))) failed++;
                if (failed > 0) app.setStatus(" No se pudieron leer " + failed + " entradas del grupo (¿abriste el juego?).");
                refreshRows();
                return;
            }
            CheatEntry e = row.entry();
            if (c == 1) e.description = String.valueOf(v);
            else if (c == 0) {
                if (!setFrozen(e, Boolean.TRUE.equals(v))) app.setStatus(" No se pudo leer esa dirección (¿abriste el juego?).");
            } else if (c == 4) {
                try {
                    if (!writeValue(e, String.valueOf(v))) app.setStatus(" No se pudo escribir en esa dirección.");
                } catch (NumberFormatException ex) { app.error("Valor inválido para " + e.type.label); }
            }
            refreshRows(); // la casilla del grupo depende de sus entradas
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
        refreshRows();
        app.setStatus(" " + Hotkey.keyText(combo >> 8, combo & 0xFF) + ": " + String.join(", ", done));
    }

    private void hotkeysFailed(Set<Integer> combos) {
        List<String> names = new ArrayList<>();
        for (int c : combos) names.add(Hotkey.keyText(c >> 8, c & 0xFF));
        app.setStatus(" No se pudo usar " + String.join(", ", names) + ": otro programa ya usa esa tecla. Elige otra.");
    }

    void editHotkey() {
        List<CheatEntry> sel = selectedEntries();
        if (sel.isEmpty()) { app.info("Selecciona una o más entradas (o un grupo) de la tabla."); return; }
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
            refreshRows();
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
        List<CheatEntry> victims = selectedEntries();
        if (victims.isEmpty()) return;
        if (table.isEditing()) table.getCellEditor().cancelCellEditing();
        boolean group = false;
        for (int r : table.getSelectedRows()) group |= r < rows.size() && rows.get(r).group() != null;
        if (group && JOptionPane.showConfirmDialog(this, "¿Eliminar " + victims.size() + " entradas (incluye grupos completos)?",
                "Eliminar", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        cheats.removeAll(victims);
        changed();
    }

    void moveToGroup() {
        List<CheatEntry> sel = selectedEntries();
        if (sel.isEmpty()) { app.info("Selecciona una o más entradas (o un grupo) de la tabla."); return; }
        Set<String> existing = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (CheatEntry e : cheats)
            for (int i = 1; i <= e.group.size(); i++) existing.add(CheatTree.pathText(e.group.subList(0, i)));
        JComboBox<String> box = new JComboBox<>(existing.toArray(new String[0]));
        box.setEditable(true);
        box.setSelectedItem(CheatTree.pathText(sel.get(0).group));
        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.add(new JLabel("Grupo (usa « / » para subgrupos, p. ej. Jugador / Armas; vacío = sin grupo):"));
        form.add(box);
        if (JOptionPane.showConfirmDialog(this, form, "Mover " + sel.size() + " entrada(s) a un grupo",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        Object text = box.getEditor().getItem();
        List<String> path = CheatTree.parsePath(text == null ? "" : text.toString());
        for (CheatEntry e : sel) e.group = path;
        changed();
        app.setStatus(path.isEmpty() ? " Entradas sacadas de su grupo." : " Movidas a «" + CheatTree.pathText(path) + "».");
    }

    void cheatToPointers() {
        CheatEntry e = selectedEntry();
        if (e == null || app.proc == null) { app.info("Abre el juego y selecciona una entrada."); return; }
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
