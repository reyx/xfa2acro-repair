package global.agnos.pdf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConvertAndRepairTest {

  @TempDir
  Path tmp;

  /** Build a minimal 1-page doc with an orphan Text widget: /FT /Tx, /T "Field1", on page annots only. */
  private Path makePdfWithOrphanTextWidget(String name) throws Exception {
    Path p = tmp.resolve(name);
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.LETTER);
      doc.addPage(page);

      // Ensure AcroForm exists but empty
      PDDocumentCatalog cat = doc.getDocumentCatalog();
      PDAcroForm acro = new PDAcroForm(doc);
      cat.setAcroForm(acro);

      // Create a widget annotation that LOOKS like a field, but is not attached to AcroForm
      PDAnnotationWidget widget = new PDAnnotationWidget();
      widget.setRectangle(new PDRectangle(100, 600, 150, 18));

      // On PDFBox 3.x we set form metadata at COS level for cross-version safety
      COSDictionary wCos = widget.getCOSObject();
      wCos.setItem(COSName.FT, COSName.TX);               // /Tx
      wCos.setItem(COSName.T, new COSString("Field1"));   // /T

      // Add widget to page annots
      page.getAnnotations().add(widget);

      doc.save(p.toFile());
    }
    return p;
  }

  /** Build a doc with a proper (non-orphan) text field so we can check it's preserved. */
  private Path makePdfWithProperTextField(String name) throws Exception {
    Path p = tmp.resolve(name);
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.LETTER);
      doc.addPage(page);

      PDAcroForm acro = new PDAcroForm(doc);
      doc.getDocumentCatalog().setAcroForm(acro);

      PDTextField tf = new PDTextField(acro);
      tf.setPartialName("ProperText");
      acro.getFields().add(tf);

      PDAnnotationWidget widget = new PDAnnotationWidget();
      widget.setRectangle(new PDRectangle(100, 560, 150, 18));
      // link widget to field: set /Parent and add to /Kids
      widget.getCOSObject().setItem(COSName.PARENT, tf.getCOSObject());
      COSArray kids = new COSArray();
      kids.add(widget.getCOSObject());
      tf.getCOSObject().setItem(COSName.KIDS, kids);

      // Add to page
      page.getAnnotations().add(widget);

      doc.save(p.toFile());
    }
    return p;
  }

  /** Build a doc with orphan widgets of different types (Tx, Ch, Btn). */
  private Path makePdfWithOrphanMixedWidgets(String name) throws Exception {
    Path p = tmp.resolve(name);
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.LETTER);
      doc.addPage(page);
      PDAcroForm acro = new PDAcroForm(doc);
      doc.getDocumentCatalog().setAcroForm(acro); // empty form tree on purpose

      // TEXT
      PDAnnotationWidget wTx = new PDAnnotationWidget();
      wTx.setRectangle(new PDRectangle(100, 600, 150, 18));
      wTx.getCOSObject().setItem(COSName.FT, COSName.TX);
      wTx.getCOSObject().setItem(COSName.T, new COSString("TxName"));
      page.getAnnotations().add(wTx);

      // CHOICE (ComboBox)
      PDAnnotationWidget wCh = new PDAnnotationWidget();
      wCh.setRectangle(new PDRectangle(100, 560, 150, 18));
      wCh.getCOSObject().setItem(COSName.FT, COSName.CH);
      wCh.getCOSObject().setItem(COSName.T, new COSString("ChName"));
      page.getAnnotations().add(wCh);

      // BUTTON (we’ll treat as CheckBox by default)
      PDAnnotationWidget wBtn = new PDAnnotationWidget();
      wBtn.setRectangle(new PDRectangle(100, 520, 12, 12));
      wBtn.getCOSObject().setItem(COSName.FT, COSName.BTN);
      wBtn.getCOSObject().setItem(COSName.T, new COSString("BtnName"));
      page.getAnnotations().add(wBtn);

      doc.save(p.toFile());
    }
    return p;
  }

  @Test
  void repairs_orphan_text_widget_into_acroform_field() throws Exception {
    Path input = makePdfWithOrphanTextWidget("orphan-tx.pdf");
    Path output = tmp.resolve("orphan-tx_clean.pdf");

    // Run the tool (call main with args)
    ConvertAndRepair.main(new String[]{ input.toString(), output.toString() });

    // Verify repaired output
    try (PDDocument doc = Loader.loadPDF(output.toFile())) {
      PDAcroForm acro = doc.getDocumentCatalog().getAcroForm();
      assertNotNull(acro, "AcroForm should exist after repair");
      List<PDField> fields = acro.getFields();
      assertFalse(fields.isEmpty(), "Fields should not be empty after repair");

      // Find the new field by name
      PDField field1 = fields.stream()
          .filter(f -> "Field1".equals(f.getPartialName()))
          .findFirst()
          .orElse(null);

      assertNotNull(field1, "Repaired field 'Field1' should be present");
      assertTrue(field1 instanceof PDTextField, "Field1 should be a PDTextField");

      // Ensure it has at least one widget linked back
      assertFalse(((PDTerminalField) field1).getWidgets().isEmpty(), "Field1 should have a widget");
      // Parent relationship (COS-level) should be set
      assertNotNull(((PDTerminalField) field1).getCOSObject().getDictionaryObject(COSName.KIDS),
          "Field1 should have /Kids array");
    }
  }

  @Test
  void keeps_existing_field_intact() throws Exception {
    Path input = makePdfWithProperTextField("proper-tx.pdf");
    Path output = tmp.resolve("proper-tx_clean.pdf");

    ConvertAndRepair.main(new String[]{ input.toString(), output.toString() });

    try (PDDocument doc = Loader.loadPDF(output.toFile())) {
      PDAcroForm acro = doc.getDocumentCatalog().getAcroForm();
      assertNotNull(acro);
      PDField proper = acro.getField("ProperText");
      assertNotNull(proper, "Existing field should remain");

      // Still a text field and still has a widget
      assertTrue(proper instanceof PDTextField);
      assertFalse(((PDTerminalField) proper).getWidgets().isEmpty());
    }
  }

  @Test
  void repairs_mixed_widget_types_tx_ch_btn() throws Exception {
    Path input = makePdfWithOrphanMixedWidgets("mixed.pdf");
    Path output = tmp.resolve("mixed_clean.pdf");

    ConvertAndRepair.main(new String[]{ input.toString(), output.toString() });

    try (PDDocument doc = Loader.loadPDF(output.toFile())) {
      PDAcroForm acro = doc.getDocumentCatalog().getAcroForm();
      assertNotNull(acro);

      PDField tx = acro.getField("TxName");
      PDField ch = acro.getField("ChName");
      PDField btn = acro.getField("BtnName");

      assertNotNull(tx, "TxName should be created");
      assertNotNull(ch, "ChName should be created");
      assertNotNull(btn, "BtnName should be created");

      assertTrue(tx instanceof PDTextField, "TxName -> PDTextField");
      assertTrue(ch instanceof PDComboBox, "ChName -> PDComboBox (choice)");
      assertTrue(btn instanceof PDCheckBox, "BtnName -> PDCheckBox (default for /Btn)");

      assertFalse(((PDTerminalField) tx).getWidgets().isEmpty(), "TxName should have widget");
      assertFalse(((PDTerminalField) ch).getWidgets().isEmpty(), "ChName should have widget");
      assertFalse(((PDTerminalField) btn).getWidgets().isEmpty(), "BtnName should have widget");
    }
  }
}
