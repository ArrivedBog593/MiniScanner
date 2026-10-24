package miniscanner;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import static miniscanner.Ui.*;

/** Pestaña Escáner: primer escaneo, siguientes, deshacer y acciones sobre los resultados. */
final class ScannerTab extends JPanel {
    static final String HOLD_OFF = "⏸ Pausar juego", HOLD_ON = "▶ Reanudar juego";

    private final MiniScanner app;
    Scanner scanner;
    private long[] shownAddrs = new long[0];
    /** Cuándo cambió por última vez el valor de cada fila (para pintarlo de rojo un momento). */
    private long[] changedAt = new long[0];
    private final JCheckBox autoBox = new JCheckBox("Auto-actualizar valores", true);

    private final JTextField valueField = new JTextField(10), value2Field = new JTextField(10), writeField = new JTextField(10);
    private final JLabel value2Label = new JLabel("y");
    private final JComboBox<ValueType> typeBox = new JComboBox<>(ValueType.values());
    private final JComboBox<Scanner.Mode> modeBox = new JComboBox<>(Scanner.Mode.FIRST);
    private final JCheckBox hexBox = new JCheckBox("Hex"), pauseBox = new JCheckBox("Pausar el juego al escanear");
    /** Congela el juego hasta que lo sueltes (para valores que cambian rápido). */
    private final JToggleButton holdBtn = new JToggleButton(HOLD_OFF);
    private final JButton firstBtn = new JButton("Primer escaneo"), nextBtn = new JButton("Siguiente escaneo"),
            undoBtn = new JButton("Deshacer último escaneo");
    private final DefaultTableModel scanModel = readOnlyModel("Dirección", "Valor actual");
    private final JTable scanTable = new JTable(scanModel);

