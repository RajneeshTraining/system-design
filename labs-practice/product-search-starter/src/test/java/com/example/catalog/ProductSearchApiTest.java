package com.example.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ProductSearchApiTest {
    @Autowired MockMvc mvc;
    @Test void searchesAndSortsByPrice() throws Exception {
        mvc.perform(get("/api/v1/products").param("categoryId","RUNNING_SHOES").param("sort","PRICE_ASC"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].productId").value("SKU-1008"))
            .andExpect(jsonPath("$.totalElements").value(6));
    }
    @Test void rejectsInvalidPriceRange() throws Exception {
        mvc.perform(get("/api/v1/products").param("minPrice","5000").param("maxPrice","1000"))
            .andExpect(status().isBadRequest());
    }
}
