package global.agnos.pdf;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
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

/**
 * Normalize & repair AcroForm PDFs by attaching orphan widget annotations to
 * /AcroForm.Fields.
 * Optional: call Aspose Cloud to convert XFA → AcroForm first (if
 * ASPOSE_CLIENT_ID/SECRET are set).
 * No iText; PDFBox 3.x only.
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
      // Option: also run local repair over the converted file (idempotent, fixes
      // orphans, etc.)
      repairPdfInToOut(out, out);
      System.out.println("Converted (Aspose Cloud) + repaired (PDFBox): " + out.toAbsolutePath());
      return;
    }

    // Fallback: local repair only (input → output)
    repairPdfInToOut(in, out);
    System.out.println("Repaired (PDFBox): " + out.toAbsolutePath());
  }

  /*
   * ============================= Aspose Cloud (optional)
   * =============================
   */

  /**
   * Calls Aspose Cloud to convert XFA → AcroForm.
   * Writes the converted PDF to outputPath and returns true on success.
   * Returns false if not configured or if the call fails.
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

  /**
   * Minimal JSON extractor (for {"access_token":"..."}), avoids adding a JSON
   * library.
   */
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
   * =============================== Local PDF repair
   * ===============================
   */

  /**
   * Runs the orphan-widget repair from source → dest.
   * If source == dest, it overwrites the file in place.
   */
  private static void repairPdfInToOut(Path source, Path dest) throws Exception {
    try (PDDocument doc = Loader.loadPDF(source.toFile())) {
      // 1) Strip any encryption/owner restrictions
      doc.setAllSecurityToBeRemoved(true);

      // 2) Ensure AcroForm + minimal defaults
      PDDocumentCatalog catalog = doc.getDocumentCatalog();
      PDAcroForm acro = catalog.getAcroForm();
      if (acro == null) {
        acro = new PDAcroForm(doc);
        catalog.setAcroForm(acro);
      }
      ensureAcroDefaults(acro);

      // Cache existing top-level fields by their partial names
      Map<String, PDField> byName = new HashMap<>();
      for (PDField f : acro.getFields()) {
        byName.put(f.getPartialName(), f);
      }

      // 3) Scan pages for orphan widgets and attach them to the field tree
      for (PDPage page : doc.getPages()) {
        for (PDAnnotation ann : page.getAnnotations()) {
          if (!(ann instanceof PDAnnotationWidget))
            continue;
          PDAnnotationWidget widget = (PDAnnotationWidget) ann;

          COSDictionary wCos = widget.getCOSObject();

          // Ensure Widget annotation subtype
          wCos.setItem(COSName.SUBTYPE, COSName.WIDGET);

          // Widget must declare /FT (field type) and /T (name)
          COSBase ft = wCos.getDictionaryObject(COSName.FT); // /Tx, /Ch, /Btn
          COSBase t = wCos.getDictionaryObject(COSName.T); // full name from converter
          if (ft == null || t == null)
            continue;

          // Skip already wired
          if (wCos.getDictionaryObject(COSName.PARENT) != null)
            continue;

          String fullName = readName(t);
          if (fullName == null || fullName.isBlank())
            continue;
          if (fullName.startsWith("u:"))
            fullName = fullName.substring(2); // normalize some prefixes

          // Partial name (AcroForm forbids '.' in partial names)
          String pname = partialNameOf(fullName);

          // Deduplicate partial names if needed
          String uniqueName = uniquePartialName(pname, byName);

          PDField field = byName.get(uniqueName);
          if (field == null) {
            // Create the concrete field type and stamp essentials
            field = createConcreteField(acro, ft, uniqueName);
            field.getCOSObject().setItem(COSName.DA, new COSString("/Helv 0 Tf 0 g"));

            // Add to AcroForm
            acro.getFields().add(field);
            byName.put(uniqueName, field);
          }

          // Ensure field has /Kids array; push this widget
          COSArray kids = field.getCOSObject().getCOSArray(COSName.KIDS);
          if (kids == null) {
            kids = new COSArray();
            field.getCOSObject().setItem(COSName.KIDS, kids);
          }
          kids.add(wCos);

          // Link widget → field
          wCos.setItem(COSName.PARENT, field.getCOSObject());
        }
      }

      // 4) Save
      doc.save(dest.toFile());
    }
  }

  /*
   * --------------------------------- Shared helpers
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

  /** Return a legal partial name (last segment after the final dot). */
  private static String partialNameOf(String fullName) {
    int i = fullName.lastIndexOf('.');
    String last = (i >= 0) ? fullName.substring(i + 1) : fullName;
    return last.trim();
  }

  /** If the partial name already exists, append a counter to disambiguate. */
  private static String uniquePartialName(String base, Map<String, PDField> byName) {
    if (!byName.containsKey(base))
      return base;
    int n = 2;
    while (byName.containsKey(base + "_" + n))
      n++;
    return base + "_" + n;
  }

  /** Create the specific PDField subclass and stamp /FT. */
  private static PDField createConcreteField(PDAcroForm acro, COSBase ft, String partialName) {
    PDField field;
    if (COSName.TX.equals(ft)) {
      PDTextField tf = new PDTextField(acro);
      tf.setPartialName(partialName); // throws if '.' present — we avoid that
      tf.getCOSObject().setItem(COSName.FT, COSName.TX); // /FT /Tx
      field = tf;
    } else if (COSName.CH.equals(ft)) {
      PDComboBox cb = new PDComboBox(acro);
      cb.setPartialName(partialName);
      cb.getCOSObject().setItem(COSName.FT, COSName.CH); // /FT /Ch
      field = cb;
    } else if (COSName.BTN.equals(ft)) {
      // Default BTN → checkbox. If you need radio buttons, inspect /Ff flags and swap
      // to PDRadioButton.
      PDCheckBox bx = new PDCheckBox(acro);
      bx.setPartialName(partialName);
      bx.getCOSObject().setItem(COSName.FT, COSName.BTN); // /FT /Btn
      field = bx;
    } else {
      // Fallback to text field (safe default)
      PDTextField tf = new PDTextField(acro);
      tf.setPartialName(partialName);
      tf.getCOSObject().setItem(COSName.FT, COSName.TX);
      field = tf;
    }
    return field;
  }

  /** Ensure AcroForm has minimal defaults so text rendering works everywhere. */
  private static void ensureAcroDefaults(PDAcroForm acro) {
    if (acro.getDefaultResources() == null) {
      acro.setDefaultResources(new PDResources());
    }
    // Map "Helv" alias to a Standard 14 Helvetica instance (PDFBox 3.x)
    try {
      PDType1Font helv = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      acro.getDefaultResources().put(COSName.getPDFName("Helv"), helv);
    } catch (Exception ignore) {
      // Viewers usually synthesize /Helv anyway.
    }

    if (acro.getDefaultAppearance() == null || acro.getDefaultAppearance().isBlank()) {
      acro.setDefaultAppearance("/Helv 0 Tf 0 g");
    }
  }

  /** Print all terminal field partial names (useful for mapping). */
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
