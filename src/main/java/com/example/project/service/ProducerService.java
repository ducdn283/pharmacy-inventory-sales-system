package com.example.project.service;

import com.example.project.dto.request.ProducerCreateRequest;
import com.example.project.dto.response.ProducerResponse;
import com.example.project.entity.Producer;
import com.example.project.repository.ProducerRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Nghiệp vụ nhà sản xuất ({@link Producer}) phục vụ màn quản lý Owner ({@code /owner/producers/**}).
 */
@Service
public class ProducerService {
    private final ProducerRepository producerRepository;

    public ProducerService(ProducerRepository producerRepository) {
        this.producerRepository = producerRepository;
    }

    /**
     * Danh sách nhà sản xuất có phân trang và tìm kiếm.
     * Tìm kiếm khớp đầu chuỗi trên tên hoặc mã {@code NSX-xxxxx}.
     */
    @Transactional(readOnly = true)
    public Page<ProducerResponse> list(String search, Pageable pageable) {
        String normalizedKeyword = normalize(search);

        List<ProducerResponse> filtered = producerRepository.findAll(Sort.by("id").ascending()).stream()
                .map(ProducerResponse::from)
                .filter(producer -> normalizedKeyword.isEmpty() || matchesKeyword(producer, normalizedKeyword))
                .toList();

        return paginate(filtered, pageable);
    }

    /** Tổng số nhà sản xuất — thẻ thống kê màn danh sách Owner. */
    @Transactional(readOnly = true)
    public long countAll() {
        return producerRepository.count();
    }

    /** Chi tiết một nhà sản xuất — form cập nhật Owner. */
    @Transactional(readOnly = true)
    public ProducerResponse getById(Integer id) {
        return producerRepository.findById(id)
                .map(ProducerResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà sản xuất"));
    }

    /** Tạo nhà sản xuất mới — chỉ Owner (hoặc quick-create từ form hàng hóa). */
    @Transactional
    public ProducerResponse create(ProducerCreateRequest request) {
        Producer producer = new Producer();
        producer.setName(request.getName().trim());
        return ProducerResponse.from(producerRepository.save(producer));
    }

    /** Cập nhật nhà sản xuất — chỉ Owner. */
    @Transactional
    public ProducerResponse update(Integer id, ProducerCreateRequest request) {
        Producer producer = producerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà sản xuất"));
        producer.setName(request.getName().trim());
        return ProducerResponse.from(producerRepository.save(producer));
    }

    /** Kiểm tra từ khóa tìm kiếm (đã bỏ dấu) có khớp tên hoặc mã nhà sản xuất không. */
    private boolean matchesKeyword(ProducerResponse producer, String normalizedKeyword) {
        return startsWithNormalized(producer.getName(), normalizedKeyword)
                || startsWithNormalized(formatCode("NSX", producer.getId()), normalizedKeyword);
    }

    /** Mã hiển thị nhà sản xuất: {@code NSX-00001}. */
    private String formatCode(String prefix, Integer id) {
        return id != null ? prefix + "-" + String.format("%05d", id) : prefix + "-";
    }

    /** Cắt danh sách đã lọc theo {@link Pageable} — vì lọc chạy trong bộ nhớ. */
    private Page<ProducerResponse> paginate(List<ProducerResponse> filtered, Pageable pageable) {
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        List<ProducerResponse> content = start >= filtered.size()
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
