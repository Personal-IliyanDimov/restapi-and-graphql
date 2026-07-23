package com.zuhlke.catalog.service.domain;

import java.util.UUID;

/**
 * Thrown when a product id does not exist. Translated to a 404 + shared ErrorResponse
 * body by {@link com.zuhlke.catalog.service.rest.RestExceptionHandler} on the REST side,
 * and to a NOT_FOUND GraphQL error by
 * {@link com.zuhlke.catalog.service.graphql.GraphQlExceptionResolver} on the GraphQL side.
 */
public class ProductNotFoundException extends RuntimeException {

    private final UUID productId;

    public ProductNotFoundException(UUID productId) {
        super("No product exists with id " + productId);
        this.productId = productId;
    }

    public UUID getProductId() {
        return productId;
    }
}
