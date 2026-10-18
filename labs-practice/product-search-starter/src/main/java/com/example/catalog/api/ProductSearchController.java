package com.example.catalog.api;

import com.example.catalog.domain.ProductSort;
import com.example.catalog.search.ProductSearchStore;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/products")
@Validated
public class ProductSearchController {
    private final ProductSearchStore store;
    public ProductSearchController(ProductSearchStore store) { this.store = store; }

    @GetMapping
    public ProductSearchResponse search(
            @RequestParam(required = false) @Size(max = 120) String q,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String[] colors,
            @RequestParam(required = false) String[] brands,
            @RequestParam(required = false) @DecimalMin("0.00") BigDecimal minPrice,
            @RequestParam(required = false) @DecimalMin("0.00") BigDecimal maxPrice,
            @RequestParam(required = false) @DecimalMin("0.0") @DecimalMax("5.0") Double minRating,
            @RequestParam(required = false) Boolean inStock,
            @RequestParam(defaultValue = "RELEVANCE") ProductSort sort,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new IllegalArgumentException("minPrice must be less than or equal to maxPrice");
        }
        return store.search(q, categoryId, colors == null ? java.util.List.of() : java.util.List.of(colors),
                brands == null ? java.util.List.of() : java.util.List.of(brands), minPrice, maxPrice,
                minRating, inStock, sort, page, size);
    }
}
