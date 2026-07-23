package com.zuhlke.catalog.service;

import com.zuhlke.catalog.schema.model.Product;
import com.zuhlke.catalog.schema.model.ProductInput;
import com.zuhlke.catalog.schema.model.ProductPage;
import com.zuhlke.catalog.service.domain.ProductNotFoundException;
import com.zuhlke.catalog.service.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Single business-logic layer used by both the REST controller
 * ({@link com.zuhlke.catalog.service.rest.ProductRestController}) and the GraphQL data
 * fetchers ({@link com.zuhlke.catalog.service.graphql.ProductGraphQlController}). Both
 * transports call the exact same methods, on the exact same shared model types, so any
 * behavioral difference between REST and GraphQL would be a bug here, not a divergence
 * between two parallel implementations.
 */
@Service
public class ProductCatalogService {

    private final ProductRepository repository;

    public ProductCatalogService(ProductRepository repository) {
        this.repository = repository;
    }

    public Product create(ProductInput input) {
        return repository.save(toProduct(new Product(), input));
    }

    public Product get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    }

    public Product update(UUID id, ProductInput input) {
        Product existing = get(id);
        return repository.save(toProduct(existing, input));
    }

    public void delete(UUID id) {
        if (!repository.deleteById(id)) {
            throw new ProductNotFoundException(id);
        }
    }

    public ProductPage list(int page, int size) {
        return paginate(repository.findAll(), page, size);
    }

    public ProductPage search(String name, String category, Double minPrice, Double maxPrice, int page, int size) {
        List<Product> filtered = repository.findAll().stream()
                .filter(p -> name == null || p.getName().toLowerCase().contains(name.toLowerCase()))
                .filter(p -> category == null || category.equalsIgnoreCase(p.getCategory()))
                .filter(p -> minPrice == null || p.getPrice() >= minPrice)
                .filter(p -> maxPrice == null || p.getPrice() <= maxPrice)
                .sorted(Comparator.comparing(Product::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        return paginate(filtered, page, size);
    }

    private ProductPage paginate(List<Product> all, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(size, 1);
        int fromIndex = Math.min(safePage * safeSize, all.size());
        int toIndex = Math.min(fromIndex + safeSize, all.size());

        ProductPage result = new ProductPage();
        result.setContent(all.subList(fromIndex, toIndex).stream().toList());
        result.setPage(safePage);
        result.setSize(safeSize);
        result.setTotalElements((long) all.size());
        result.setTotalPages((int) Math.ceil((double) all.size() / safeSize));
        return result;
    }

    private Product toProduct(Product target, ProductInput input) {
        target.setName(input.getName());
        target.setDescription(input.getDescription());
        target.setPrice(input.getPrice());
        target.setQuantity(input.getQuantity());
        target.setCategory(input.getCategory());
        return target;
    }
}
