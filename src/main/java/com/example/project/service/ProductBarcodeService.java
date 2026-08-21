package com.example.project.service;

import com.example.project.dto.response.ProductBarcodeOptionResponse;
import com.example.project.dto.response.ProductBarcodePrintItemResponse;
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
import java.util.regex.Pattern;

@Service
public class ProductBarcodeService {

    private static final int IMAGE_WIDTH = 600;
    private static final int IMAGE_HEIGHT = 180;

    private static final int MAX_SEARCH_RESULT = 20;
    private static final int MAX_PRINT_QUANTITY_PER_PRODUCT = 1000;
    private static final int MAX_TOTAL_PRINT_QUANTITY = 5000;

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final Pattern DIACRITICS_PATTERN =
            Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    private final ProductRepository productRepository;
    private final ProductunitRepository productunitRepository;
    private final PurchaseinvoiceRepository purchaseinvoiceRepository;
    private final PurchasedetailRepository purchasedetailRepository;

    public ProductBarcodeService(
            ProductRepository productRepository,
            ProductunitRepository productunitRepository,
            PurchaseinvoiceRepository purchaseinvoiceRepository,
            PurchasedetailRepository purchasedetailRepository
    ) {
        this.productRepository = productRepository;
        this.productunitRepository = productunitRepository;
        this.purchaseinvoiceRepository = purchaseinvoiceRepository;
        this.purchasedetailRepository = purchasedetailRepository;
    }

    /**
     * Tìm kiếm sản phẩm dùng trong modal in barcode.
     *
     * Có thể tìm theo:
     * - Mã hàng hóa.
     * - Tên hàng hóa.
     * - Barcode.
     *
     * Chỉ trả về tối đa 20 kết quả để tránh modal phải tải quá nhiều dữ liệu.
     */
    @Transactional(readOnly = true)
    public List<ProductBarcodeOptionResponse> searchProducts(String keyword) {
        String normalizedKeyword = normalize(keyword);

        Map<Integer, Productunit> printUnitByProduct = getPrintUnitByProduct();

        return productRepository.findAllWithRelations()
                .stream()
                .filter(product -> !Boolean.FALSE.equals(product.getStatus()))
                .filter(product -> matchesProduct(product, normalizedKeyword))
                .sorted(
                        Comparator.comparing(
                                Product::getName,
                                String.CASE_INSENSITIVE_ORDER
                        )
                )
                .limit(MAX_SEARCH_RESULT)
                .map(product -> toProductOption(
                        product,
                        printUnitByProduct.get(product.getProductID())
                ))
                .toList();
    }

    /**
     * Tìm kiếm hóa đơn nhập trong modal.
     *
     * Có thể tìm theo:
     * - Mã hóa đơn nhập.
     * - Tên nhà cung cấp.
     * - Trạng thái hóa đơn.
     *
     * Hóa đơn đã hủy sẽ không được sử dụng để thêm sản phẩm.
     */
    @Transactional(readOnly = true)
    public List<PurchaseInvoiceBarcodeOptionResponse> searchPurchaseInvoices(
            String keyword
    ) {
        String normalizedKeyword = normalize(keyword);

        return purchaseinvoiceRepository.findAllWithRelations()
                .stream()
                .filter(this::isUsablePurchaseInvoice)
                .filter(invoice -> matchesPurchaseInvoice(
                        invoice,
                        normalizedKeyword
                ))
                .limit(MAX_SEARCH_RESULT)
                .map(this::toPurchaseInvoiceOption)
                .toList();
    }

    /**
     * Lấy tất cả sản phẩm từ một hóa đơn nhập.
     *
     * Nếu một sản phẩm xuất hiện nhiều dòng trong hóa đơn thì số lượng
     * của các dòng sẽ được cộng lại thành một dòng duy nhất.
     */
    @Transactional(readOnly = true)
    public List<ProductBarcodePrintItemResponse> getProductsFromPurchaseInvoice(
            Integer purchaseId
    ) {
        Purchaseinvoice purchaseinvoice =
                purchaseinvoiceRepository.findByIdWithRelations(purchaseId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy hóa đơn nhập"
                                )
                        );

