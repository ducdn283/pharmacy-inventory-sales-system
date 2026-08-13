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
import java.util.List;
import java.util.Locale;

@Service
public class PositionService {
    private final PositionRepository positionRepository;
    private final ProductRepository productRepository;

    public PositionService(PositionRepository positionRepository,
                           ProductRepository productRepository) {
        this.positionRepository = positionRepository;
        this.productRepository = productRepository;
    }

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

    @Transactional(readOnly = true)
    public long countAll() {
        return positionRepository.count();
    }

    @Transactional(readOnly = true)
    public List<Product> listProducts() {
        return productRepository.findAllWithRelations();
    }

    @Transactional(readOnly = true)
    public PositionResponse getById(Integer id) {
        return positionRepository.findById(id)
                .map(PositionResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy vị trí"));
    }

    @Transactional
    public PositionResponse create(PositionCreateRequest request) {
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm"));

        Position entity = new Position();
        applyForm(entity, product, request);
        return PositionResponse.from(positionRepository.save(entity));
    }

    @Transactional
    public PositionResponse update(Integer id, PositionCreateRequest request) {
        Position entity = positionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy vị trí"));
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm"));

        applyForm(entity, product, request);
        return PositionResponse.from(positionRepository.save(entity));
    }

    private void applyForm(Position entity, Product product, PositionCreateRequest request) {
        entity.setProductID(product);
        entity.setName(request.getName().trim());
    }

    private boolean matchesKeyword(PositionResponse position, String normalizedKeyword) {
        return startsWithNormalized(position.getName(), normalizedKeyword)
                || startsWithNormalized(position.getProductName(), normalizedKeyword)
                || startsWithNormalized(position.getProductCode(), normalizedKeyword)
                || startsWithNormalized(formatCode("VT", position.getId()), normalizedKeyword);
    }

    private String formatCode(String prefix, Integer id) {
        return id != null ? prefix + "-" + String.format("%05d", id) : prefix + "-";
    }

    private Page<PositionResponse> paginate(List<PositionResponse> filtered, Pageable pageable) {
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        List<PositionResponse> content = start >= filtered.size()
                ? List.of()
                : filtered.subList(start, end);
        return new PageImpl<>(content, pageable, filtered.size());
    }

    private boolean startsWithNormalized(String value, String normalizedKeyword) {
        return value != null && normalize(value).startsWith(normalizedKeyword);
    }

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
