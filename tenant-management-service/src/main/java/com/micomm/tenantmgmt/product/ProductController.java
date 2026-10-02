package com.micomm.tenantmgmt.product;

import com.micomm.common.dto.ApiResponse;
import com.micomm.common.security.MicommAuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductRepository productRepository;

    public ProductController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @GetMapping
    public ApiResponse<List<Product>> listActive() {
        return ApiResponse.ok(productRepository.findByStatus("ACTIVE"));
    }

    @GetMapping("/all")
    public ApiResponse<List<Product>> listAll(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser) {

        if (!currentUser.isOrgAdmin()) {
            throw new SecurityException("Only ORG_ADMIN can view all products");
        }
        return ApiResponse.ok(productRepository.findAll());
    }

    @PostMapping
    public ApiResponse<Product> create(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser,
            @RequestBody CreateProductRequest request) {

        if (!currentUser.isOrgAdmin()) {
            throw new SecurityException("Only ORG_ADMIN can create products");
        }

        Product product = new Product();
        product.setCode(request.code());
        product.setName(request.name());
        product.setDescription(request.description());
        product.setStatus("ACTIVE");

        return ApiResponse.ok(productRepository.save(product));
    }

    @PutMapping("/{id}")
    public ApiResponse<Product> update(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser,
            @PathVariable UUID id,
            @RequestBody UpdateProductRequest request) {

        if (!currentUser.isOrgAdmin()) {
            throw new SecurityException("Only ORG_ADMIN can update products");
        }

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + id));

        if (request.name() != null) product.setName(request.name());
        if (request.description() != null) product.setDescription(request.description());
        if (request.status() != null) product.setStatus(request.status());

        return ApiResponse.ok(productRepository.save(product));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal MicommAuthenticatedUser currentUser,
            @PathVariable UUID id) {

        if (!currentUser.isOrgAdmin()) {
            throw new SecurityException("Only ORG_ADMIN can delete products");
        }

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + id));
        product.setStatus("INACTIVE");
        productRepository.save(product);

        return ApiResponse.ok(null);
    }

    public record CreateProductRequest(String code, String name, String description) {}
    public record UpdateProductRequest(String name, String description, String status) {}
}