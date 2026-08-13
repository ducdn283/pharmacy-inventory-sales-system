package com.example.project.dto.response;

/**
 * Kết quả của một lệnh "Tạo phiếu", tách được hai ca mà từ ngoài nhìn giống hệt nhau:
 * phiếu vừa được tạo mới, hay phiếu đã tồn tại từ một lần bấm trước đó.
 *
 * <p>Có kiểu trả về này vì fragment {@code fragments/single-submit} chỉ chặn được cú bấm thứ hai
 * TRONG CÙNG một lần tải trang. Khi request đã tới server và đã commit nhưng phản hồi rớt giữa
 * đường (mất mạng, trình duyệt bị đóng), người dùng tải lại trang rồi bấm Tạo lần nữa — lúc đó
 * cờ khoá phía màn hình đã mất, và nếu không có gì kiểm thì phiếu thứ hai sẽ được tạo thật.
 * Service phát hiện ca đó rồi trả về id của phiếu CŨ kèm {@code duplicate = true}; controller
 * chuyển thẳng người dùng tới phiếu đó với một thông báo cảnh báo thay vì báo "tạo thành công"
 * cho một phiếu mà họ không định tạo.</p>
 *
 * @param id        id phiếu để chuyển hướng tới — phiếu vừa tạo, hoặc phiếu cũ khi trùng.
 * @param duplicate {@code true} nghĩa là KHÔNG có phiếu nào được tạo thêm ở lần bấm này.
 * @param message   câu giải thích cho ca trùng (kèm mã phiếu + giờ tạo); {@code null} khi tạo mới.
 */
public record SlipCreateOutcome(Integer id, boolean duplicate, String message) {

    public static SlipCreateOutcome created(Integer id) {
        return new SlipCreateOutcome(id, false, null);
    }

    public static SlipCreateOutcome duplicate(Integer id, String message) {
        return new SlipCreateOutcome(id, true, message);
    }
}
