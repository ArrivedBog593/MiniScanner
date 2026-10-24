package miniscanner;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * MiniScanner - escáner/editor de memoria estilo Cheat Engine (solo Windows).
 * Pestañas: Escáner, Tabla de trucos (.CT compatible con Cheat Engine) y Pointers.
 */
public class MiniScanner extends JFrame {
    static final int MAX_SHOWN = 1000;

    final Preferences prefs = Preferences.userNodeForPackage(MiniScanner.class);
    volatile Win.Proc proc;
    List<Win.ProcInfo> procs = new ArrayList<>();
    volatile boolean busy;
    volatile long progDone, progTotal;
    volatile String phase = "";

    final JComboBox<String> procBox = new JComboBox<>();
    final JLabel status = new JLabel(" Elige el proceso del juego y presiona Abrir.");
    final JTabbedPane tabs = new JTabbedPane();

    // ---- escáner
    Scanner scanner;
    long[] shownAddrs = new long[0];
    final JTextField valueField = new JTextField(10), value2Field = new JTextField(10), writeField = new JTextField(10);
    final JLabel value2Label = new JLabel("y");
    final JComboBox<ValueType> typeBox = new JComboBox<>(ValueType.values());
    final JComboBox<Scanner.Mode> modeBox = new JComboBox<>(Scanner.Mode.FIRST);
    final JCheckBox hexBox = new JCheckBox("Hex"), pauseBox = new JCheckBox("Pausar el juego al escanear");
    /** Congela el juego hasta que lo sueltes (para valores que cambian rápido). */
    final JToggleButton holdBtn = new JToggleButton(HOLD_OFF);
    static final String HOLD_OFF = "⏸ Pausar juego", HOLD_ON = "▶ Reanudar juego";
    final JButton firstBtn = new JButton("Primer escaneo"), nextBtn = new JButton("Siguiente escaneo"),
            undoBtn = new JButton("Deshacer último escaneo");
    final DefaultTableModel scanModel = readOnlyModel("Dirección", "Valor actual");
    final JTable scanTable = new JTable(scanModel);

    // ---- tabla de trucos
    final List<CheatEntry> cheats = new CopyOnWriteArrayList<>();
    File cheatFile;
    final CheatTableModel cheatModel = new CheatTableModel();
    final JTable cheatTable = new JTable(cheatModel);

    // ---- pointers
    List<AddressExpr> pointers = new ArrayList<>();
    final JTextField ptrTarget = new JTextField(14), ptrMaxOffset = new JTextField("1000", 6), ptrValue = new JTextField(10);
    final JSpinner ptrLevel = new JSpinner(new SpinnerNumberModel(5, 1, 8, 1));
    final JComboBox<ValueType> ptrType = new JComboBox<>(ValueType.values());
    final DefaultTableModel ptrModel = readOnlyModel("Base", "Offsets", "Apunta a", "Valor");
    final JTable ptrTable = new JTable(ptrModel);

    // ---- visor de memoria
    static final int MEM_ROWS = 32, MEM_BYTES = MEM_ROWS * 16;
    long memBase;
    final byte[] memData = new byte[MEM_BYTES];
    final boolean[] memOk = new boolean[MEM_BYTES];
    final long[] memChanged = new long[MEM_BYTES];
    final JTextField memAddr = new JTextField(22);
    final JCheckBox memAuto = new JCheckBox("Auto-actualizar", true);
    final JLabel memInfo = new JLabel(" ");
    final JComboBox<ValueType> memType = new JComboBox<>(ValueType.values());
    final MemModel memModel = new MemModel();
    final JTable memTable = new JTable(memModel);

    MiniScanner() {
        super("MiniScanner");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(860, 640);
        setLocationRelativeTo(null);
        typeBox.setSelectedItem(ValueType.INT4);
        memType.setSelectedItem(ValueType.INT4);
        ptrType.setSelectedItem(ValueType.INT4);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        procBox.setPreferredSize(new Dimension(420, procBox.getPreferredSize().height));
        top.add(new JLabel("Proceso:"));
        top.add(procBox);
        top.add(button("↻", this::refreshProcs));
        top.add(button("Abrir", this::openProc));

        tabs.addTab("Escáner", buildScannerTab());
        tabs.addTab("Tabla de trucos", buildCheatTab());
        tabs.addTab("Pointers", buildPointerTab());
        tabs.addTab("Memoria", buildMemoryTab());

        status.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        add(top, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        refreshProcs();
        Thread freezer = new Thread(this::freezeLoop, "congelar");
        freezer.setDaemon(true);
        freezer.start();
        // refresca los valores de la tabla de trucos
        new Timer(300, e -> {
            if (tabs.getSelectedIndex() == 1 && !cheatTable.isEditing() && cheatModel.getRowCount() > 0)
                cheatModel.fireTableRowsUpdated(0, cheatModel.getRowCount() - 1);
        }).start();
        // refresca el visor de memoria
        new Timer(500, e -> {
            if (tabs.getSelectedIndex() == 3 && memAuto.isSelected() && !memTable.isEditing()) refreshMem();
        }).start();
    }

    // ================================================================ helpers
    static JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.addActionListener(e -> r.run());
        return b;
    }

