package com.logsmanage.system.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse {

    private List<Map<String, Object>> records;
    private long total;
    private int pageNum;
    private int pageSize;
    private long totalPages;
    private boolean hasNext;
    private boolean hasPrevious;
}
