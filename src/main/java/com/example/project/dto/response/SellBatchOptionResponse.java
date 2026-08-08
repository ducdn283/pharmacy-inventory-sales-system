package com.example.project.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;

/** One in-stock batch embedded into the create-invoice page for lot selection. */
@Getter
@AllArgsConstructor
public class SellBatchOptionResponse {

    private Integer batchId;
    private String batchCode;
    private String lotNumber;

    @JsonIgnore
    private LocalDate expirationDate;
    private String expirationDateDisplay;

    private Integer storageQuantity;

    /** True when expirationDate is before today — shown on the sell screen but not selectable. */
    private boolean expired;
}