        if (!isUsablePurchaseInvoice(purchaseinvoice)) {
            throw new IllegalArgumentException(
                    "Không thể thêm sản phẩm từ hóa đơn nhập đã hủy"
            );
        }

        List<Purchasedetail> purchaseDetails =
                purchasedetailRepository.findByPurchaseIdWithProduct(purchaseId);

        if (purchaseDetails.isEmpty()) {
            throw new IllegalArgumentException(
                    "Hóa đơn nhập không có sản phẩm"
            );
        }

        Map<Integer, Productunit> printUnitByProduct = getPrintUnitByProduct();

        /*
         * LinkedHashMap giúp giữ nguyên thứ tự sản phẩm
         * giống thứ tự trong hóa đơn nhập.
         */
        Map<Integer, ProductBarcodePrintItemResponse> itemByProduct =
                new LinkedHashMap<>();

        for (Purchasedetail purchaseDetail : purchaseDetails) {
            Product product = purchaseDetail.getProductID();

            if (product == null || product.getProductID() == null) {
                continue;
            }

            int purchaseQuantity =
                    purchaseDetail.getQuantity() == null
                            ? 0
                            : purchaseDetail.getQuantity();

            if (purchaseQuantity <= 0) {
                continue;
            }

            Integer productId = product.getProductID();

            ProductBarcodePrintItemResponse existingItem =
                    itemByProduct.get(productId);

            if (existingItem != null) {
                existingItem.setQuantity(
                        existingItem.getQuantity() + purchaseQuantity
                );
                continue;
            }

            Productunit printUnit = printUnitByProduct.get(productId);

            itemByProduct.put(
                    productId,
                    new ProductBarcodePrintItemResponse(
                            productId,
                            product.getCode(),
                            product.getName(),
                            trimToNull(product.getBarcode()),
                            printUnit != null
                                    ? printUnit.getUnitName()
                                    : null,
                            printUnit != null
                                    ? printUnit.getSellPrice()
                                    : null,
                            purchaseQuantity
                    )
            );
        }

        if (itemByProduct.isEmpty()) {
            throw new IllegalArgumentException(
                    "Hóa đơn nhập không có sản phẩm hợp lệ để thêm"
            );
        }

