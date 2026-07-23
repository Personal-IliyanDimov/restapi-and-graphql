package com.zuhlke.catalog.service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the actual openapi.yaml contract (and the JSON Schema fragments it $refs) as
 * static files, straight from the `schema` module's classpath resources - so the file
 * you edit is the file clients fetch, with no separate "publish the spec" step.
 * Reachable at GET /openapi/openapi.yaml, /openapi/schemas/Product.yaml, etc.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/openapi/**")
                .addResourceLocations("classpath:/openapi/");
    }
}
