package com.example.catalog.search;

import com.example.catalog.api.ProductSearchResponse;
import com.example.catalog.domain.Product;
import com.example.catalog.domain.ProductSort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
@ConditionalOnProperty(name = "app.search.mode", havingValue = "opensearch")
public class OpenSearchProductSearchStore implements ProductSearchStore {
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String baseUrl;
    private final String index;

    public OpenSearchProductSearchStore(ObjectMapper mapper,
            @Value("${app.search.opensearch-url}") String baseUrl,
            @Value("${app.search.index-name}") String index) {
        this.mapper = mapper; this.baseUrl = baseUrl.replaceAll("/$", ""); this.index = index;
    }

    @PostConstruct
    void initializeIndexAndSeed() throws Exception {
        HttpResponse<String> exists = send("HEAD", "/" + index, null);
        if (exists.statusCode() == 404) {
            String mapping = "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0},\"mappings\":{\"properties\":{\"productId\":{\"type\":\"keyword\"},\"title\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"description\":{\"type\":\"text\"},\"categoryId\":{\"type\":\"keyword\"},\"brand\":{\"type\":\"keyword\"},\"price\":{\"type\":\"double\"},\"currency\":{\"type\":\"keyword\"},\"discountPercentage\":{\"type\":\"double\"},\"averageRating\":{\"type\":\"double\"},\"ratingCount\":{\"type\":\"long\"},\"colors\":{\"type\":\"keyword\"},\"availableSizes\":{\"type\":\"keyword\"},\"inStock\":{\"type\":\"boolean\"},\"createdAt\":{\"type\":\"date\"},\"popularityScore\":{\"type\":\"long\"},\"searchable\":{\"type\":\"boolean\"}}}}";
            HttpResponse<String> created = send("PUT", "/" + index, mapping);
            if (created.statusCode() >= 300) throw new IllegalStateException("Could not create OpenSearch index: " + created.body());
            seedSampleProducts();
        } else if (exists.statusCode() >= 300) {
            throw new IllegalStateException("OpenSearch index check failed: HTTP " + exists.statusCode());
        }
    }

    private void seedSampleProducts() throws Exception {
        List<Product> seed = List.of(
            product("SKU-1001","Nike Men's Running Shoes","Lightweight road running shoes","RUNNING_SHOES","Nike",3499,20,4.4,1250,List.of("BLACK","BLUE"),true,"2026-09-20T10:15:00Z",850),
            product("SKU-1002","Adidas Ultraboost Running Shoes","Cushioned running shoes for daily training","RUNNING_SHOES","Adidas",4999,15,4.7,2100,List.of("BLACK","WHITE"),true,"2026-10-01T08:00:00Z",950),
            product("SKU-1003","Puma Flex Running Shoes","Affordable flexible running shoes","RUNNING_SHOES","Puma",2299,25,4.1,430,List.of("BLUE","RED"),true,"2026-08-14T12:00:00Z",620),
            product("SKU-1004","Asics Gel Nimbus","Premium running shoes with gel cushioning","RUNNING_SHOES","Asics",7999,10,4.8,980,List.of("BLACK","GREY"),false,"2026-10-10T11:00:00Z",780),
            product("SKU-1005","Sony Wireless Headphones","Noise cancelling over-ear headphones","HEADPHONES","Sony",8999,12,4.6,3400,List.of("BLACK","WHITE"),true,"2026-07-12T09:00:00Z",1100),
            product("SKU-1006","JBL Bluetooth Headphones","Portable wireless headphones","HEADPHONES","JBL",2999,18,4.2,890,List.of("BLACK","BLUE","RED"),true,"2026-09-28T09:30:00Z",730),
            product("SKU-1007","New Balance Fresh Foam","Comfortable running shoes","RUNNING_SHOES","New Balance",5499,8,4.5,670,List.of("WHITE","GREY"),true,"2026-10-12T07:00:00Z",700),
            product("SKU-1008","Reebok Training Shoes","Everyday gym and running shoes","RUNNING_SHOES","Reebok",1999,30,3.9,210,List.of("BLACK","RED"),true,"2026-06-30T07:00:00Z",510));
        for (Product p : seed) {
            String doc = mapper.writeValueAsString(p);
            send("PUT", "/" + index + "/_doc/" + p.productId() + "?refresh=true", doc);
        }
    }
    private Product product(String id,String title,String desc,String cat,String brand,double price,double discount,double rating,long count,List<String> colors,boolean stock,String created,long pop) {
        return new Product(id,title,desc,cat,brand,BigDecimal.valueOf(price),"INR",discount,rating,count,colors,List.of("8","9","10"),stock,Instant.parse(created),pop,true);
    }

