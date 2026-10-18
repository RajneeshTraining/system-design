package com.example.catalog.api;

import com.example.catalog.domain.Product;
import java.util.List;
import java.util.Map;

public record ProductSearchResponse(
        List<Product> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        Map<String, Map<String, Long>> facets) {
}
