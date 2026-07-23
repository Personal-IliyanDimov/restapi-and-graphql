package com.zuhlke.catalog.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the GraphQL contract in graphql/schema.graphqls: exercises the same
 * create / read / update / delete / list / search flow as {@link RestApiE2ETest}, but over
 * real HTTP GraphQL requests (random port, full Spring context) so it proves the GraphQL
 * transport, not just the resolver methods in isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
class GraphQlE2ETest {

    @Autowired
    private HttpGraphQlTester graphQlTester;

    @Test
    void createReadUpdateDeleteAndSearch() {
        String createdId = graphQlTester.document("""
                        mutation {
                          createProduct(input: {
                            name: "Mechanical Keyboard"
                            description: "Hot-swappable mechanical keyboard"
                            price: 89.5
                            quantity: 40
                            category: "Accessories"
                          }) {
                            id
                            name
                            price
                          }
                        }
                        """)
                .execute()
                .path("createProduct.name").entity(String.class).isEqualTo("Mechanical Keyboard")
                .path("createProduct.id").entity(String.class).get();

        graphQlTester.document("""
                        query($id: ID!) {
                          product(id: $id) {
                            name
                            category
                          }
                        }
                        """)
                .variable("id", createdId)
                .execute()
                .path("product.name").entity(String.class).isEqualTo("Mechanical Keyboard");

        graphQlTester.document("""
                        mutation($id: ID!) {
                          updateProduct(id: $id, input: {
                            name: "Mechanical Keyboard v2"
                            price: 99.0
                            quantity: 35
                            category: "Accessories"
                          }) {
                            name
                            price
                          }
                        }
                        """)
                .variable("id", createdId)
                .execute()
                .path("updateProduct.name").entity(String.class).isEqualTo("Mechanical Keyboard v2");

        graphQlTester.document("""
                        query {
                          searchProducts(name: "keyboard", category: "Accessories") {
                            totalElements
                            content { name }
                          }
                        }
                        """)
                .execute()
                .path("searchProducts.content[0].name").entity(String.class).isEqualTo("Mechanical Keyboard v2");

        Boolean deleted = graphQlTester.document("""
                        mutation($id: ID!) {
                          deleteProduct(id: $id)
                        }
                        """)
                .variable("id", createdId)
                .execute()
                .path("deleteProduct").entity(Boolean.class).get();
        assertThat(deleted).isTrue();

        graphQlTester.document("""
                        query($id: ID!) {
                          product(id: $id) { name }
                        }
                        """)
                .variable("id", createdId)
                .execute()
                .path("product").valueIsNull();
    }

    @Test
    void deletingUnknownIdReturnsGraphQlError() {
        graphQlTester.document("""
                        mutation {
                          deleteProduct(id: "00000000-0000-0000-0000-000000000000")
                        }
                        """)
                .execute()
                .errors()
                .expect(error -> error.getMessage() != null && error.getMessage().contains("00000000-0000-0000-0000-000000000000"));
    }
}
