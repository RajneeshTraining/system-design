package com.example.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record Product(
        String productId,
        String title,
        String description,
        String categoryId,
        String brand,
        BigDecimal price,
        String currency,
        double discountPercentage,
        double averageRating,
        long ratingCount,
        List<String> colors,
        List<String> availableSizes,
        boolean inStock,
        Instant createdAt,
        long popularityScore,
        boolean searchable) {
}
