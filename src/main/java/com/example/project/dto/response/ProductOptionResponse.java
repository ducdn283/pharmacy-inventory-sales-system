package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Bản rút gọn (id, tên) của {@link com.example.project.entity.Product}, dùng cho ô chọn sản phẩm
 * ở trang tạo Phiếu nhập. Cố tình không dùng entity {@code Product} trực tiếp: trang đó nhúng danh
 * sách sản phẩm vào JS inline ({@code th:inline="javascript"}), Jackson sẽ serialize luôn cả các
 * quan hệ {@code @ManyToOne} lazy như {@code typeID}/{@code producerID} và lỗi
 * {@code InvalidDefinitionException} khi gặp Hibernate proxy chưa khởi tạo. DTO này chỉ mang đúng
 * 2 field cần dùng nên không bao giờ đụng tới proxy.
 */
@Getter
@AllArgsConstructor
public class ProductOptionResponse {

    private Integer productID;

    private String name;
}