    @Override public ProductSearchResponse search(String q, String categoryId, List<String> colors, List<String> brands,
            BigDecimal minPrice, BigDecimal maxPrice, Double minRating, Boolean inStock, ProductSort sort, int page, int size) {
        try {
            List<Object> filters = new ArrayList<>();
            filters.add(Map.of("term", Map.of("searchable", true)));
            if (categoryId != null && !categoryId.isBlank()) filters.add(Map.of("term", Map.of("categoryId", categoryId)));
            if (!colors.isEmpty()) filters.add(Map.of("terms", Map.of("colors", Arrays.stream(colors.toArray(String[]::new)).map(s -> s.toUpperCase(Locale.ROOT)).toList())));
            if (!brands.isEmpty()) filters.add(Map.of("terms", Map.of("brand", brands)));
            if (minPrice != null || maxPrice != null) {
                Map<String,Object> range = new LinkedHashMap<>(); if(minPrice!=null)range.put("gte",minPrice); if(maxPrice!=null)range.put("lte",maxPrice);
                filters.add(Map.of("range",Map.of("price",range)));
            }
            if (minRating != null) filters.add(Map.of("range",Map.of("averageRating",Map.of("gte",minRating))));
            if (inStock != null) filters.add(Map.of("term",Map.of("inStock",inStock)));
            Map<String,Object> query;
            if (q != null && !q.isBlank()) {
                query = Map.of("bool",Map.of("must",List.of(Map.of("multi_match",Map.of("query",q,"fields",List.of("title^3","brand^2","description")))),"filter",filters));
            } else query = Map.of("bool",Map.of("filter",filters));
            List<Object> sortList = new ArrayList<>();
            switch (sort == null ? ProductSort.RELEVANCE : sort) {
                case PRICE_ASC -> sortList.add(Map.of("price",Map.of("order","asc")));
                case PRICE_DESC -> sortList.add(Map.of("price",Map.of("order","desc")));
                case NEWEST -> sortList.add(Map.of("createdAt",Map.of("order","desc")));
                case RATING_DESC -> { sortList.add(Map.of("averageRating",Map.of("order","desc"))); sortList.add(Map.of("ratingCount",Map.of("order","desc"))); }
                case POPULARITY_DESC -> sortList.add(Map.of("popularityScore",Map.of("order","desc")));
                case DISCOUNT_DESC -> sortList.add(Map.of("discountPercentage",Map.of("order","desc")));
                case RELEVANCE -> sortList.add(Map.of("_score",Map.of("order","desc")));
            }
            sortList.add(Map.of("productId",Map.of("order","asc")));
            Map<String,Object> body = new LinkedHashMap<>(); body.put("from",page*size); body.put("size",size); body.put("query",query); body.put("sort",sortList);
            body.put("aggs",Map.of("brands",Map.of("terms",Map.of("field","brand","size",50)),"colors",Map.of("terms",Map.of("field","colors","size",30))));
            JsonNode root = mapper.readTree(send("POST","/"+index+"/_search",mapper.writeValueAsString(body)).body());
            if (root.has("error")) throw new IllegalStateException(root.get("error").toString());
            JsonNode hits = root.path("hits"); long total = hits.path("total").isObject() ? hits.path("total").path("value").asLong() : hits.path("total").asLong();
            List<Product> items = new ArrayList<>();
            for (JsonNode hit : hits.path("hits")) items.add(mapper.treeToValue(hit.path("_source"),Product.class));
            Map<String,Map<String,Long>> facets = new LinkedHashMap<>();
            for (String facet : List.of("brands","colors")) {
                Map<String,Long> values = new TreeMap<>(); for(JsonNode bucket:root.path("aggregations").path(facet).path("buckets")) values.put(bucket.path("key").asText(),bucket.path("doc_count").asLong()); facets.put(facet,values);
            }
            return new ProductSearchResponse(items,page,size,total,(int)Math.ceil((double)total/size),facets);
        } catch (Exception e) { throw new IllegalStateException("Product search failed: " + e.getMessage(), e); }
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10)).header("Accept","application/json");
        if (body == null) builder.method(method,HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
}