        return new ArrayList<>(itemByProduct.values());
    }

    /**
     * Tạo danh sách cuối cùng dùng cho trang in barcode.
     *
     * Phương thức này kiểm tra lại dữ liệu ở phía server,
     * không tin hoàn toàn dữ liệu product name, barcode hoặc giá
     * được gửi từ JavaScript.
     */
    @Transactional(readOnly = true)
    public List<ProductBarcodePrintItemResponse> buildPrintItems(
            List<Integer> productIds,
            List<Integer> quantities
    ) {
        if (productIds == null
                || quantities == null
                || productIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Vui lòng thêm ít nhất một sản phẩm để in barcode"
            );
        }

        if (productIds.size() != quantities.size()) {
            throw new IllegalArgumentException(
                    "Danh sách sản phẩm và số lượng tem không hợp lệ"
            );
        }

        Map<Integer, Integer> quantityByProduct = new LinkedHashMap<>();

        for (int index = 0; index < productIds.size(); index++) {
            Integer productId = productIds.get(index);
            Integer quantity = quantities.get(index);

            if (productId == null) {
                throw new IllegalArgumentException(
                        "Danh sách sản phẩm không hợp lệ"
                );
            }

            if (quantity == null
                    || quantity < 1
                    || quantity > MAX_PRINT_QUANTITY_PER_PRODUCT) {
                throw new IllegalArgumentException(
                        "Số lượng tem của mỗi sản phẩm phải nằm trong khoảng từ 1 đến "
                                + MAX_PRINT_QUANTITY_PER_PRODUCT
                );
            }

            quantityByProduct.merge(productId, quantity, Integer::sum);
        }

        int totalQuantity = quantityByProduct.values()
                .stream()
                .mapToInt(Integer::intValue)
                .sum();

        if (totalQuantity > MAX_TOTAL_PRINT_QUANTITY) {
            throw new IllegalArgumentException(
                    "Tổng số lượng tem không được vượt quá "
                            + MAX_TOTAL_PRINT_QUANTITY
            );
        }

        Map<Integer, Productunit> printUnitByProduct = getPrintUnitByProduct();

        List<ProductBarcodePrintItemResponse> printItems =
                new ArrayList<>();

        for (Map.Entry<Integer, Integer> entry :
                quantityByProduct.entrySet()) {

            Integer productId = entry.getKey();
            Integer quantity = entry.getValue();

            Product product = productRepository.findDetailById(productId)
                    .orElseThrow(() ->
                            new IllegalArgumentException(
                                    "Không tìm thấy sản phẩm có ID "
                                            + productId
                            )
                    );

            String barcode = trimToNull(product.getBarcode());

            if (barcode == null) {
                throw new IllegalArgumentException(
                        "Sản phẩm \"" + product.getName()
                                + "\" chưa có barcode"
                );
            }

            Productunit printUnit =
                    printUnitByProduct.get(productId);

            printItems.add(
                    new ProductBarcodePrintItemResponse(
                            productId,
                            product.getCode(),
                            product.getName(),
                            barcode,
                            printUnit != null
                                    ? printUnit.getUnitName()
                                    : null,
                            printUnit != null
                                    ? printUnit.getSellPrice()
                                    : null,
                            quantity
                    )
            );
        }

        return printItems;
    }

    /**
     * Sinh ảnh barcode Code 128 theo barcode đang được lưu
     * trong database của sản phẩm.
     */
    @Transactional(readOnly = true)
    public byte[] generateBarcodePng(Integer productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy sản phẩm"
                        )
                );

        String barcode = product.getBarcode();

        if (barcode == null || barcode.isBlank()) {
            throw new IllegalArgumentException(
                    "Sản phẩm chưa có barcode. "
                            + "Vui lòng cập nhật barcode trước khi in."
            );
        }

        barcode = barcode.trim();

        try {
            BitMatrix matrix = new Code128Writer().encode(
                    barcode,
                    BarcodeFormat.CODE_128,
                    IMAGE_WIDTH,
                    IMAGE_HEIGHT,
                    Map.of(
                            EncodeHintType.MARGIN,
                            4
                    )
            );

            BufferedImage image = new BufferedImage(
                    matrix.getWidth(),
                    matrix.getHeight(),
                    BufferedImage.TYPE_BYTE_BINARY
            );

            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    image.setRGB(
                            x,
                            y,
                            matrix.get(x, y)
                                    ? 0xFF000000
                                    : 0xFFFFFFFF
                    );
                }
            }

            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();

            boolean written = ImageIO.write(
                    image,
                    "png",
                    output
            );

            if (!written) {
                throw new IllegalStateException(
                        "Không tìm thấy bộ mã hóa ảnh PNG"
                );
            }

            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Không thể tạo ảnh barcode",
                    exception
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Mã barcode không hợp lệ: "
                            + barcode,
                    exception
            );
        }
    }

    /**
     * Lấy đơn vị dùng để in cho tất cả sản phẩm.
     *
     * Thứ tự ưu tiên:
     * 1. Đơn vị mặc định.
     * 2. Đơn vị cơ bản.
     * 3. Đơn vị đang hoạt động đầu tiên.
     */
    private Map<Integer, Productunit> getPrintUnitByProduct() {
        Map<Integer, Productunit> printUnitByProduct =
                new LinkedHashMap<>();

        Comparator<Productunit> unitComparator =
                Comparator
                        .comparingInt(
                                (Productunit unit) ->
                                        Boolean.TRUE.equals(
                                                unit.getIsDefault()
                                        )
                                                ? 0
                                                : 1
                        )
                        .thenComparingInt(
                                unit ->
                                        Boolean.TRUE.equals(
                                                unit.getIsBaseUnit()
                                        )
                                                ? 0
                                                : 1
                        )
                        .thenComparing(
                                Productunit::getId,
                                Comparator.nullsLast(
                                        Comparator.naturalOrder()
                                )
                        );

        productunitRepository.findAllWithProduct()
                .stream()
                .filter(unit -> unit.getProductID() != null)
                .filter(unit -> !Boolean.FALSE.equals(
                        unit.getIsActive()
                ))
                .sorted(unitComparator)
                .forEach(unit ->
                        printUnitByProduct.putIfAbsent(
                                unit.getProductID().getProductID(),
                                unit
                        )
                );

        return printUnitByProduct;
    }

    private ProductBarcodeOptionResponse toProductOption(
            Product product,
            Productunit printUnit
    ) {
        return new ProductBarcodeOptionResponse(
                product.getProductID(),
                product.getCode(),
                product.getName(),
                trimToNull(product.getBarcode()),
                printUnit != null
                        ? printUnit.getUnitName()
                        : null,
                printUnit != null
                        ? printUnit.getSellPrice()
                        : null
        );
    }

    private PurchaseInvoiceBarcodeOptionResponse
    toPurchaseInvoiceOption(Purchaseinvoice invoice) {
        String dateDisplay = "";

        if (invoice.getDate() != null) {
            dateDisplay = DATE_TIME_FORMATTER.format(
                    invoice.getDate().atZone(VIETNAM_ZONE)
            );
        }

        String supplierName =
                invoice.getSupplierID() != null
                        ? invoice.getSupplierID().getName()
                        : "—";

        return new PurchaseInvoiceBarcodeOptionResponse(
                invoice.getId(),
                invoice.getPurchaseInvoiceCode(),
                dateDisplay,
                supplierName,
                invoice.getStatus()
        );
    }

    private boolean matchesProduct(
            Product product,
            String normalizedKeyword
    ) {
        if (normalizedKeyword.isEmpty()) {
            return true;
        }

        return containsNormalized(
                product.getCode(),
                normalizedKeyword
        ) || containsNormalized(
                product.getName(),
                normalizedKeyword
        ) || containsNormalized(
                product.getBarcode(),
                normalizedKeyword
        );
    }

    private boolean matchesPurchaseInvoice(
            Purchaseinvoice invoice,
            String normalizedKeyword
    ) {
        if (normalizedKeyword.isEmpty()) {
            return true;
        }

        String supplierName =
                invoice.getSupplierID() != null
                        ? invoice.getSupplierID().getName()
                        : null;

        return containsNormalized(
                invoice.getPurchaseInvoiceCode(),
                normalizedKeyword
        ) || containsNormalized(
                supplierName,
                normalizedKeyword
        ) || containsNormalized(
                invoice.getStatus(),
                normalizedKeyword
        );
    }

    private boolean isUsablePurchaseInvoice(
            Purchaseinvoice invoice
    ) {
        if (invoice == null) {
            return false;
        }

        String normalizedStatus = normalize(invoice.getStatus());

        return !"cancelled".equals(normalizedStatus)
                && !"canceled".equals(normalizedStatus)
                && !"da huy".equals(normalizedStatus);
    }

    private boolean containsNormalized(
            String source,
            String normalizedKeyword
    ) {
        if (source == null) {
            return false;
        }

        return normalize(source).contains(normalizedKeyword);
    }

    /**
     * Chuẩn hóa chuỗi để tìm kiếm không phân biệt:
     * - Chữ hoa/chữ thường.
     * - Dấu tiếng Việt.
     * - Khoảng trắng thừa.
     */
    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        String normalized = Normalizer.normalize(
                value.trim().toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        );

        normalized = DIACRITICS_PATTERN
                .matcher(normalized)
                .replaceAll("");

        return normalized.replace('đ', 'd');
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty()
                ? null
                : trimmed;
    }
}