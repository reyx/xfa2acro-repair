package global.agnos.pdf;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Normalize & repair AcroForm PDFs by attaching orphan widget annotations to
 * /AcroForm.Fields.
 * Optional: call Aspose Cloud to convert XFA → AcroForm first (if
 * ASPOSE_CLIENT_ID/SECRET are set).
 * Also strips XFA/AcroForm JavaScript so the output contains no running
 * scripts.
 *
 * Usage:
 * java -jar xfa2acro-repair.jar <input.pdf> [output.pdf]
 *
 * Optional (list terminal field names):
 * java -jar xfa2acro-repair.jar --list-fields <file.pdf>
 */
public class ConvertAndRepair {

  public static void main(String[] args) throws Exception {
    if (args.length < 1)
      usageAndExit();

    // Quick field listing
    if (args.length == 2 && "--list-fields".equals(args[0])) {
      listFields(Path.of(args[1]));
      return;
    }
    if (args.length > 2)
      usageAndExit();

    Path in = Path.of(args[0]);
    if (!Files.isReadable(in)) {
      System.err.println("Input not found or unreadable: " + in);
      System.exit(3);
    }
    String outName = (args.length == 2) ? args[1] : deriveOutputName(in.toString());
    Path out = Path.of(outName);

    // 0) If Aspose Cloud creds are present, try converting XFA → AcroForm first.
    boolean convertedByAspose = tryAsposeCloudConvert(in, out);

    if (convertedByAspose) {
      // Even if converted, still run local cleanup/repair (idempotent)
      repairPdfInToOut(out, out);
      System.out.println("Converted (Aspose Cloud) + cleaned & repaired (PDFBox): " + out.toAbsolutePath());
      return;
    }

    // Fallback: local cleanup & repair only (input → output)
    repairPdfInToOut(in, out);
    System.out.println("Cleaned & repaired (PDFBox): " + out.toAbsolutePath());
  }

  /*
   * ============================= Aspose Cloud (optional)
   * =============================
   */

