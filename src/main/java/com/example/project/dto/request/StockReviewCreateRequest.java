package com.example.project.dto.request;

import com.example.project.constant.StockReviewType;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class StockReviewCreateRequest {

    private String type = StockReviewType.COUNT;

    private String note;

    private List<StockReviewItemRequest> items = new ArrayList<>();
}