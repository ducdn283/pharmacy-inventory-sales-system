package com.example.project.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class StockReviewPrintPageResponse {

    private String printDateDisplay;

    private String printedByName;

    private String type;

    private String typeDisplay;

    private long totalItems;

    private List<StockReviewPrintLineResponse> items;
}