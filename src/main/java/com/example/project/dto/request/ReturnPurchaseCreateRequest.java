package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Form backing the "Create supplier return" screen. The Owner picks one received purchase invoice,
 * then chooses how many units of each of its lines to return to the supplier, plus a reason.
 *
 * <p>No money is posted from here: the refund is computed server-side from each
 * batch's import cost and only recorded on the slip. Collecting it back from the supplier belongs to
 * the Income module.</p>
 */
@Getter
@Setter
public class ReturnPurchaseCreateRequest {

    /** The original purchase invoice being returned against. */
    private Integer purchaseId;

    /** Reason for the return (required). */
    private String reason;

    /** Optional free-text note. */
    private String note;

    /**
     * SỐ TIỀN nhà cung cấp chấp nhận hoàn cho cả phiếu, bằng đồng — người lập gõ thẳng con số NCC
     * báo lại. Bắt buộc, phải {@code > 0} và không vượt quá giá trị hàng trả.
     *
     * <p><b>Vì sao là số tiền chứ không còn là tỷ lệ %</b> (BA chốt 13/08/2026): tiền hoàn từng được
     * tính bằng {@code giá nhập từng dòng × tỷ lệ %}, tức luôn đo trên giá niêm yết của phiếu nhập.
     * Phiếu nhập có chiết khấu / phụ phí thì con số đó KHÔNG bằng số nhà thuốc đã thực trả, và đòi
     * NCC hoàn theo nó là hoàn thừa. Bên NCC đã tự tính ra số họ chấp nhận hoàn (đã gồm mọi khoản
     * điều chỉnh), nên nhà thuốc chỉ việc điền vào — hết chuyện phải suy ra từ chiết khấu.</p>
     *
     * <p>Chênh lệch giữa giá trị hàng trả và số này là khoản LỖ, tính động vào chi phí hợp lý TNCN
     * nếu có chứng từ (mục 1.3 + 4.5) — cách hiểu không đổi so với thời còn tỷ lệ %.</p>
     */
    private BigDecimal refundAmount;

    /** One entry per candidate purchase line; blank/zero rows are dropped server-side. */
    private List<ReturnPurchaseLineRequest> items = new ArrayList<>();
}
