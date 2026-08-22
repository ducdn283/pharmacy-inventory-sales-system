package com.example.project.service;

import com.example.project.dto.response.ProductBarcodePrintResponse;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

@Service
public class ProductBarcodePdfService {

    private static final int COLUMNS = 5;
    private static final int ROWS = 3;
    private static final int LABELS_PER_PAGE = COLUMNS * ROWS;
    private static final float PAGE_MARGIN = 14.2f; // xấp xỉ 5 mm
    private static final float LABEL_HEIGHT = 130f;

    private final ProductBarcodeService barcodeService;

    public ProductBarcodePdfService(ProductBarcodeService barcodeService) {
        this.barcodeService = barcodeService;
    }

    /** Xuất PDF A5 nằm ngang, mỗi trang 5 cột x 3 hàng tem. */
    public byte[] generateA5Pdf(List<ProductBarcodePrintResponse> items) {
        List<ProductBarcodePrintResponse> labels = expandLabels(items);
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("Danh sách in tem đang trống");
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(
                    PageSize.A5.rotate(), PAGE_MARGIN, PAGE_MARGIN, PAGE_MARGIN, PAGE_MARGIN);
            PdfWriter.getInstance(document, output);
            document.open();

            BaseFont baseFont = createVietnameseBaseFont();
            for (int start = 0; start < labels.size(); start += LABELS_PER_PAGE) {
                if (start > 0) {
                    document.newPage();
                }
                PdfPTable pageTable = new PdfPTable(COLUMNS);
                pageTable.setWidthPercentage(100);
                pageTable.setHorizontalAlignment(Element.ALIGN_CENTER);

                int end = Math.min(start + LABELS_PER_PAGE, labels.size());
                for (int index = start; index < end; index++) {
                    pageTable.addCell(createLabelCell(labels.get(index), baseFont));
                }
                for (int index = end; index < start + LABELS_PER_PAGE; index++) {
                    pageTable.addCell(createBlankCell());
                }
                document.add(pageTable);
            }
            document.close();
            return output.toByteArray();
        } catch (DocumentException | IOException exception) {
            throw new IllegalStateException("Không thể tạo file PDF barcode", exception);
        }
    }

    private PdfPCell createLabelCell(ProductBarcodePrintResponse item, BaseFont baseFont)
            throws IOException, DocumentException {
        Font pharmacyFont = new Font(baseFont, 6.5f, Font.BOLD, Color.BLACK);
        Font nameFont = new Font(baseFont, 6.2f, Font.BOLD, Color.BLACK);
        Font normalFont = new Font(baseFont, 5.5f, Font.NORMAL, Color.BLACK);
        Font priceFont = new Font(baseFont, 8f, Font.BOLD, Color.BLACK);

        PdfPTable content = new PdfPTable(1);
        content.setWidthPercentage(100);
        addText(content, "NHÀ THUỐC HẰNG NGỌC", pharmacyFont, Element.ALIGN_CENTER, 0f);
        addText(content, truncate(item.getName(), 55), nameFont, Element.ALIGN_CENTER, 1f);
        addText(content, item.getUnitName() == null ? "" : "(" + item.getUnitName() + ")",
                normalFont, Element.ALIGN_CENTER, 0f);

        Image barcode = Image.getInstance(
                barcodeService.generateBarcodePng(item.getCode(), 420, 105));
        barcode.scaleAbsolute(99f, 25f);
        PdfPCell imageCell = new PdfPCell(barcode, false);
        imageCell.setBorder(PdfPCell.NO_BORDER);
        imageCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        imageCell.setPaddingTop(2f);
        imageCell.setPaddingBottom(0f);
        content.addCell(imageCell);

        addText(content, item.getCode(), normalFont, Element.ALIGN_CENTER, 0f);
        addText(content, formatPrice(item.getSellPrice(), item.getUnitName()),
                priceFont, Element.ALIGN_CENTER, 0f);

        PdfPCell labelCell = new PdfPCell(content);
        labelCell.setFixedHeight(LABEL_HEIGHT);
        labelCell.setPadding(3f);
        labelCell.setBorderColor(new Color(220, 220, 220));
        labelCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return labelCell;
    }

    private void addText(PdfPTable table, String value, Font font, int alignment, float paddingTop) {
        Paragraph paragraph = new Paragraph(value == null ? "" : value, font);
        paragraph.setAlignment(alignment);
        paragraph.setLeading(font.getSize() + 1f);
        PdfPCell cell = new PdfPCell(paragraph);
        cell.setBorder(PdfPCell.NO_BORDER);
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(0f);
        cell.setPaddingTop(paddingTop);
        table.addCell(cell);
    }

    private PdfPCell createBlankCell() {
        PdfPCell cell = new PdfPCell();
        cell.setFixedHeight(LABEL_HEIGHT);
        cell.setBorderColor(new Color(235, 235, 235));
        return cell;
    }

    private List<ProductBarcodePrintResponse> expandLabels(List<ProductBarcodePrintResponse> items) {
        List<ProductBarcodePrintResponse> result = new ArrayList<>();
        for (ProductBarcodePrintResponse item : items) {
            for (int count = 0; count < item.getQuantity(); count++) {
                result.add(item);
            }
        }
        return result;
    }

    private String formatPrice(BigDecimal price, String unitName) {
        if (price == null) {
            return "";
        }
        String unit = unitName == null || unitName.isBlank() ? "đơn vị" : unitName;
        return new DecimalFormat("#,##0").format(price) + " VND/" + unit;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 1) + "…";
    }

    /** Ưu tiên font Unicode phổ biến trên Windows; có fallback cho Linux và môi trường test. */
    private BaseFont createVietnameseBaseFont() throws IOException, DocumentException {
        String[] fontPaths = {
                "C:/Windows/Fonts/arial.ttf",
                "C:/Windows/Fonts/tahoma.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/dejavu/DejaVuSans.ttf"
        };
        for (String path : fontPaths) {
            if (new File(path).isFile()) {
                return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            }
        }
        return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
    }
}