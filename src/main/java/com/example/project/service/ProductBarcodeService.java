package com.example.project.service;

import com.example.project.dto.response.ProductBarcodeOptionResponse;
import com.example.project.dto.response.ProductBarcodePrintResponse;
import com.example.project.dto.response.PurchaseInvoiceBarcodeOptionResponse;
import com.example.project.entity.Product;
import com.example.project.entity.Productunit;
import com.example.project.entity.Purchasedetail;
import com.example.project.entity.Purchaseinvoice;
import com.example.project.repository.ProductRepository;
import com.example.project.repository.ProductunitRepository;
import com.example.project.repository.PurchasedetailRepository;
import com.example.project.repository.PurchaseinvoiceRepository;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ProductBarcodeService {

    private static final int IMAGE_WIDTH = 600;
    private static final int IMAGE_HEIGHT = 180;
    private static final int MAX_SEARCH_RESULTS = 30;
    private static final int MAX_TOTAL_LABELS = 5000;
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ProductRepository productRepository;
    private final ProductunitRepository productunitRepository;
    private final PurchaseinvoiceRepository purchaseinvoiceRepository;
    private final PurchasedetailRepository purchasedetailRepository;

    public ProductBarcodeService(ProductRepository productRepository,
                                 ProductunitRepository productunitRepository,
                                 PurchaseinvoiceRepository purchaseinvoiceRepository,
                                 PurchasedetailRepository purchasedetailRepository) {
        this.productRepository = productRepository;
        this.productunitRepository = productunitRepository;
        this.purchaseinvoiceRepository = purchaseinvoiceRepository;
        this.purchasedetailRepository = purchasedetailRepository;
    }

    /** Tìm sản phẩm theo mã hoặc tên. Barcode luôn được sinh từ cột code. */
    @Transactional(readOnly = true)
    public List<ProductBarcodeOptionResponse> searchProducts(String keyword) {
        String normalizedKeyword = normalizeText(keyword);
        return productRepository.findAllWithRelations().stream()
                .filter(product -> hasCode(product)
                        && (normalizedKeyword.isBlank()
                        || normalizeText(product.getCode()).contains(normalizedKeyword)
                        || normalizeText(product.getName()).contains(normalizedKeyword)))
                .limit(MAX_SEARCH_RESULTS)
                .map(product -> toOption(product, 1))
                .toList();
    }

    /** Tìm hóa đơn nhập theo mã hóa đơn hoặc tên nhà cung cấp. */
    @Transactional(readOnly = true)
    public List<PurchaseInvoiceBarcodeOptionResponse> searchPurchaseInvoices(String keyword) {
        String normalizedKeyword = normalizeText(keyword);
        return purchaseinvoiceRepository.findAllWithRelations().stream()
                .filter(invoice -> normalizedKeyword.isBlank()
                        || normalizeText(invoice.getPurchaseInvoiceCode()).contains(normalizedKeyword)
                        || normalizeText(invoice.getSupplierID() == null
                        ? null : invoice.getSupplierID().getName()).contains(normalizedKeyword))
                .limit(MAX_SEARCH_RESULTS)
                .map(this::toInvoiceOption)
                .toList();
    }

    /** Lấy các sản phẩm và số lượng thực nhập từ một hóa đơn nhập. */
    @Transactional(readOnly = true)
    public List<ProductBarcodeOptionResponse> getProductsFromPurchaseInvoice(Integer purchaseId) {
        purchaseinvoiceRepository.findByIdWithRelations(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn nhập"));

        Map<Integer, ProductBarcodeOptionResponse> result = new LinkedHashMap<>();
        for (Purchasedetail detail : purchasedetailRepository.findByPurchaseIdWithProduct(purchaseId)) {
            Product product = detail.getProductID();
            if (product == null || !hasCode(product)) {
                continue;
            }
            int purchased = detail.getQuantity() == null ? 0 : detail.getQuantity();
            int returned = detail.getReturnQty() == null ? 0 : detail.getReturnQty();
            int quantity = Math.max(0, purchased - returned);
            if (quantity == 0) {
                continue;
            }
            ProductBarcodeOptionResponse existing = result.get(product.getProductID());
            int totalQuantity = quantity + (existing == null ? 0 : existing.getQuantity());
            result.put(product.getProductID(), toOption(product, Math.min(1000, totalQuantity)));
        }
        return new ArrayList<>(result.values());
    }

    /** Chuẩn hóa danh sách do modal gửi lên để xem trước hoặc xuất PDF. */
    @Transactional(readOnly = true)
    public List<ProductBarcodePrintResponse> buildPrintItems(List<Integer> productIds,
                                                             List<Integer> quantities) {
        if (productIds == null || quantities == null || productIds.isEmpty()
                || productIds.size() != quantities.size()) {
            throw new IllegalArgumentException("Danh sách in tem không hợp lệ");
        }

        List<ProductBarcodePrintResponse> result = new ArrayList<>();
        int totalLabels = 0;
        for (int index = 0; index < productIds.size(); index++) {
            Integer quantity = quantities.get(index);
            if (quantity == null || quantity < 1 || quantity > 1000) {
                throw new IllegalArgumentException("Số lượng tem mỗi sản phẩm phải từ 1 đến 1000");
            }
            totalLabels += quantity;
            if (totalLabels > MAX_TOTAL_LABELS) {
                throw new IllegalArgumentException("Tổng số tem không được vượt quá " + MAX_TOTAL_LABELS);
            }
            result.add(getPrintData(productIds.get(index), quantity));
        }
        return result;
    }

    /** Lấy dữ liệu một sản phẩm; giá trị mã hóa chính là Product.code. */
    @Transactional(readOnly = true)
    public ProductBarcodePrintResponse getPrintData(Integer productId, Integer quantity) {
        Product product = productRepository.findDetailById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hàng hóa"));
        String code = requireCode(product);
        Productunit printUnit = findPrintUnit(productId);
        return new ProductBarcodePrintResponse(
                product.getProductID(), code, product.getName(),
                printUnit == null ? null : printUnit.getUnitName(),
                printUnit == null ? null : printUnit.getSellPrice(), quantity
        );
    }

    /** Sinh ảnh PNG Code 128 trực tiếp từ Product.code; không đọc cột barcode. */
    @Transactional(readOnly = true)
    public byte[] generateBarcodePng(Integer productId) {
        return generateBarcodePng(getPrintData(productId, 1).getCode(), IMAGE_WIDTH, IMAGE_HEIGHT);
    }

    /** Dùng chung cho trang HTML và dịch vụ PDF. */
    public byte[] generateBarcodePng(String code, int width, int height) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Mã sản phẩm không được để trống");
        }
        try {
            BitMatrix matrix = new Code128Writer().encode(
                    code.trim(), BarcodeFormat.CODE_128, width, height,
                    Map.of(EncodeHintType.MARGIN, 4));
            BufferedImage image = new BufferedImage(
                    matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Không thể tạo ảnh barcode", exception);
        }
    }

    private ProductBarcodeOptionResponse toOption(Product product, int quantity) {
        Productunit printUnit = findPrintUnit(product.getProductID());
        return new ProductBarcodeOptionResponse(
                product.getProductID(), product.getCode().trim(), product.getName(),
                printUnit == null ? null : printUnit.getUnitName(),
                printUnit == null ? null : printUnit.getSellPrice(), quantity);
    }

    private PurchaseInvoiceBarcodeOptionResponse toInvoiceOption(Purchaseinvoice invoice) {
        String dateDisplay = invoice.getDate() == null ? null
                : DATE_FORMAT.format(invoice.getDate().atZone(VIETNAM_ZONE));
        return new PurchaseInvoiceBarcodeOptionResponse(
                invoice.getId(), invoice.getPurchaseInvoiceCode(), dateDisplay,
                invoice.getSupplierID() == null ? null : invoice.getSupplierID().getName(),
                invoice.getStatus());
    }

    private Productunit findPrintUnit(Integer productId) {
        return productunitRepository.findByProductId(productId).stream()
                .filter(unit -> !Boolean.FALSE.equals(unit.getIsActive()))
                .min(Comparator
                        .comparingInt((Productunit unit) -> Boolean.TRUE.equals(unit.getIsDefault()) ? 0 : 1)
                        .thenComparingInt(unit -> Boolean.TRUE.equals(unit.getIsBaseUnit()) ? 0 : 1)
                        .thenComparing(Productunit::getId))
                .orElse(null);
    }

    private boolean hasCode(Product product) {
        return product.getCode() != null && !product.getCode().isBlank();
    }

    private String requireCode(Product product) {
        if (!hasCode(product)) {
            throw new IllegalArgumentException("Hàng hóa chưa có mã sản phẩm nên không thể sinh barcode");
        }
        return product.getCode().trim();
    }

    private String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT).trim();
    }
}