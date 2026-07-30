package com.example.project.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class DebtOffsetRequest {

    private String partyType;
    private Integer entityId;
    private List<DebtOffsetLineRequest> receivableLines = new ArrayList<>();
    private List<DebtOffsetLineRequest> payableLines = new ArrayList<>();
    private String note;
}
