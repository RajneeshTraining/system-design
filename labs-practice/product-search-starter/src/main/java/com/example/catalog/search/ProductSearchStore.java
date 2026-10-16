package com.example.catalog.search;

import com.example.catalog.api.ProductSearchResponse;
import com.example.catalog.domain.ProductSort;
import java.math.BigDecimal;
import java.util.List;

public interface ProductSearchStore {
    ProductSearchResponse search(String q, String categoryId, List<String> colors,
            List<String> brands, BigDecimal minPrice, BigDecimal maxPrice,
            Double minRating, Boolean inStock, ProductSort sort, int page, int size);
}