    ScannerTab(MiniScanner app) {
        super(new BorderLayout());
        this.app = app;
        typeBox.setSelectedItem(ValueType.INT4);
        modeBox.addActionListener(e -> updateValueFields());
        firstBtn.addActionListener(e -> {
            if (app.busy) return;
            if (scanner != null && scanner.hasResults()) newScan(); else firstScan();
        });
        nextBtn.addActionListener(e -> nextScan());
        undoBtn.addActionListener(e -> undoScan());
        hexBox.addActionListener(e -> showScanResults());
        holdBtn.addActionListener(e -> toggleHold());
        holdBtn.setToolTipText("Congela el juego entero para escanear valores que cambian todo el tiempo. Funciona con su tecla desde dentro del juego.");
        updateHoldText();
        JPanel north = new JPanel(new GridLayout(0, 1));
        north.add(row(new JLabel("Modo:"), modeBox, new JLabel("Valor:"), valueField, value2Label, value2Field, hexBox));
        north.add(row(new JLabel("Tipo:"), typeBox, pauseBox, holdBtn, button("Tecla...", this::editPauseKey)));
        north.add(row(firstBtn, nextBtn, undoBtn));
        JPanel south = new JPanel(new GridLayout(0, 1));
        south.add(row(new JLabel("Nuevo valor:"), writeField, button("Escribir en seleccionados", this::writeScanSelected),
                button("Actualizar lista", this::showScanResults), autoBox));
        south.add(row(button("Agregar a la tabla", this::addScanToTable), button("Buscar pointers", this::scanToPointers),
                button("Ver en memoria", () -> { int r = scanTable.getSelectedRow(); if (r >= 0) app.memoryTab.showAddress(shownAddrs[r]); })));
        add(north, BorderLayout.NORTH);
        add(new JScrollPane(scanTable), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        updateScanUi();

        scanTable.getColumnModel().getColumn(1).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                boolean recent = r < changedAt.length && System.currentTimeMillis() - changedAt[r] < 1500;
                comp.setForeground(recent ? Color.RED : sel ? t.getSelectionForeground() : t.getForeground());
                return comp;
            }
        });
        new Timer(500, e -> {
            if (isShowing() && autoBox.isSelected() && !app.busy) refreshValues();
        }).start();
    }

    /** Vuelve a leer el valor de las filas que se ven en pantalla (sin repetir el escaneo). */
    private void refreshValues() {
        Scanner s = scanner;
        if (s == null || shownAddrs.length == 0) return;
        Rectangle vr = scanTable.getVisibleRect();
        int first = Math.max(0, scanTable.rowAtPoint(vr.getLocation()));
        int last = scanTable.rowAtPoint(new Point(vr.x, vr.y + vr.height - 1));
        if (last < 0) last = scanModel.getRowCount() - 1;
        long now = System.currentTimeMillis();
        boolean recent = false;
        for (int r = first; r <= last && r < shownAddrs.length; r++) {
            String v = readVal(shownAddrs[r], s.type);
            if (!v.equals(scanModel.getValueAt(r, 1))) {
                scanModel.setValueAt(v, r, 1);
                changedAt[r] = now;
            }
            recent |= now - changedAt[r] < 1500;
        }
        if (recent) scanTable.repaint(); // para que el rojo se apague solo
    }

    /** Se abrió otro proceso: el escaneo y la pausa eran del anterior. */
    void processChanged() {
        closeScanner();
        holdBtn.setSelected(false);
        updateHoldText();
        newScan();
    }

    void closeScanner() {
        if (scanner != null) { scanner.close(); scanner = null; }
    }

    // ------------------------------------------------------------ formulario
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
        if (hexBox.isSelected() && !t.isFloat) return t.fromLong(Long.parseUnsignedLong(s.replaceFirst("(?i)^0x", ""), 16));
        return t.parse(s);
    }

    String fmtVal(ValueType t, double v) {
        if (hexBox.isSelected() && !t.isFloat) {
            long mask = t.size == 8 ? -1L : (1L << (t.size * 8)) - 1;
            return Long.toHexString((long) v & mask).toUpperCase();
        }
        return t.format(v);
    }

    /** Lee los valores del formulario. Devuelve null (y avisa) si algo no es válido. */
    double[] readScanValues(ValueType t, Scanner.Mode m) {
        try {
            double v1 = m.values >= 1 ? parseVal(t, valueField.getText()) : 0;
            double v2 = m.values == 2 ? parseVal(t, value2Field.getText()) : 0;
            return new double[]{v1, v2};
        } catch (NumberFormatException ex) {
            app.error("Escribe un número válido" + (m.values == 2 ? " en los dos campos." : "."));
            return null;
        }
    }

    // ------------------------------------------------------------ pausa
    static <T> T withPause(Win.Proc p, boolean pause, Callable<T> c) throws Exception {
        // Si el juego ya estaba pausado a mano, el escaneo no lo reanuda al terminar.
        boolean alreadyPaused = p.suspended;
        if (pause && !alreadyPaused) p.suspend();
        try { return c.call(); } finally { if (pause && !alreadyPaused) p.resume(); }
    }

    /** Botón de pausa manual: congela el juego hasta que se vuelva a pulsar. */
    void toggleHold() {
        Win.Proc proc = app.proc;
        if (holdBtn.isSelected()) {
            if (proc == null) {
                holdBtn.setSelected(false);
                app.error("Primero elige el proceso del juego y presiona Abrir.");
                return;
            }
            proc.suspend();
            app.setStatus(" Juego pausado. Haz el escaneo y reanúdalo cuando quieras.");
        } else {
            if (proc != null) proc.resume();
            app.setStatus(" Juego reanudado.");
        }
        updateHoldText();
    }

    /** La tecla de pausa: como pulsar el botón, pero sin diálogos (el usuario está dentro del juego). */
    void holdHotkey() {
        if (app.proc == null) { app.setStatus(" Tecla de pausa: primero abre el proceso del juego."); return; }
        holdBtn.doClick();
    }

    private void updateHoldText() {
        int c = app.pauseCombo();
        holdBtn.setText((holdBtn.isSelected() ? HOLD_ON : HOLD_OFF) + (c == 0 ? "" : " (" + Hotkey.keyText(c >> 8, c & 0xFF) + ")"));
    }

    void editPauseKey() {
        int c = app.pauseCombo();
        int[] key = {c >> 8, c & 0xFF};
        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.add(new JLabel("Tecla para pausar y reanudar el juego desde dentro del juego:"));
        form.add(keyCaptureField(key));
        form.add(new JLabel("Pulsa la combinación (F1-F12, letras, números, numpad o Pausa Inter). Supr o Retroceso para quitarla."));
        app.releaseHotkeys(); // para poder pulsar la tecla actual
        try {
            if (JOptionPane.showConfirmDialog(this, form, "Tecla de pausa", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            int nc = key[1] == 0 ? 0 : Hotkey.combo(key[0], key[1]);
            if (nc != 0 && app.cheatTab.hotkeyCombos().contains(nc)) {
                app.error(Hotkey.keyText(key[0], key[1]) + " ya la usa una entrada de la tabla de trucos. Elige otra.");
                return;
            }
            app.setPauseCombo(nc);
            updateHoldText();
            app.setStatus(nc == 0 ? " Tecla de pausa quitada." : " Tecla de pausa: " + Hotkey.keyText(key[0], key[1]) + ".");
        } finally {
            app.updateHotkeys();
        }
    }

    // ------------------------------------------------------------ escaneos
    void firstScan() {
        if (!app.needProc()) return;
        ValueType type = (ValueType) typeBox.getSelectedItem();
        Scanner.Mode m = (Scanner.Mode) modeBox.getSelectedItem();
        double[] v = readScanValues(type, m);
        if (v == null) return;
        Win.Proc p = app.proc;
        Scanner s = new Scanner(p, type);
        s.tol = ValueType.tolFor(valueField.getText());
        boolean pause = pauseBox.isSelected();
        app.runBg(m == Scanner.Mode.UNKNOWN ? "Copiando la memoria del juego" : "Escaneando",
                () -> withPause(p, pause, () -> { s.firstScan(m, v[0], v[1], app::setProg); return s; }),
                r -> { scanner = r; updateScanUi(); showScanResults(); });
    }

    void nextScan() {
        if (scanner == null || !app.needProc()) return;
        Scanner.Mode m = (Scanner.Mode) modeBox.getSelectedItem();
        double[] v = readScanValues(scanner.type, m);
        if (v == null) return;
        if (m.values >= 1) scanner.tol = ValueType.tolFor(valueField.getText());
        boolean pause = pauseBox.isSelected();
        Win.Proc p = app.proc;
        Scanner s = scanner;
        app.runBg("Escaneando", () -> withPause(p, pause, () -> { s.nextScan(m, v[0], v[1], app::setProg); return s; }),
                r -> { updateScanUi(); showScanResults(); });
    }

    void undoScan() {
        if (scanner == null || app.busy) return;
        scanner.undo();
        updateScanUi();
        showScanResults();
    }

    void newScan() {
        if (app.busy) return;
        closeScanner();
        shownAddrs = new long[0];
        changedAt = new long[0];
        scanModel.setRowCount(0);
        updateScanUi();
        if (app.proc != null) app.setStatus(" Listo para un nuevo escaneo.");
    }

    void showScanResults() {
        // Mientras un escaneo corre en segundo plano, el estado del escáner está cambiando: no leerlo.
        // Al terminar, runBg vuelve a llamar a este método.
        if (app.busy) return;
        scanModel.setRowCount(0);
        if (scanner == null || !scanner.hasResults()) return;
        long total = scanner.count();
        shownAddrs = scanner.firstAddresses(MiniScanner.MAX_SHOWN);
        changedAt = new long[shownAddrs.length];
        for (long a : shownAddrs) scanModel.addRow(new Object[]{app.addrText(a), readVal(a, scanner.type)});
        String extra = total > MiniScanner.MAX_SHOWN ? " (mostrando los primeros " + MiniScanner.MAX_SHOWN + ")" : "";
        String hint = scanner.isSnapshot() ? " Cambia el valor en el juego y usa Aumentó / Disminuyó / Cambió / Sin cambio." : "";
        app.setStatus(String.format(" %,d resultados%s.%s", total, extra, hint));
    }

    /** Como MiniScanner.readText, pero respetando la casilla Hex. */
    String readVal(long addr, ValueType t) {
        Win.Proc proc = app.proc;
        if (proc == null || addr == Win.BAD) return "??";
        byte[] raw = proc.read(addr, t.size);
        return raw == null ? "??" : fmtVal(t, t.decode(raw));
    }

    // ------------------------------------------------------------ acciones sobre resultados
    void writeScanSelected() {
        int[] rows = scanTable.getSelectedRows();
        if (rows.length == 0 || scanner == null) { app.info("Selecciona direcciones de la lista."); return; }
        byte[] data;
        try { data = scanner.type.encode(parseVal(scanner.type, writeField.getText())); }
        catch (NumberFormatException ex) { app.error("Valor inválido para este tipo."); return; }
        for (int r : rows) app.proc.write(shownAddrs[r], data);
        showScanResults();
    }

    void addScanToTable() {
        int[] rows = scanTable.getSelectedRows();
        if (rows.length == 0 || scanner == null) { app.info("Selecciona direcciones de la lista."); return; }
        String name = JOptionPane.showInputDialog(this, "Nombre para la(s) entrada(s):", "Dinero");
        if (name == null) return;
        Win.Proc proc = app.proc;
        boolean dynamic = false;
        List<CheatEntry> added = new ArrayList<>();
        for (int r : rows) {
            added.add(new CheatEntry(name, AddressExpr.of(proc, shownAddrs[r]), scanner.type));
            dynamic |= proc.moduleAt(shownAddrs[r]) == null;
        }
        app.cheatTab.addAll(added);
        app.setStatus(" Agregado a la tabla." + (dynamic
                ? " Ojo: es una dirección dinámica, cambiará al reiniciar el juego. Usa «Buscar pointers» para una permanente." : ""));
    }

    void scanToPointers() {
        int row = scanTable.getSelectedRow();
        if (row < 0 || scanner == null) { app.info("Selecciona una dirección de la lista."); return; }
        app.pointerTab.setTarget(shownAddrs[row], scanner.type);
    }
}
