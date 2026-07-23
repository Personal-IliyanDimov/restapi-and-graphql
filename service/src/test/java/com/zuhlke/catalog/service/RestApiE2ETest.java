package com.zuhlke.catalog.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test of the REST contract in openapi/openapi.yaml: exercises create, read,
 * update, delete, list and search exactly as an external HTTP client would, over the real
 * Spring MVC dispatcher (no mocked layers below the controller).
 */
@SpringBootTest
@AutoConfigureMockMvc
class RestApiE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createReadUpdateDeleteAndSearch() throws Exception {
        String createBody = """
                {
                  "name": "Wireless Mouse",
                  "description": "Ergonomic wireless mouse",
                  "price": 29.99,
                  "quantity": 100,
                  "category": "Accessories"
                }
                """;

        String location = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Wireless Mouse"))
                .andReturn().getResponse().getContentAsString();

        String id = com.jayway.jsonpath.JsonPath.read(location, "$.id");

        mockMvc.perform(get("/api/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Wireless Mouse"));

        String updateBody = """
                {
                  "name": "Wireless Mouse Pro",
                  "description": "Ergonomic wireless mouse, v2",
                  "price": 34.99,
                  "quantity": 80,
                  "category": "Accessories"
                }
                """;

        mockMvc.perform(put("/api/products/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Wireless Mouse Pro"))
                .andExpect(jsonPath("$.price").value(34.99));

        mockMvc.perform(get("/api/products").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        mockMvc.perform(get("/api/products/search")
                        .param("name", "mouse")
                        .param("category", "Accessories")
                        .param("minPrice", "10")
                        .param("maxPrice", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Wireless Mouse Pro"));

        mockMvc.perform(delete("/api/products/{id}", id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/products/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void searchWithNoMatchesReturnsEmptyPageNotError() throws Exception {
        mockMvc.perform(get("/api/products/search").param("name", "definitely-does-not-exist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
