package com.example.catalog.search;

import com.example.catalog.api.ProductSearchResponse;
import com.example.catalog.domain.Product;
import com.example.catalog.domain.ProductSort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@ConditionalOnProperty(name = "app.search.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryProductSearchStore implements ProductSearchStore {
    private final List<Product> products = List.of(
        p("SKU-1001","Nike Men's Running Shoes","Lightweight road running shoes","RUNNING_SHOES","Nike",3499,"INR",20,4.4,1250,List.of("BLACK","BLUE"),List.of("8","9","10"),true,"2026-09-20T10:15:00Z",850),
        p("SKU-1002","Adidas Ultraboost Running Shoes","Cushioned running shoes for daily training","RUNNING_SHOES","Adidas",4999,"INR",15,4.7,2100,List.of("BLACK","WHITE"),List.of("7","8","9"),true,"2026-10-01T08:00:00Z",950),
        p("SKU-1003","Puma Flex Running Shoes","Affordable flexible running shoes","RUNNING_SHOES","Puma",2299,"INR",25,4.1,430,List.of("BLUE","RED"),List.of("8","9","10","11"),true,"2026-08-14T12:00:00Z",620),
        p("SKU-1004","Asics Gel Nimbus","Premium running shoes with gel cushioning","RUNNING_SHOES","Asics",7999,"INR",10,4.8,980,List.of("BLACK","GREY"),List.of("8","9","10"),false,"2026-10-10T11:00:00Z",780),
        p("SKU-1005","Sony Wireless Headphones","Noise cancelling over-ear headphones","HEADPHONES","Sony",8999,"INR",12,4.6,3400,List.of("BLACK","WHITE"),List.of("ONE_SIZE"),true,"2026-07-12T09:00:00Z",1100),
        p("SKU-1006","JBL Bluetooth Headphones","Portable wireless headphones","HEADPHONES","JBL",2999,"INR",18,4.2,890,List.of("BLACK","BLUE","RED"),List.of("ONE_SIZE"),true,"2026-09-28T09:30:00Z",730),
        p("SKU-1007","New Balance Fresh Foam","Comfortable running shoes","RUNNING_SHOES","New Balance",5499,"INR",8,4.5,670,List.of("WHITE","GREY"),List.of("8","9","10"),true,"2026-10-12T07:00:00Z",700),
        p("SKU-1008","Reebok Training Shoes","Everyday gym and running shoes","RUNNING_SHOES","Reebok",1999,"INR",30,3.9,210,List.of("BLACK","RED"),List.of("7","8","9"),true,"2026-06-30T07:00:00Z",510)
    );

    private static Product p(String id,String title,String desc,String category,String brand,double price,String currency,double discount,double rating,long count,List<String> colors,List<String> sizes,boolean stock,String created,long popularity) {
        return new Product(id,title,desc,category,brand,BigDecimal.valueOf(price),currency,discount,rating,count,colors,sizes,stock,Instant.parse(created),popularity,true);
    }

    @Override public ProductSearchResponse search(String q, String categoryId, List<String> colors, List<String> brands,
            BigDecimal minPrice, BigDecimal maxPrice, Double minRating, Boolean inStock, ProductSort sort, int page, int size) {
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        Stream<Product> stream = products.stream().filter(Product::searchable)
            .filter(p -> query.isBlank() || (p.title()+" "+p.description()+" "+p.brand()).toLowerCase(Locale.ROOT).contains(query))
            .filter(p -> categoryId == null || p.categoryId().equalsIgnoreCase(categoryId))
            .filter(p -> colors.isEmpty() || colors.stream().map(s->s.toUpperCase(Locale.ROOT)).anyMatch(c -> p.colors().contains(c)))
            .filter(p -> brands.isEmpty() || brands.stream().anyMatch(b -> p.brand().equalsIgnoreCase(b)))
            .filter(p -> minPrice == null || p.price().compareTo(minPrice) >= 0)
            .filter(p -> maxPrice == null || p.price().compareTo(maxPrice) <= 0)
            .filter(p -> minRating == null || p.averageRating() >= minRating)
            .filter(p -> inStock == null || p.inStock() == inStock);
        Comparator<Product> comparator = switch (sort == null ? ProductSort.RELEVANCE : sort) {
            case PRICE_ASC -> Comparator.comparing(Product::price).thenComparing(Product::productId);
            case PRICE_DESC -> Comparator.comparing(Product::price).reversed().thenComparing(Product::productId);
            case NEWEST -> Comparator.comparing(Product::createdAt).reversed().thenComparing(Product::productId);
            case RATING_DESC -> Comparator.comparingDouble(Product::averageRating).reversed().thenComparing(Comparator.comparingLong(Product::ratingCount).reversed()).thenComparing(Product::productId);
            case POPULARITY_DESC -> Comparator.comparingLong(Product::popularityScore).reversed().thenComparing(Product::productId);
            case DISCOUNT_DESC -> Comparator.comparingDouble(Product::discountPercentage).reversed().thenComparing(Product::productId);
            case RELEVANCE -> Comparator.comparing(Product::productId);
        };
        List<Product> matched = stream.sorted(comparator).toList();
        Map<String, Map<String, Long>> facets = new LinkedHashMap<>();
        facets.put("brands", matched.stream().collect(Collectors.groupingBy(Product::brand, TreeMap::new, Collectors.counting())));
        facets.put("colors", matched.stream().flatMap(p -> p.colors().stream()).collect(Collectors.groupingBy(c -> c, TreeMap::new, Collectors.counting())));
        int start = Math.min(page * size, matched.size());
        int end = Math.min(start + size, matched.size());
        return new ProductSearchResponse(matched.subList(start, end), page, size, matched.size(), (int)Math.ceil((double)matched.size()/size), facets);
    }
}
