package com.example.project.service;

import com.example.project.dto.request.TypeCreateRequest;
import com.example.project.dto.response.TypeResponse;
import com.example.project.entity.Type;
import com.example.project.repository.TypeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Nghiệp vụ loại hàng ({@link Type}) phục vụ màn quản lý Owner ({@code /owner/types/**}).
 */
@Service
public class TypeService {
    private final TypeRepository typeRepository;

    public TypeService(TypeRepository typeRepository) {
        this.typeRepository = typeRepository;
    }

    /**
     * Danh sách loại hàng có phân trang, lọc theo nhóm mặt hàng và tìm kiếm.
     * Tìm kiếm khớp đầu chuỗi trên tên loại, nhóm mặt hàng hoặc mã {@code LH-xxxxx}.
     */
    @Transactional(readOnly = true)
    public Page<TypeResponse> list(String search, String sortType, Pageable pageable) {
        String normalizedKeyword = normalize(search);
        String group = sortType == null ? "" : sortType.trim();

        List<TypeResponse> filtered = typeRepository.findAll(Sort.by("id").ascending()).stream()
                .map(TypeResponse::from)
                .filter(type -> group.isEmpty()
                        || (type.getSortType() != null && group.equalsIgnoreCase(type.getSortType())))
                .filter(type -> normalizedKeyword.isEmpty() || matchesKeyword(type, normalizedKeyword))
                .toList();

        return paginate(filtered, pageable);
    }

    /** Tổng số loại hàng — thẻ thống kê màn danh sách Owner. */
    @Transactional(readOnly = true)
    public long countAll() {
        return typeRepository.count();
    }

    /** Danh sách nhóm mặt hàng ({@code sortType}) — dropdown lọc / form tạo-sửa Owner. */
    @Transactional(readOnly = true)
    public List<String> listSortTypes() {
        return typeRepository.findDistinctSortTypes()
                .stream()
                .filter(value -> value != null && !value.isBlank())
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    /** Chi tiết một loại — form cập nhật Owner. */
    @Transactional(readOnly = true)
    public TypeResponse getById(Integer id) {
        return typeRepository.findById(id)
                .map(TypeResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy loại hàng"));
    }

    /** Tạo loại hàng mới — chỉ Owner. */
    @Transactional
    public TypeResponse create(TypeCreateRequest request) {
        Type type = new Type();
        type.setSortType(request.getSortType().trim());
        type.setName(request.getName().trim());
        type.setDefaultVATRate(request.getDefaultVATRate());
        return TypeResponse.from(typeRepository.save(type));
    }

    /** Cập nhật loại hàng — chỉ Owner. */
    @Transactional
    public TypeResponse update(Integer id, TypeCreateRequest request) {
        Type type = typeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy loại hàng"));
        type.setSortType(request.getSortType().trim());
        type.setName(request.getName().trim());
        type.setDefaultVATRate(request.getDefaultVATRate());
        return TypeResponse.from(typeRepository.save(type));
    }

    /** Kiểm tra từ khóa tìm kiếm (đã bỏ dấu) có khớp tên, nhóm hoặc mã loại không. */
    private boolean matchesKeyword(TypeResponse type, String normalizedKeyword) {
        return startsWithNormalized(type.getName(), normalizedKeyword)
                || startsWithNormalized(type.getSortType(), normalizedKeyword)
                || startsWithNormalized(formatCode("LH", type.getId()), normalizedKeyword);
    }

    /** Mã hiển thị loại hàng: {@code LH-00001}. */
    private String formatCode(String prefix, Integer id) {
        return id != null ? prefix + "-" + String.format("%05d", id) : prefix + "-";
    }

    /** Cắt danh sách đã lọc theo {@link Pageable} — vì lọc chạy trong bộ nhớ. */
    private Page<TypeResponse> paginate(List<TypeResponse> filtered, Pageable pageable) {
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        List<TypeResponse> content = start >= filtered.size()
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
