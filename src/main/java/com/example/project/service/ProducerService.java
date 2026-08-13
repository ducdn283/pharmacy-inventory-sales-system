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

@Service
public class ProducerService {
    private final ProducerRepository producerRepository;

    public ProducerService(ProducerRepository producerRepository) {
        this.producerRepository = producerRepository;
    }

    @Transactional(readOnly = true)
    public Page<ProducerResponse> list(String search, Pageable pageable) {
        String normalizedKeyword = normalize(search);

        List<ProducerResponse> filtered = producerRepository.findAll(Sort.by("id").ascending()).stream()
                .map(ProducerResponse::from)
                .filter(producer -> normalizedKeyword.isEmpty() || matchesKeyword(producer, normalizedKeyword))
                .toList();

        return paginate(filtered, pageable);
    }

    @Transactional(readOnly = true)
    public long countAll() {
        return producerRepository.count();
    }

    @Transactional(readOnly = true)
    public ProducerResponse getById(Integer id) {
        return producerRepository.findById(id)
                .map(ProducerResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà sản xuất"));
    }

    @Transactional
    public ProducerResponse create(ProducerCreateRequest request) {
        Producer producer = new Producer();
        producer.setName(request.getName().trim());
        return ProducerResponse.from(producerRepository.save(producer));
    }

    @Transactional
    public ProducerResponse update(Integer id, ProducerCreateRequest request) {
        Producer producer = producerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà sản xuất"));
        producer.setName(request.getName().trim());
        return ProducerResponse.from(producerRepository.save(producer));
    }

    private boolean matchesKeyword(ProducerResponse producer, String normalizedKeyword) {
        return startsWithNormalized(producer.getName(), normalizedKeyword)
                || startsWithNormalized(formatCode("NSX", producer.getId()), normalizedKeyword);
    }

    private String formatCode(String prefix, Integer id) {
        return id != null ? prefix + "-" + String.format("%05d", id) : prefix + "-";
    }

    private Page<ProducerResponse> paginate(List<ProducerResponse> filtered, Pageable pageable) {
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        List<ProducerResponse> content = start >= filtered.size()
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
