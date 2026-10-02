import apiClient from "./apiClient";

export async function listProducts() {
    const response = await apiClient.get("/products");
    return response.data.data;
}

export async function listAllProducts() {
    const response = await apiClient.get("/products/all");
    return response.data.data;
}

export async function createProduct(code, name, description) {
    const response = await apiClient.post("/products", { code, name, description });
    return response.data.data;
}

export async function updateProduct(id, updates) {
    const response = await apiClient.put(`/products/${id}`, updates);
    return response.data.data;
}

export async function deleteProduct(id) {
    await apiClient.delete(`/products/${id}`);
}

export async function getClubProducts(clubId) {
    const response = await apiClient.get(`/clubs/${clubId}/products`);
    return response.data.data ?? response.data;
}

export async function setClubProducts(clubId, productIds) {
    const response = await apiClient.put(`/clubs/${clubId}/products`, { productIds });
    return response.data.data;
}