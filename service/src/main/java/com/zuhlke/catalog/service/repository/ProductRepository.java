package com.zuhlke.catalog.service.repository;

import com.zuhlke.catalog.schema.model.Product;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store, deliberately holding the exact shared {@code Product} type generated
 * from schema/src/main/resources/openapi/schemas/Product.yaml - the same class both the
 * REST controller and the GraphQL data fetchers read and write. There is no separate
 * "entity" class in this demo; that's what keeps the two APIs honestly on one schema.
 */
@Repository
public class ProductRepository {

    private final Map<UUID, Product> store = new ConcurrentHashMap<>();

    public Product save(Product product) {
        OffsetDateTime now = OffsetDateTime.now();
        if (product.getId() == null) {
            product.setId(UUID.randomUUID());
            product.setCreatedAt(now);
        }
        product.setUpdatedAt(now);
        store.put(product.getId(), product);
        return product;
    }

    public Optional<Product> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    public boolean deleteById(UUID id) {
        return store.remove(id) != null;
    }

    public List<Product> findAll() {
        return store.values().stream()
                .sorted(Comparator.comparing(Product::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }
}
