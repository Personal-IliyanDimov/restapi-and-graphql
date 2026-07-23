package com.zuhlke.catalog.service.graphql;

import com.zuhlke.catalog.schema.model.Product;
import com.zuhlke.catalog.schema.model.ProductInput;
import com.zuhlke.catalog.schema.model.ProductPage;
import com.zuhlke.catalog.service.ProductCatalogService;
import com.zuhlke.catalog.service.domain.ProductNotFoundException;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.UUID;

/**
 * Resolvers for graphql/schema.graphqls (see schema module). Every method delegates to the
 * exact same {@link ProductCatalogService} the REST controller uses, and every input/output
 * object it passes around (Product, ProductInput, ProductPage) is the exact same generated
 * Java class the REST layer uses too - see schema/build.gradle.kts's `typeMapping` for how
 * that's enforced at code-gen time.
 *
 * GraphQL's ID scalar is transport-level String; it is converted to the shared domain's
 * UUID at the edge here, same as Spring MVC's path-variable conversion does for REST.
 */
@Controller
public class ProductGraphQlController {

    private final ProductCatalogService catalogService;

    public ProductGraphQlController(ProductCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @QueryMapping
    public Product product(@Argument String id) {
        try {
            return catalogService.get(UUID.fromString(id));
        } catch (ProductNotFoundException ex) {
            return null;
        }
    }

    @QueryMapping
    public ProductPage products(@Argument Integer page, @Argument Integer size) {
        return catalogService.list(orDefault(page, 0), orDefault(size, 20));
    }

    @QueryMapping
    public ProductPage searchProducts(
            @Argument String name,
            @Argument String category,
            @Argument Double minPrice,
            @Argument Double maxPrice,
            @Argument Integer page,
            @Argument Integer size) {
        return catalogService.search(name, category, minPrice, maxPrice, orDefault(page, 0), orDefault(size, 20));
    }

    @MutationMapping
    public Product createProduct(@Argument ProductInput input) {
        return catalogService.create(input);
    }

    @MutationMapping
    public Product updateProduct(@Argument String id, @Argument ProductInput input) {
        return catalogService.update(UUID.fromString(id), input);
    }

    @MutationMapping
    public boolean deleteProduct(@Argument String id) {
        catalogService.delete(UUID.fromString(id));
        return true;
    }

    private static int orDefault(Integer value, int fallback) {
        return value != null ? value : fallback;
    }
}
