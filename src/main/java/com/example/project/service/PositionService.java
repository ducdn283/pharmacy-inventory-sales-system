package com.example.project.service;

import com.example.project.dto.request.PositionCreateRequest;
import com.example.project.dto.response.PositionResponse;
import com.example.project.entity.Position;
import com.example.project.entity.Product;
import com.example.project.repository.PositionRepository;
import com.example.project.repository.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Nghiệp vụ vị trí lưu kho ({@link Position}) phục vụ màn quản lý Owner ({@code /owner/positions/**}).
 */
@Service
public class PositionService {
    private final PositionRepository positionRepository;
    private final ProductRepository productRepository;

    public PositionService(PositionRepository positionRepository,
                           ProductRepository productRepository) {
        this.positionRepository = positionRepository;
        this.productRepository = productRepository;
    }

    /**
     * Danh sách vị trí có phân trang và tìm kiếm.
     * Tìm kiếm khớp đầu chuỗi trên tên vị trí, tên/mã hàng hoặc mã {@code VT-xxxxx}.
     */
    @Transactional(readOnly = true)
    public Page<PositionResponse> list(String search, Pageable pageable) {
        String normalizedKeyword = normalize(search);

        List<PositionResponse> filtered = positionRepository.findAllWithProduct().stream()
                .map(PositionResponse::from)
                .filter(position -> normalizedKeyword.isEmpty() || matchesKeyword(position, normalizedKeyword))
                .sorted(Comparator.comparing(PositionResponse::getId, Comparator.nullsLast(Integer::compareTo)))
                .toList();

        return paginate(filtered, pageable);
    }

    /** Tổng số vị trí — thẻ thống kê màn danh sách Owner. */
    @Transactional(readOnly = true)
    public long countAll() {
        return positionRepository.count();
    }

    /** Sản phẩm chưa có vị trí — dropdown form tạo vị trí Owner. */
    @Transactional(readOnly = true)
    public List<Product> listProductsWithoutPosition() {
        Set<Integer> assigned = new HashSet<>(positionRepository.findAllAssignedProductIds());
        return productRepository.findAllWithRelations().stream()
                .filter(product -> !assigned.contains(product.getProductID()))
                .toList();
    }

    /**
     * Sản phẩm cho form sửa vị trí: sản phẩm chưa có vị trí + sản phẩm hiện tại của bản ghi đang sửa.
     */
    @Transactional(readOnly = true)
    public List<Product> listProductsForUpdate(Integer positionId) {
        Position entity = positionRepository.findById(positionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy vị trí"));
        Integer currentProductId = entity.getProductID() != null ? entity.getProductID().getProductID() : null;
        Set<Integer> assigned = new HashSet<>(positionRepository.findAllAssignedProductIds());
        if (currentProductId != null) {
            assigned.remove(currentProductId);
        }
        Set<Integer> excluded = assigned;
        return productRepository.findAllWithRelations().stream()
                .filter(product -> !excluded.contains(product.getProductID()))
                .toList();
    }

    /** Chi tiết một vị trí — form cập nhật Owner. */
    @Transactional(readOnly = true)
    public PositionResponse getById(Integer id) {
        return positionRepository.findById(id)
                .map(PositionResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy vị trí"));
    }

    /** Tạo vị trí mới — chỉ Owner. Mỗi sản phẩm chỉ được gắn một vị trí. */
    @Transactional
    public PositionResponse create(PositionCreateRequest request) {
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm"));
        ensureProductHasNoPosition(product.getProductID());

        Position entity = new Position();
        applyForm(entity, product, request);
        return PositionResponse.from(positionRepository.save(entity));
    }

    /** Cập nhật vị trí — chỉ Owner. Không cho gán sản phẩm đã có vị trí khác. */
    @Transactional
    public PositionResponse update(Integer id, PositionCreateRequest request) {
        Position entity = positionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy vị trí"));
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm"));
        ensureProductHasNoOtherPosition(product.getProductID(), id);

        applyForm(entity, product, request);
        return PositionResponse.from(positionRepository.save(entity));
    }

    private void ensureProductHasNoPosition(Integer productId) {
        if (positionRepository.existsByProductId(productId)) {
            throw new IllegalArgumentException("Sản phẩm này đã có vị trí, không thể thêm vị trí mới");
        }
    }

    private void ensureProductHasNoOtherPosition(Integer productId, Integer positionId) {
        if (positionRepository.existsByProductIdAndIdNot(productId, positionId)) {
            throw new IllegalArgumentException("Sản phẩm này đã có vị trí, không thể gán thêm vị trí mới");
        }
    }

    /** Gán sản phẩm và tên vị trí từ form vào entity. */
    private void applyForm(Position entity, Product product, PositionCreateRequest request) {
        entity.setProductID(product);
        entity.setName(request.getName().trim());
    }

    /** Kiểm tra từ khóa tìm kiếm (đã bỏ dấu) có khớp tên vị trí, hàng hóa hoặc mã vị trí không. */
    private boolean matchesKeyword(PositionResponse position, String normalizedKeyword) {
        return startsWithNormalized(position.getName(), normalizedKeyword)
                || startsWithNormalized(position.getProductName(), normalizedKeyword)
                || startsWithNormalized(position.getProductCode(), normalizedKeyword)
                || startsWithNormalized(formatCode("VT", position.getId()), normalizedKeyword);
    }

    /** Mã hiển thị vị trí: {@code VT-00001}. */
    private String formatCode(String prefix, Integer id) {
        return id != null ? prefix + "-" + String.format("%05d", id) : prefix + "-";
    }

    /** Cắt danh sách đã lọc theo {@link Pageable} — vì lọc chạy trong bộ nhớ. */
    private Page<PositionResponse> paginate(List<PositionResponse> filtered, Pageable pageable) {
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        List<PositionResponse> content = start >= filtered.size()
                ? List.of()
                : filtered.subList(start, end);
        return new PageImpl<>(content, pageable, filtered.size());
    }

    /** So khớp đầu chuỗi sau khi chuẩn hóa (bỏ dấu, chữ thường) — dùng cho tìm kiếm prefix. */
    private boolean startsWithNormalized(String value, String normalizedKeyword) {
        return value != null && normalize(value).startsWith(normalizedKeyword);
    }

    /** Chuẩn hóa chuỗi tìm kiếm: bỏ dấu tiếng Việt, chữ thường. */
    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.replace("Đ", "D").replace("đ", "d");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }
}
