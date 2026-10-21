import apiClient from "./apiClient";

function unwrap(response, fallback) {
    if (!response.data.success) {
        throw new Error(response.data.errorMessage ?? fallback);
    }
    return response.data.data;
}

/** The product's module catalogue (MEMBERSHIP, EVENT, ...). */
export async function listProductModules(productId) {
    const response = await apiClient.get(`/products/${productId}/modules`);
    return unwrap(response, "Failed to load modules");
}

/** The templates a module needs, with their placeholders and default content per channel. */
export async function listTemplateTypes(productId, moduleCode) {
    const response = await apiClient.get(`/products/${productId}/modules/${moduleCode}/template-types`);
    return unwrap(response, "Failed to load module template types");
}

/** { supportsModules, modules: [{ code, name, description, enabled }] } for one club + product. */
export async function getClubModules(clubId, productId) {
    const response = await apiClient.get(`/clubs/${clubId}/products/${productId}/modules`);
    return unwrap(response, "Failed to load club modules");
}

/** Replaces the club's enabled modules (ORG_ADMIN only). Newly enabled modules get their default templates. */
export async function setClubModules(clubId, productId, moduleCodes) {
    const response = await apiClient.put(`/clubs/${clubId}/products/${productId}/modules`, { moduleCodes });
    return unwrap(response, "Failed to update club modules");
}
