package miniscanner;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Tablas .CT (el formato XML de Cheat Engine).
 * Solo se importan entradas simples: direcciones y pointers. Los scripts (Auto Assembler / Lua)
 * y los tipos raros (texto, arreglos de bytes...) se omiten: MiniScanner nunca ejecuta código de una tabla.
 */
final class CtFile {
    private CtFile() {}

    record LoadResult(List<CheatEntry> entries, int skipped) {}

    static LoadResult load(File file) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setExpandEntityReferences(false);
        Document doc = f.newDocumentBuilder().parse(file);
        Element root = doc.getDocumentElement();
        if (!"CheatTable".equals(root.getNodeName())) throw new IllegalArgumentException("No parece una tabla de Cheat Engine.");
        List<CheatEntry> out = new ArrayList<>();
        int[] skipped = {0};
        Element entries = child(root, "CheatEntries");
        if (entries != null) readEntries(entries, "", out, skipped);
        return new LoadResult(out, skipped[0]);
    }

    private static void readEntries(Element entries, String prefix, List<CheatEntry> out, int[] skipped) {
        for (Node n = entries.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e) || !"CheatEntry".equals(e.getNodeName())) continue;
            String desc = unquote(text(e, "Description"));
            if (desc == null || desc.isBlank()) desc = "Sin nombre";
            String full = prefix.isEmpty() ? desc : prefix + " / " + desc;

            boolean isScript = child(e, "AssemblerScript") != null;
            String addr = text(e, "Address");
            ValueType type = ValueType.fromCt(text(e, "VariableType"));
            if (isScript) skipped[0]++;
            else if (addr != null) {
                try {
                    List<String> offs = new ArrayList<>();
                    Element offsets = child(e, "Offsets");
                    if (offsets != null)
                        for (Node o = offsets.getFirstChild(); o != null; o = o.getNextSibling())
                            if (o instanceof Element oe && "Offset".equals(oe.getNodeName())) offs.add(0, oe.getTextContent().trim());
                    // En los .CT los offsets van del último al primero, por eso se insertan al revés
                    if (type == null) throw new IllegalArgumentException("tipo no soportado");
                    out.add(new CheatEntry(full, AddressExpr.parse(addr, String.join(",", offs)), type));
                } catch (Exception ex) {
                    skipped[0]++;
                }
            }
            Element kids = child(e, "CheatEntries");
            if (kids != null) readEntries(kids, addr == null || isScript ? full : prefix, out, skipped);
        }
    }

    static void save(File file, List<CheatEntry> entries) throws Exception {
        DocumentBuilder b = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = b.newDocument();
        Element root = doc.createElement("CheatTable");
        root.setAttribute("CheatEngineTableVersion", "45");
        doc.appendChild(root);
        Element list = doc.createElement("CheatEntries");
        root.appendChild(list);
        int id = 0;
        for (CheatEntry ce : entries) {
            Element e = doc.createElement("CheatEntry");
            add(doc, e, "ID", String.valueOf(id++));
            add(doc, e, "Description", "\"" + ce.description + "\"");
            add(doc, e, "VariableType", ce.type.ctName);
            add(doc, e, "Address", ce.address.baseText());
            if (ce.address.isPointer()) {
                Element offs = doc.createElement("Offsets");
                long[] o = ce.address.offsets;
                for (int i = o.length - 1; i >= 0; i--) add(doc, offs, "Offset", AddressExpr.hex(o[i]));
                e.appendChild(offs);
            }
            list.appendChild(e);
        }
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty(OutputKeys.ENCODING, "utf-8");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        t.transform(new DOMSource(doc), new StreamResult(file));
    }

    // ------------------------------------------------------------ helpers XML
    private static Element child(Element e, String name) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element c && name.equals(c.getNodeName())) return c;
        return null;
    }

    private static String text(Element e, String name) {
        Element c = child(e, name);
        return c == null ? null : c.getTextContent().trim();
    }

    private static void add(Document doc, Element parent, String name, String value) {
        Element c = doc.createElement(name);
        c.setTextContent(value);
        parent.appendChild(c);
    }

    private static String unquote(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }
}
