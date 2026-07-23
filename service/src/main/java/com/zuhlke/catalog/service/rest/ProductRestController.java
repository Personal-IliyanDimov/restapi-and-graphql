package com.zuhlke.catalog.service.rest;

import com.zuhlke.catalog.schema.api.ProductsApi;
import com.zuhlke.catalog.schema.model.Product;
import com.zuhlke.catalog.schema.model.ProductInput;
import com.zuhlke.catalog.schema.model.ProductPage;
import com.zuhlke.catalog.service.ProductCatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Implements the {@link ProductsApi} interface generated (by the `schema` module, from
 * openapi/openapi.yaml) via OpenAPI Generator - i.e. this class supplies behavior for a
 * contract that already exists; it does not define the contract itself.
 *
 * NOTE: method signatures here are written to match the standard openapi-generator "spring"
 * output for this spec (interfaceOnly=true, skipDefaultInterface=true, useSpringBoot3=true).
 * If you change openapi.yaml in a way that reshapes ProductsApi, re-run
 * `./gradlew :schema:openApiGenerate` and let the compiler's @Override mismatch point you at
 * whatever needs adjusting here.
 */
@RestController
@RequestMapping("/api")
public class ProductRestController implements ProductsApi {

    private final ProductCatalogService catalogService;

    public ProductRestController(ProductCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Override
    public ResponseEntity<ProductPage> listProducts(Integer page, Integer size) {
        return ResponseEntity.ok(catalogService.list(orDefault(page, 0), orDefault(size, 20)));
    }

    @Override
    public ResponseEntity<Product> createProduct(ProductInput productInput) {
        Product created = catalogService.create(productInput);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Override
    public ResponseEntity<ProductPage> searchProducts(
            String name, String category, Double minPrice, Double maxPrice, Integer page, Integer size) {
        return ResponseEntity.ok(
                catalogService.search(name, category, minPrice, maxPrice, orDefault(page, 0), orDefault(size, 20)));
    }

    @Override
    public ResponseEntity<Product> getProductById(UUID productId) {
        return ResponseEntity.ok(catalogService.get(productId));
    }

    @Override
    public ResponseEntity<Product> updateProduct(UUID productId, ProductInput productInput) {
        return ResponseEntity.ok(catalogService.update(productId, productInput));
    }

    @Override
    public ResponseEntity<Void> deleteProduct(UUID productId) {
        catalogService.delete(productId);
        return ResponseEntity.noContent().build();
    }

    private static int orDefault(Integer value, int fallback) {
        return value != null ? value : fallback;
    }
}