  private static boolean tryAsposeCloudConvert(Path inputPath, Path outputPath) {
    String clientId = System.getenv("ASPOSE_CLIENT_ID");
    String clientSecret = System.getenv("ASPOSE_CLIENT_SECRET");
    if (clientId == null || clientSecret == null) {
      return false; // not configured, skip
    }

    try {
      HttpClient http = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(20)).build();

      // 1) Get OAuth token
      String form = "grant_type=client_credentials"
          + "&client_id=" + URLEncoder.encode(clientId, "UTF-8")
          + "&client_secret=" + URLEncoder.encode(clientSecret, "UTF-8");

      HttpRequest tokenReq = HttpRequest.newBuilder()
          .uri(URI.create("https://api.aspose.cloud/connect/token"))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .header("Accept", "application/json")
          .timeout(Duration.ofSeconds(20))
          .POST(HttpRequest.BodyPublishers.ofString(form))
          .build();

      HttpResponse<String> tokenResp = http.send(tokenReq, HttpResponse.BodyHandlers.ofString());
      if (tokenResp.statusCode() / 100 != 2) {
        System.err.println("Aspose token error: " + tokenResp.statusCode() + " " + tokenResp.body());
        return false;
      }
      String accessToken = extractJsonField(tokenResp.body(), "access_token");
      if (accessToken == null || accessToken.isBlank()) {
        System.err.println("Aspose token missing access_token");
        return false;
      }

      // 2) Convert XFA → AcroForm; request returns the PDF bytes directly
      HttpRequest convReq = HttpRequest.newBuilder()
          .uri(URI.create("https://api.aspose.cloud/v3.0/pdf/convert/xfatoacroform"))
          .header("Authorization", "Bearer " + accessToken)
          .header("Accept", "application/pdf")
          .timeout(Duration.ofMinutes(2))
          .PUT(HttpRequest.BodyPublishers.ofFile(inputPath))
          .build();

      HttpResponse<byte[]> convResp = http.send(convReq, HttpResponse.BodyHandlers.ofByteArray());
      if (convResp.statusCode() / 100 == 2 && convResp.body() != null && convResp.body().length > 0) {
        Files.write(outputPath, convResp.body());
        System.out.println("Aspose Cloud converted XFA → AcroForm: " + outputPath.toAbsolutePath());
        return true;
      } else {
        System.err.println("Aspose conversion failed or returned no content: " + convResp.statusCode());
        return false;
      }
    } catch (Exception e) {
      System.err.println("Aspose conversion error: " + e.getMessage());
      return false;
    }
  }

  private static String extractJsonField(String json, String field) {
    String key = "\"" + field + "\"";
    int i = json.indexOf(key);
    if (i < 0)
      return null;
    int colon = json.indexOf(':', i + key.length());
    if (colon < 0)
      return null;
    int q1 = json.indexOf('"', colon + 1);
    if (q1 < 0)
      return null;
    int q2 = json.indexOf('"', q1 + 1);
    if (q2 < 0)
      return null;
    return json.substring(q1 + 1, q2);
  }

  /*
   * =============================== Local PDF cleanup & repair
   * ===============================
   */

  private static void repairPdfInToOut(Path source, Path dest) throws Exception {
    try (PDDocument doc = Loader.loadPDF(source.toFile())) {
      doc.setAllSecurityToBeRemoved(true);

      // NEW: remove all AcroForm/Doc/Page/Annot JavaScript entry points
      stripAllJavaScript(doc);

      // NEW: remove XFA <event> and <script ...javascript...> from the XFA packets
      stripXfaScripts(doc);

      // Ensure AcroForm + minimal defaults (existing logic)
      PDDocumentCatalog catalog = doc.getDocumentCatalog();
      PDAcroForm acro = catalog.getAcroForm();
      if (acro == null) {
        acro = new PDAcroForm(doc);
        catalog.setAcroForm(acro);
      }
      ensureAcroDefaults(acro);

      // Re-attach orphan widgets to fields (existing logic)
      Map<String, PDField> byName = new HashMap<>();
      for (PDField f : acro.getFields()) {
        byName.put(f.getPartialName(), f);
      }

      for (PDPage page : doc.getPages()) {
        for (PDAnnotation ann : page.getAnnotations()) {
          if (!(ann instanceof PDAnnotationWidget))
            continue;
          PDAnnotationWidget widget = (PDAnnotationWidget) ann;
          COSDictionary wCos = widget.getCOSObject();

          wCos.setItem(COSName.SUBTYPE, COSName.WIDGET);

          COSBase ft = wCos.getDictionaryObject(COSName.FT);
          COSBase t = wCos.getDictionaryObject(COSName.T);
          if (ft == null || t == null)
            continue;

          if (wCos.getDictionaryObject(COSName.PARENT) != null)
            continue;

          String fullName = readName(t);
          if (fullName == null || fullName.isBlank())
            continue;
          if (fullName.startsWith("u:"))
            fullName = fullName.substring(2);

          String pname = partialNameOf(fullName);
          String uniqueName = uniquePartialName(pname, byName);

          PDField field = byName.get(uniqueName);
          if (field == null) {
            field = createConcreteField(acro, ft, uniqueName);
            field.getCOSObject().setItem(COSName.DA, new COSString("/Helv 0 Tf 0 g"));
            acro.getFields().add(field);
            byName.put(uniqueName, field);
          }

          COSArray kids = field.getCOSObject().getCOSArray(COSName.KIDS);
          if (kids == null) {
            kids = new COSArray();
            field.getCOSObject().setItem(COSName.KIDS, kids);
          }
          kids.add(wCos);
          wCos.setItem(COSName.PARENT, field.getCOSObject());
        }
      }

      doc.save(dest.toFile());
    }
  }

  /*
   * ------------------------------- NEW: Strip AcroForm/Document/Page/Annot JS
   * -------------------------------
   */

  private static void stripAllJavaScript(PDDocument doc) throws Exception {
    PDDocumentCatalog catalog = doc.getDocumentCatalog();
    if (catalog == null)
      return;

    COSDictionary cat = catalog.getCOSObject();

    // Document-level actions
    cat.removeItem(COSName.OPEN_ACTION);
    cat.removeItem(COSName.AA);

    // /Names /JavaScript
    COSDictionary names = (COSDictionary) cat.getDictionaryObject(COSName.NAMES);
    if (names != null) {
      names.removeItem(COSName.JAVA_SCRIPT);
      // If Names is empty you could also remove /Names entirely.
    }

    // Page & annotation actions
    for (PDPage page : doc.getPages()) {
      COSDictionary p = page.getCOSObject();
      p.removeItem(COSName.AA);

      List<PDAnnotation> annots = page.getAnnotations();
      if (annots == null)
        continue;
      for (PDAnnotation a : annots) {
        COSDictionary ad = a.getCOSObject();
        ad.removeItem(COSName.AA);
        ad.removeItem(COSName.A);
      }
    }
  }

  /*
   * ------------------------------- NEW: Strip XFA event & script blocks inside
   * /AcroForm /XFA -------------------------------
   */

  private static void stripXfaScripts(PDDocument doc) throws Exception {
    PDDocumentCatalog catalog = doc.getDocumentCatalog();
    if (catalog == null)
      return;
    PDAcroForm acro = catalog.getAcroForm();
    if (acro == null)
      return;

    COSDictionary acroCos = acro.getCOSObject();
    COSBase xfaBase = acroCos.getDictionaryObject(COSName.getPDFName("XFA"));
    if (xfaBase == null)
      return;

    if (xfaBase instanceof COSArray arr) {
      // Array of [name, stream, name, stream, ...]
      for (int i = 0; i + 1 < arr.size(); i += 2) {
        COSBase nameObj = arr.getObject(i);
        COSBase streamObj = arr.getObject(i + 1);
        String partName = (nameObj instanceof COSString cs) ? cs.getString()
            : (nameObj instanceof COSName cn ? cn.getName() : String.valueOf(i));

        // Only some packets hold scripts (template/form/datasets/config)
        String lower = partName.toLowerCase(Locale.ROOT);
        if (!(lower.contains("template") || lower.contains("form") || lower.contains("datasets")
            || lower.contains("config"))) {
          continue;
        }

        if (!(streamObj instanceof COSStream s))
          continue;
        byte[] xmlBytes = IOUtils.toByteArray(s.createInputStream());
        byte[] cleaned = removeXfaEventsAndJs(xmlBytes);
        if (cleaned != null) {
          // Replace stream content (compress with Flate)
          try (var out = s.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(cleaned);
          }
          // Length etc. are handled by PDFBox on write
          System.out.println("Stripped XFA scripts in part: " + partName);
        }
      }
    } else if (xfaBase instanceof COSStream s) {
      byte[] xmlBytes = IOUtils.toByteArray(s.createInputStream());
      byte[] cleaned = removeXfaEventsAndJs(xmlBytes);
      if (cleaned != null) {
        try (var out = s.createOutputStream(COSName.FLATE_DECODE)) {
          out.write(cleaned);
        }
        System.out.println("Stripped XFA scripts in single-stream XFA.");
      }
    }
  }

  /**
   * Remove all <event>…</event> nodes and any <script> whose @contentType
   * contains "javascript"
   * from an XFA XML packet. Returns cleaned UTF-8 bytes, or null if parsing
   * fails.
   */
  private static byte[] removeXfaEventsAndJs(byte[] xmlBytes) {
    try {
      Charset cs = detectXmlCharset(xmlBytes);
      String xml = new String(xmlBytes, cs);

      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setNamespaceAware(false); // XFA often has no namespaces; keep this simple
      dbf.setExpandEntityReferences(false);
      DocumentBuilder db = dbf.newDocumentBuilder();
      Document doc = db.parse(new java.io.ByteArrayInputStream(xml.getBytes(cs)));

      XPath xp = XPathFactory.newInstance().newXPath();

      // 1) Remove all <event> nodes
      NodeList eventNodes = (NodeList) xp.evaluate("//event", doc, XPathConstants.NODESET);
      removeAll(eventNodes);

      // 2) Remove any <script> node with contentType ~= "javascript"
      NodeList scriptNodes = (NodeList) xp.evaluate("//script", doc, XPathConstants.NODESET);
      for (int i = scriptNodes.getLength() - 1; i >= 0; i--) {
        Node n = scriptNodes.item(i);
        Node attr = (n.getAttributes() != null) ? n.getAttributes().getNamedItem("contentType") : null;
        if (attr != null) {
          String v = attr.getNodeValue();
          if (v != null && v.toLowerCase(Locale.ROOT).contains("javascript")) {
            n.getParentNode().removeChild(n);
          }
        } else {
          // Some XFA might omit contentType; if you want to nuke all <script>, uncomment:
          // n.getParentNode().removeChild(n);
        }
      }

      // Serialize back to UTF-8
      TransformerFactory tf = TransformerFactory.newInstance();
      Transformer t = tf.newTransformer();
      t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
      t.setOutputProperty(OutputKeys.METHOD, "xml");
      t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      t.setOutputProperty(OutputKeys.INDENT, "no"); // keep packet compact

      java.io.ByteArrayOutputStream bout = new java.io.ByteArrayOutputStream();
      t.transform(new DOMSource(doc), new StreamResult(bout));
      return bout.toByteArray();
    } catch (Exception e) {
      System.err.println("XFA strip error: " + e.getMessage());
      return null;
    }
  }

  private static void removeAll(NodeList nodes) {
    for (int i = nodes.getLength() - 1; i >= 0; i--) {
      Node n = nodes.item(i);
      if (n != null && n.getParentNode() != null) {
        n.getParentNode().removeChild(n);
      }
    }
  }

  private static Charset detectXmlCharset(byte[] bytes) {
    if (bytes.length >= 2) {
      // UTF-16 BOMs
      if ((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF)
        return StandardCharsets.UTF_16BE;
      if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE)
        return StandardCharsets.UTF_16LE;
    }
    if (bytes.length >= 3) {
      // UTF-8 BOM
      if ((bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF)
        return StandardCharsets.UTF_8;
    }
    return StandardCharsets.UTF_8;
  }

  /*
   * --------------------------------- Shared helpers (unchanged)
   * ---------------------------------
   */

  private static void usageAndExit() {
    System.err.println("""
        Usage:
          java -jar xfa2acro-repair.jar <input.pdf> [output.pdf]

        Optional (list terminal field names):
          java -jar xfa2acro-repair.jar --list-fields <file.pdf>
        """);
    System.exit(2);
  }

  private static String deriveOutputName(String in) {
    int dot = in.toLowerCase(Locale.ROOT).lastIndexOf(".pdf");
    return (dot > 0 ? in.substring(0, dot) : in) + "_clean.pdf";
  }

  private static String readName(COSBase t) {
    if (t instanceof COSString cs)
      return cs.getString();
    if (t instanceof COSName cn)
      return cn.getName();
    return null;
  }

  private static String partialNameOf(String fullName) {
    int i = fullName.lastIndexOf('.');
    String last = (i >= 0) ? fullName.substring(i + 1) : fullName;
    return last.trim();
  }

  private static String uniquePartialName(String base, Map<String, PDField> byName) {
    if (!byName.containsKey(base))
      return base;
    int n = 2;
    while (byName.containsKey(base + "_" + n))
      n++;
    return base + "_" + n;
  }

  private static PDField createConcreteField(PDAcroForm acro, COSBase ft, String partialName) {
    PDField field;
    if (COSName.TX.equals(ft)) {
      PDTextField tf = new PDTextField(acro);
      tf.setPartialName(partialName);
      tf.getCOSObject().setItem(COSName.FT, COSName.TX);
      field = tf;
    } else if (COSName.CH.equals(ft)) {
      PDComboBox cb = new PDComboBox(acro);
      cb.setPartialName(partialName);
      cb.getCOSObject().setItem(COSName.FT, COSName.CH);
      field = cb;
    } else if (COSName.BTN.equals(ft)) {
      PDCheckBox bx = new PDCheckBox(acro);
      bx.setPartialName(partialName);
      bx.getCOSObject().setItem(COSName.FT, COSName.BTN);
      field = bx;
    } else {
      PDTextField tf = new PDTextField(acro);
      tf.setPartialName(partialName);
      tf.getCOSObject().setItem(COSName.FT, COSName.TX);
      field = tf;
    }
    return field;
  }

  private static void ensureAcroDefaults(PDAcroForm acro) {
    if (acro.getDefaultResources() == null) {
      acro.setDefaultResources(new PDResources());
    }
    try {
      PDType1Font helv = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      acro.getDefaultResources().put(COSName.getPDFName("Helv"), helv);
    } catch (Exception ignore) {
    }

    if (acro.getDefaultAppearance() == null || acro.getDefaultAppearance().isBlank()) {
      acro.setDefaultAppearance("/Helv 0 Tf 0 g");
    }
  }

  private static void listFields(Path pdf) throws Exception {
    try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
      PDDocumentCatalog cat = doc.getDocumentCatalog();
      PDAcroForm acro = (cat != null) ? cat.getAcroForm() : null;
      if (acro == null) {
        System.out.println("(no AcroForm)");
        return;
      }
      List<String> names = new ArrayList<>();
      collectTerminalNames(acro.getFields(), names);
      names.forEach(System.out::println);
    }
  }

  private static void collectTerminalNames(List<PDField> fields, List<String> out) {
    if (fields == null)
      return;
    for (PDField f : fields) {
      if (f instanceof PDNonTerminalField nt) {
        List<PDField> kids = nt.getChildren();
        if (kids != null && !kids.isEmpty()) {
          collectTerminalNames(kids, out);
          continue;
        }
      }
      out.add(f.getPartialName());
    }
  }
}