    static DefaultTableModel readOnlyModel(String... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    static JPanel row(Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (Component c : cs) p.add(c);
        return p;
    }

    void error(String msg) { JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE); }

    boolean needProc() {
        if (proc == null) { error("Primero elige el proceso del juego y presiona Abrir."); return false; }
        if (busy) { JOptionPane.showMessageDialog(this, "Espera a que termine la operación actual."); return false; }
        return true;
    }

    String addrText(long a) {
        Win.Module m = proc == null ? null : proc.moduleAt(a);
        return m != null ? m.name() + "+" + AddressExpr.hex(a - m.base()) : AddressExpr.hex(a);
    }

    String readText(long addr, ValueType t) {
        if (proc == null || addr == Win.BAD) return "??";
        byte[] raw = proc.read(addr, t.size);
        return raw == null ? "??" : t.format(t.decode(raw));
    }

    /** Ejecuta una tarea larga en segundo plano mostrando el progreso. */
    <T> void runBg(String label, Callable<T> task, Consumer<T> onDone) {
        busy = true;
        progDone = 0; progTotal = 0; phase = label;
        Timer timer = new Timer(150, e -> status.setText(" " + phase + "... "
                + (progTotal > 0 ? (progDone * 100 / Math.max(1, progTotal)) + "%" : "")));
        timer.start();
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                timer.stop();
                busy = false;
                try { onDone.accept(get()); }
                catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    status.setText(" Error.");
                    error(c.getMessage() != null ? c.getMessage() : c.toString());
                }
            }
        }.execute();
    }

    void setProg(long done, long total) { progDone = done; progTotal = total; }

    // ================================================================ proceso
    void refreshProcs() {
        procs = Win.listProcesses();
        procBox.removeAllItems();
        for (Win.ProcInfo p : procs) procBox.addItem(p.name() + "  (PID " + p.pid() + ")");
        String last = prefs.get("lastProc", "");
        for (int i = 0; i < procs.size(); i++) if (procs.get(i).name().equalsIgnoreCase(last)) procBox.setSelectedIndex(i);
    }

    void openProc() {
        if (busy) return;
        if (scanner != null) { scanner.close(); scanner = null; }
        int idx = procBox.getSelectedIndex();
        if (idx < 0) return;
        Win.ProcInfo info = procs.get(idx);
        Win.Proc np;
        try { np = new Win.Proc(info); } catch (IllegalStateException ex) { error(ex.getMessage()); return; }
        // Las direcciones absolutas eran del proceso anterior: congelarlas escribiría basura en el nuevo
        int unfrozen = 0;
        for (CheatEntry e : cheats)
            if (e.frozenValue != null && e.address.module == null) { e.frozenValue = null; unfrozen++; }
        if (unfrozen > 0) cheatModel.fireTableDataChanged();
        Win.Proc old = proc;
        proc = np;
        if (old != null) old.close();
        holdBtn.setSelected(false);
        holdBtn.setText(HOLD_OFF);
        prefs.put("lastProc", info.name());
        newScan();
        status.setText(" Abierto: " + info.name() + " (" + (np.ptrSize * 8) + " bits, " + np.modules.size() + " módulos)"
                + (unfrozen > 0 ? ". Se descongelaron " + unfrozen + " direcciones dinámicas del proceso anterior." : ""));
    }

    // ================================================================ pestaña Escáner
    JComponent buildScannerTab() {
        modeBox.addActionListener(e -> updateValueFields());
        firstBtn.addActionListener(e -> {
            if (busy) return;
            if (scanner != null && scanner.hasResults()) newScan(); else firstScan();
        });
        nextBtn.addActionListener(e -> nextScan());
        undoBtn.addActionListener(e -> undoScan());
        hexBox.addActionListener(e -> showScanResults());
        holdBtn.addActionListener(e -> toggleHold());
        JPanel north = new JPanel(new GridLayout(0, 1));
        north.add(row(new JLabel("Modo:"), modeBox, new JLabel("Valor:"), valueField, value2Label, value2Field, hexBox));
        north.add(row(new JLabel("Tipo:"), typeBox, pauseBox, holdBtn));
        north.add(row(firstBtn, nextBtn, undoBtn));
        JPanel south = new JPanel(new GridLayout(0, 1));
        south.add(row(new JLabel("Nuevo valor:"), writeField, button("Escribir en seleccionados", this::writeScanSelected),
                button("Actualizar lista", this::showScanResults)));
        south.add(row(button("Agregar a la tabla", this::addScanToTable), button("Buscar pointers", this::scanToPointers),
                button("Ver en memoria", () -> { int r = scanTable.getSelectedRow(); if (r >= 0) showInMemory(shownAddrs[r]); })));
        JPanel p = new JPanel(new BorderLayout());
        p.add(north, BorderLayout.NORTH);
        p.add(new JScrollPane(scanTable), BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        updateScanUi();
        return p;
    }

    void updateValueFields() {
        Scanner.Mode m = (Scanner.Mode) modeBox.getSelectedItem();
        if (m == null) return;
        valueField.setEnabled(m.values >= 1);
        value2Label.setVisible(m.values == 2);
        value2Field.setVisible(m.values == 2);
        valueField.getParent().revalidate();
    }

    void updateScanUi() {
        boolean active = scanner != null && scanner.hasResults();
        Scanner.Mode[] wanted = active ? Scanner.Mode.NEXT : Scanner.Mode.FIRST;
        if (modeBox.getItemCount() != wanted.length || modeBox.getItemAt(1) != wanted[1]) {
            Scanner.Mode sel = (Scanner.Mode) modeBox.getSelectedItem();
            modeBox.setModel(new DefaultComboBoxModel<>(wanted));
            if (sel != null && Arrays.asList(wanted).contains(sel)) modeBox.setSelectedItem(sel);
        }
        firstBtn.setText(active ? "Nuevo escaneo" : "Primer escaneo");
        nextBtn.setEnabled(active);
        undoBtn.setEnabled(active && scanner.canUndo());
        typeBox.setEnabled(!active);
        updateValueFields();
    }

    /** Lee un número del usuario respetando el tipo y la casilla Hex. */
    double parseVal(ValueType t, String s) {
        s = s.trim();
        if (hexBox.isSelected() && !t.isFloat) return Long.parseUnsignedLong(s.replaceFirst("(?i)^0x", ""), 16);
        return t.parse(s);
    }

    String fmtVal(ValueType t, double v) {
        if (hexBox.isSelected() && !t.isFloat) {
            long mask = t.size == 8 ? -1L : (1L << (t.size * 8)) - 1;
            return Long.toHexString((long) v & mask).toUpperCase();
        }
        return t.format(v);
    }

    /** Tolerancia de floats según los decimales escritos: "100" → ±0.5, "1.5" → ±0.05 (como Cheat Engine). */
    static double tolFor(String s) {
        s = s.trim();
        int dot = Math.max(s.lastIndexOf('.'), s.lastIndexOf(','));
        int decimals = dot < 0 ? 0 : s.length() - dot - 1;
        return 0.5 * Math.pow(10, -decimals);
    }

    <T> T withPause(Win.Proc p, boolean pause, Callable<T> c) throws Exception {
        // Si el juego ya estaba pausado a mano, el escaneo no lo reanuda al terminar.
        boolean alreadyPaused = p.suspended;
        if (pause && !alreadyPaused) p.suspend();
        try { return c.call(); } finally { if (pause && !alreadyPaused) p.resume(); }
    }

    /** Botón de pausa manual: congela el juego hasta que se vuelva a pulsar. */
    void toggleHold() {
        if (holdBtn.isSelected()) {
            if (proc == null) {
                holdBtn.setSelected(false);
                error("Primero elige el proceso del juego y presiona Abrir.");
                return;
            }
            proc.suspend();
            holdBtn.setText(HOLD_ON);
            status.setText(" Juego pausado. Haz el escaneo y reanúdalo cuando quieras.");
        } else {
            if (proc != null) proc.resume();
            holdBtn.setText(HOLD_OFF);
            status.setText(" Juego reanudado.");
        }
    }

    /** Lee los valores del formulario. Devuelve null (y avisa) si algo no es válido. */
    double[] readScanValues(ValueType t, Scanner.Mode m) {
        try {
            double v1 = m.values >= 1 ? parseVal(t, valueField.getText()) : 0;
            double v2 = m.values == 2 ? parseVal(t, value2Field.getText()) : 0;
            return new double[]{v1, v2};
        } catch (NumberFormatException ex) {
            error("Escribe un número válido" + (m.values == 2 ? " en los dos campos." : "."));
            return null;
        }
    }

    void firstScan() {
        if (!needProc()) return;
        ValueType type = (ValueType) typeBox.getSelectedItem();
        Scanner.Mode m = (Scanner.Mode) modeBox.getSelectedItem();
        double[] v = readScanValues(type, m);
        if (v == null) return;
        Scanner s = new Scanner(proc, type);
        s.tol = tolFor(valueField.getText());
        boolean pause = pauseBox.isSelected();
        Win.Proc p = proc;
        runBg(m == Scanner.Mode.UNKNOWN ? "Copiando la memoria del juego" : "Escaneando",
                () -> withPause(p, pause, () -> { s.firstScan(m, v[0], v[1], this::setProg); return s; }),
                r -> { scanner = r; updateScanUi(); showScanResults(); });
    }

    void nextScan() {
        if (scanner == null || !needProc()) return;
        Scanner.Mode m = (Scanner.Mode) modeBox.getSelectedItem();
        double[] v = readScanValues(scanner.type, m);
        if (v == null) return;
        if (m.values >= 1) scanner.tol = tolFor(valueField.getText());
        boolean pause = pauseBox.isSelected();
        Win.Proc p = proc;
        Scanner s = scanner;
        runBg("Escaneando", () -> withPause(p, pause, () -> { s.nextScan(m, v[0], v[1], this::setProg); return s; }),
                r -> { updateScanUi(); showScanResults(); });
    }

    void undoScan() {
        if (scanner == null || busy) return;
        scanner.undo();
        updateScanUi();
        showScanResults();
    }

    void newScan() {
        if (busy) return;
        if (scanner != null) scanner.close();
        scanner = null;
        shownAddrs = new long[0];
        scanModel.setRowCount(0);
        updateScanUi();
        if (proc != null) status.setText(" Listo para un nuevo escaneo.");
    }

    void showScanResults() {
        // Mientras un escaneo corre en segundo plano, el estado del escáner está cambiando: no leerlo.
        // Al terminar, runBg vuelve a llamar a este método.
        if (busy) return;
        scanModel.setRowCount(0);
        if (scanner == null || !scanner.hasResults()) return;
        long total = scanner.count();
        shownAddrs = scanner.firstAddresses(MAX_SHOWN);
        for (long a : shownAddrs) scanModel.addRow(new Object[]{addrText(a), readVal(a, scanner.type)});
        String extra = total > MAX_SHOWN ? " (mostrando los primeros " + MAX_SHOWN + ")" : "";
        String hint = scanner.isSnapshot() ? " Cambia el valor en el juego y usa Aumentó / Disminuyó / Cambió / Sin cambio." : "";
        status.setText(String.format(" %,d resultados%s.%s", total, extra, hint));
    }

    String readVal(long addr, ValueType t) {
        if (proc == null || addr == Win.BAD) return "??";
        byte[] raw = proc.read(addr, t.size);
        return raw == null ? "??" : fmtVal(t, t.decode(raw));
    }

    void writeScanSelected() {
        int[] rows = scanTable.getSelectedRows();
        if (rows.length == 0 || scanner == null) { JOptionPane.showMessageDialog(this, "Selecciona direcciones de la lista."); return; }
        byte[] data;
        try { data = scanner.type.encode(parseVal(scanner.type, writeField.getText())); }
        catch (NumberFormatException ex) { error("Valor inválido para este tipo."); return; }
        for (int r : rows) proc.write(shownAddrs[r], data);
        showScanResults();
    }

    void addScanToTable() {
        int[] rows = scanTable.getSelectedRows();
        if (rows.length == 0 || scanner == null) { JOptionPane.showMessageDialog(this, "Selecciona direcciones de la lista."); return; }
        String name = JOptionPane.showInputDialog(this, "Nombre para la(s) entrada(s):", "Dinero");
        if (name == null) return;
        boolean dynamic = false;
        for (int r : rows) {
            cheats.add(new CheatEntry(name, AddressExpr.of(proc, shownAddrs[r]), scanner.type));
            dynamic |= proc.moduleAt(shownAddrs[r]) == null;
        }
        cheatModel.fireTableDataChanged();
        status.setText(" Agregado a la tabla." + (dynamic
                ? " Ojo: es una dirección dinámica, cambiará al reiniciar el juego. Usa «Buscar pointers» para una permanente." : ""));
    }

    void scanToPointers() {
        int row = scanTable.getSelectedRow();
        if (row < 0 || scanner == null) { JOptionPane.showMessageDialog(this, "Selecciona una dirección de la lista."); return; }
        ptrTarget.setText(AddressExpr.hex(shownAddrs[row]));
        ptrType.setSelectedItem(scanner.type);
        tabs.setSelectedIndex(2);
    }

    // ================================================================ pestaña Tabla de trucos
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
                default -> readText(e.address.resolve(proc), e.type);
            };
        }

        @Override public void setValueAt(Object v, int r, int c) {
            CheatEntry e = cheats.get(r);
            if (c == 1) e.description = String.valueOf(v);
            else if (c == 0) {
                if (Boolean.TRUE.equals(v)) {
                    long a = e.address.resolve(proc);
                    byte[] raw = a == Win.BAD ? null : proc.read(a, e.type.size);
                    if (raw == null) { status.setText(" No se pudo leer esa dirección (¿abriste el juego?)."); return; }
                    e.frozenValue = raw;
                } else e.frozenValue = null;
            } else if (c == 4) {
                try {
                    byte[] data = e.type.encode(e.type.parse(String.valueOf(v)));
                    long a = e.address.resolve(proc);
                    if (a == Win.BAD || !proc.write(a, data)) { status.setText(" No se pudo escribir en esa dirección."); return; }
                    if (e.frozenValue != null) e.frozenValue = data;
                } catch (NumberFormatException ex) { error("Valor inválido para " + e.type.label); }
            }
            fireTableRowsUpdated(r, r);
        }
    }

    JComponent buildCheatTab() {
        cheatTable.getColumnModel().getColumn(0).setMaxWidth(70);
        cheatTable.getColumnModel().getColumn(3).setMaxWidth(80);
        cheatTable.getColumnModel().getColumn(2).setPreferredWidth(300);
        JPanel south = new JPanel(new GridLayout(0, 1));
        south.add(row(button("Agregar dirección...", this::addManualEntry), button("Eliminar", this::deleteCheats),
                button("Buscar pointers de esta", this::cheatToPointers), button("Ver en memoria", () -> {
                    int r = cheatTable.getSelectedRow();
                    long a = r < 0 ? Win.BAD : cheats.get(r).address.resolve(proc);
                    if (a != Win.BAD) showInMemory(a);
                })));
        south.add(row(button("Abrir tabla (.CT)...", this::loadTable), button("Guardar tabla", () -> saveTable(false)),
                button("Guardar como...", () -> saveTable(true))));
        JLabel help = new JLabel(" Doble clic en Descripción o Valor para editarlos. Marca «Congelar» para fijar el valor.");
        JPanel p = new JPanel(new BorderLayout());
        p.add(help, BorderLayout.NORTH);
        p.add(new JScrollPane(cheatTable), BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

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
            cheats.add(new CheatEntry(desc.getText().trim(), AddressExpr.parse(base.getText(), offs.getText()), (ValueType) type.getSelectedItem()));
            cheatModel.fireTableDataChanged();
        } catch (Exception ex) { error(ex.getMessage()); }
    }

    void deleteCheats() {
        int[] rows = cheatTable.getSelectedRows();
        if (rows.length == 0) return;
        if (cheatTable.isEditing()) cheatTable.getCellEditor().cancelCellEditing();
        List<CheatEntry> victims = new ArrayList<>();
        for (int r : rows) victims.add(cheats.get(r));
        cheats.removeAll(victims);
        cheatModel.fireTableDataChanged();
    }

    void cheatToPointers() {
        int r = cheatTable.getSelectedRow();
        if (r < 0 || proc == null) { JOptionPane.showMessageDialog(this, "Abre el juego y selecciona una entrada."); return; }
        CheatEntry e = cheats.get(r);
        long a = e.address.resolve(proc);
        if (a == Win.BAD) { error("Esa entrada no apunta a nada válido ahora mismo."); return; }
        ptrTarget.setText(AddressExpr.hex(a));
        ptrType.setSelectedItem(e.type);
        tabs.setSelectedIndex(2);
    }

    JFileChooser chooser(String filterName, String ext) {
        JFileChooser fc = new JFileChooser(prefs.get("lastDir", System.getProperty("user.home")));
        fc.setFileFilter(new FileNameExtensionFilter(filterName, ext));
        return fc;
    }

    void loadTable() {
        JFileChooser fc = chooser("Tablas de Cheat Engine (*.CT)", "ct");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        prefs.put("lastDir", f.getParent());
        try {
            CtFile.LoadResult res = CtFile.load(f);
            if (!cheats.isEmpty() && JOptionPane.showConfirmDialog(this, "¿Reemplazar la tabla actual? (No = agregar al final)",
                    "Abrir tabla", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) cheats.clear();
            cheats.addAll(res.entries());
            cheatFile = f;
            cheatModel.fireTableDataChanged();
            String msg = "Se cargaron " + res.entries().size() + " entradas de " + f.getName() + ".";
            if (res.skipped() > 0) msg += "\n" + res.skipped() + " entradas se omitieron porque usan scripts de Cheat Engine "
                    + "(Auto Assembler / Lua) u otros tipos que MiniScanner no soporta.";
            JOptionPane.showMessageDialog(this, msg);
        } catch (Exception ex) { error("No se pudo abrir la tabla:\n" + ex.getMessage()); }
    }

    void saveTable(boolean saveAs) {
        if (cheatTable.isEditing()) cheatTable.getCellEditor().stopCellEditing();
        File f = cheatFile;
        if (f == null || saveAs) {
            JFileChooser fc = chooser("Tablas de Cheat Engine (*.CT)", "ct");
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            f = fc.getSelectedFile();
            if (!f.getName().toLowerCase().endsWith(".ct")) f = new File(f.getPath() + ".CT");
        }
        try {
            CtFile.save(f, cheats);
            cheatFile = f;
            prefs.put("lastDir", f.getParent());
            long dynamic = cheats.stream().filter(e -> e.address.module == null).count();
            status.setText(" Tabla guardada: " + f.getName() + (dynamic > 0
                    ? " (" + dynamic + " entradas son direcciones dinámicas y no funcionarán al reiniciar el juego)" : ""));
        } catch (Exception ex) { error("No se pudo guardar:\n" + ex.getMessage()); }
    }

    void freezeLoop() {
        while (true) {
            Win.Proc p = proc;
            if (p != null) for (CheatEntry e : cheats) {
                byte[] v = e.frozenValue;
                if (v == null) continue;
                long a = e.address.resolve(p);
                if (a != Win.BAD) p.write(a, v);
            }
            try { Thread.sleep(100); } catch (InterruptedException ex) { return; }
        }
    }

    // ================================================================ pestaña Pointers
    JComponent buildPointerTab() {
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
        JPanel p = new JPanel(new BorderLayout());
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(ptrTable), BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    long parseHex(String s) { return Long.parseUnsignedLong(s.trim().replaceFirst("^(?i)0x", ""), 16); }

    void scanPointers() {
        if (!needProc()) return;
        long target, maxOff;
        try { target = parseHex(ptrTarget.getText()); maxOff = parseHex(ptrMaxOffset.getText()); }
        catch (NumberFormatException ex) { error("Escribe la dirección y el offset máximo en hexadecimal."); return; }
        int level = (Integer) ptrLevel.getValue();
        Win.Proc p = proc;
        runBg("Leyendo memoria", () -> PointerScanner.scan(p, target, level, maxOff, this::setProg, s -> phase = s),
                list -> { pointers = list; showPointers(); });
    }

    void filterPtrByAddress() {
        if (!needProc() || pointers.isEmpty()) return;
        long target;
        try { target = parseHex(ptrTarget.getText()); } catch (NumberFormatException ex) { error("Dirección inválida."); return; }
        Win.Proc p = proc;
        List<AddressExpr> src = pointers;
        runBg("Filtrando", () -> PointerScanner.filterByAddress(p, src, target, this::setProg), list -> { pointers = list; showPointers(); });
    }

    void filterPtrByValue() {
        if (!needProc() || pointers.isEmpty()) return;
        ValueType t = (ValueType) ptrType.getSelectedItem();
        double v;
        try { v = t.parse(ptrValue.getText()); } catch (NumberFormatException ex) { error("Valor inválido."); return; }
        Win.Proc p = proc;
        List<AddressExpr> src = pointers;
        runBg("Filtrando", () -> PointerScanner.filterByValue(p, src, t, v, this::setProg), list -> { pointers = list; showPointers(); });
    }

    void showPointers() {
        ptrModel.setRowCount(0);
        ValueType t = (ValueType) ptrType.getSelectedItem();
        int count = Math.min(pointers.size(), MAX_SHOWN);
        for (int i = 0; i < count; i++) {
            AddressExpr e = pointers.get(i);
            long a = e.resolve(proc);
            ptrModel.addRow(new Object[]{e.baseText(), e.offsetsText(), a == Win.BAD ? "??" : AddressExpr.hex(a), readText(a, t)});
        }
        String extra = pointers.size() > MAX_SHOWN ? " (mostrando los primeros " + MAX_SHOWN + ")" : "";
        status.setText(String.format(" %,d pointers%s.", pointers.size(), extra));
    }

    void addPtrToTable() {
        int[] rows = ptrTable.getSelectedRows();
        if (rows.length == 0) { JOptionPane.showMessageDialog(this, "Selecciona uno o más pointers."); return; }
        String name = JOptionPane.showInputDialog(this, "Nombre para la(s) entrada(s):", "Dinero");
        if (name == null) return;
        for (int r : rows) cheats.add(new CheatEntry(name, pointers.get(r), (ValueType) ptrType.getSelectedItem()));
        cheatModel.fireTableDataChanged();
        status.setText(" Agregado a la tabla de trucos. No olvides guardar la tabla.");
    }

    void savePtrList() {
        if (pointers.isEmpty()) return;
        JFileChooser fc = chooser("Listas de pointers (*.ptrs)", "ptrs");
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".ptrs")) f = new File(f.getPath() + ".ptrs");
        try {
            List<String> lines = new ArrayList<>(pointers.size());
            for (AddressExpr e : pointers) lines.add(e.serialize());
            Files.write(f.toPath(), lines, StandardCharsets.UTF_8);
            prefs.put("lastDir", f.getParent());
            status.setText(" Lista guardada: " + f.getName());
        } catch (Exception ex) { error("No se pudo guardar:\n" + ex.getMessage()); }
    }

    void loadPtrList() {
        JFileChooser fc = chooser("Listas de pointers (*.ptrs)", "ptrs");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            List<AddressExpr> list = new ArrayList<>();
            for (String line : Files.readAllLines(fc.getSelectedFile().toPath(), StandardCharsets.UTF_8))
                if (!line.isBlank()) list.add(AddressExpr.deserialize(line.trim()));
            pointers = list;
            prefs.put("lastDir", fc.getSelectedFile().getParent());
            showPointers();
        } catch (Exception ex) { error("No se pudo cargar:\n" + ex.getMessage()); }
    }

    // ================================================================ pestaña Memoria
    final class MemModel extends AbstractTableModel {
        public int getRowCount() { return MEM_ROWS; }
        public int getColumnCount() { return 18; }
        @Override public String getColumnName(int c) {
            return c == 0 ? "Dirección" : c == 17 ? "Texto" : String.format("%X", c - 1);
        }
        @Override public boolean isCellEditable(int r, int c) { return c >= 1 && c <= 16 && memOk[r * 16 + c - 1]; }

        public Object getValueAt(int r, int c) {
            if (c == 0) return addrText(memBase + r * 16L);
            if (c == 17) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 16; i++) {
                    int idx = r * 16 + i;
                    int b = memData[idx] & 0xFF;
                    sb.append(!memOk[idx] ? ' ' : b >= 32 && b < 127 ? (char) b : '.');
                }
                return sb.toString();
            }
            int idx = r * 16 + c - 1;
            return memOk[idx] ? String.format("%02X", memData[idx] & 0xFF) : "??";
        }

        @Override public void setValueAt(Object v, int r, int c) {
            try {
                int b = Integer.parseInt(String.valueOf(v).trim(), 16);
                if (b < 0 || b > 255) throw new NumberFormatException();
                proc.write(memBase + r * 16L + c - 1, new byte[]{(byte) b});
                refreshMem();
            } catch (NumberFormatException ex) { error("Escribe un byte en hexadecimal (00 a FF)."); }
        }
    }

    JComponent buildMemoryTab() {
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        memTable.setFont(mono);
        memTable.setRowHeight(memTable.getFontMetrics(mono).getHeight() + 4);
        memTable.setCellSelectionEnabled(true);
        memTable.getTableHeader().setReorderingAllowed(false);
        memTable.getColumnModel().getColumn(0).setPreferredWidth(190);
        for (int c = 1; c <= 16; c++) memTable.getColumnModel().getColumn(c).setPreferredWidth(28);
        memTable.getColumnModel().getColumn(17).setPreferredWidth(150);
        javax.swing.table.DefaultTableCellRenderer render = new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                boolean recent = c >= 1 && c <= 16 && System.currentTimeMillis() - memChanged[r * 16 + c - 1] < 1500;
                comp.setForeground(recent ? Color.RED : sel ? t.getSelectionForeground() : t.getForeground());
                comp.setFont(recent ? mono.deriveFont(Font.BOLD) : mono);
                setHorizontalAlignment(c >= 1 && c <= 16 ? CENTER : LEFT);
                return comp;
            }
        };
        for (int c = 0; c < 18; c++) memTable.getColumnModel().getColumn(c).setCellRenderer(render);
        memTable.getSelectionModel().addListSelectionListener(e -> updateMemInfo());
        memTable.getColumnModel().getSelectionModel().addListSelectionListener(e -> updateMemInfo());
        memType.addActionListener(e -> updateMemInfo());

        JPanel north = new JPanel(new GridLayout(0, 1));
        north.add(row(new JLabel("Dirección:"), memAddr, button("Ir", this::memGo),
                button("▲", () -> memMove(-0x10 * 8)), button("▼", () -> memMove(0x10 * 8)),
                button("◀ Página", () -> memMove(-MEM_BYTES)), button("Página ▶", () -> memMove(MEM_BYTES)), memAuto));
        JLabel help = new JLabel(" Los bytes que cambian se pintan de rojo. Doble clic en un byte para editarlo (en hex).");
        north.add(help);
        JPanel south = new JPanel(new GridLayout(0, 1));
        memInfo.setFont(mono);
        south.add(memInfo);
        south.add(row(new JLabel("Tipo:"), memType, button("Agregar dirección seleccionada a la tabla", this::memAddToTable)));
        JPanel p = new JPanel(new BorderLayout());
        p.add(north, BorderLayout.NORTH);
        p.add(new JScrollPane(memTable), BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    void showInMemory(long addr) {
        memAddr.setText(AddressExpr.hex(addr));
        memBase = Math.max(0, (addr & ~0xFL) - 0x40);
        Arrays.fill(memChanged, 0);
        refreshMem(true);
        tabs.setSelectedIndex(3);
        int idx = (int) (addr - memBase);
        if (idx >= 0 && idx < MEM_BYTES) memTable.changeSelection(idx / 16, idx % 16 + 1, false, false);
    }

    void memGo() {
        if (proc == null) { error("Primero abre el proceso del juego."); return; }
        try {
            long a = AddressExpr.parse(memAddr.getText(), "").resolve(proc);
            if (a == Win.BAD) { error("No encontré ese módulo en el juego."); return; }
            showInMemory(a);
        } catch (IllegalArgumentException ex) { error("Escribe una dirección como 7FF6A1B2C3D0 o \"Juego.exe\"+1A2B"); }
    }

    void memMove(long delta) {
        memBase = Math.max(0, memBase + delta);
        Arrays.fill(memChanged, 0);
        refreshMem(true);
    }

    void refreshMem() { refreshMem(false); }

    void refreshMem(boolean fresh) {
        byte[] all = proc == null ? null : proc.read(memBase, MEM_BYTES);
        long now = System.currentTimeMillis();
        for (int r = 0; r < MEM_ROWS; r++) {
            byte[] row = all != null ? Arrays.copyOfRange(all, r * 16, r * 16 + 16)
                    : proc == null ? null : proc.read(memBase + r * 16L, 16);
            for (int i = 0; i < 16; i++) {
                int idx = r * 16 + i;
                boolean ok = row != null;
                if (ok && memOk[idx] && !fresh && memData[idx] != row[i]) memChanged[idx] = now;
                memOk[idx] = ok;
                if (ok) memData[idx] = row[i];
            }
        }
        memModel.fireTableRowsUpdated(0, MEM_ROWS - 1);
        updateMemInfo();
    }

    long memSelected() {
        int r = memTable.getSelectedRow(), c = memTable.getSelectedColumn();
        if (r < 0) return Win.BAD;
        return memBase + r * 16L + (c >= 1 && c <= 16 ? c - 1 : 0);
    }

    void updateMemInfo() {
        long a = memSelected();
        if (a == Win.BAD || proc == null) { memInfo.setText(" "); return; }
        byte[] raw = proc.read(a, 8);
        if (raw == null) { memInfo.setText(" " + addrText(a) + ": no se puede leer"); return; }
        StringBuilder sb = new StringBuilder(" " + addrText(a) + "  →");
        for (ValueType t : ValueType.values()) sb.append("  ").append(t.ctName).append(": ").append(t.format(t.decode(raw)));
        memInfo.setText(sb.toString());
    }

    void memAddToTable() {
        long a = memSelected();
        if (a == Win.BAD || proc == null) { JOptionPane.showMessageDialog(this, "Selecciona un byte en la tabla."); return; }
        String name = JOptionPane.showInputDialog(this, "Nombre para la entrada:", "Sin nombre");
        if (name == null) return;
        cheats.add(new CheatEntry(name, AddressExpr.of(proc, a), (ValueType) memType.getSelectedItem()));
        cheatModel.fireTableDataChanged();
        status.setText(" Agregado a la tabla de trucos.");
    }

    // ================================================================ main
    public static void main(String[] args) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            System.err.println("Este programa solo funciona en Windows.");
            return;
        }
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> {
            MiniScanner app = new MiniScanner();
            app.setVisible(true);
            // Si el programa se cierra a la mitad de un escaneo con pausa, que el juego no se quede congelado
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Win.Proc p = app.proc;
                if (p != null) p.resume();
                if (app.scanner != null) app.scanner.close();
            }));
        });
    }
}
