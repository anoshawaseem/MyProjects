import emailApiClient from "./emailApiClient";

// `module` (e.g. "MEMBERSHIP") limits a call to that product module's templates.
// Leave it undefined for clubs without modules.

export async function listTemplates(clubId, productId, module) {
    const response = await emailApiClient.get("/emails/templates", {
        params: { clubId, productId, module },
    });
    if (!response.data.success) {
        throw new Error(response.data.errorMessage ?? "Failed to load templates");
    }
    return response.data.data;
}

export async function getTemplate(clubId, productId, templateId, module) {
    const response = await emailApiClient.get(`/emails/templates/${templateId}`, {
        params: { clubId, productId, module },
    });
    if (!response.data.success) {
        throw new Error(response.data.errorMessage ?? "Failed to load template");
    }
    return response.data.data;
}

/** payload: { clubId, productId, moduleCode?, templateKey?, name, subject, body } */
export async function createTemplate(payload) {
    const response = await emailApiClient.post("/emails/templates", payload);
    if (!response.data.success) {
        throw new Error(response.data.errorMessage ?? "Failed to create template");
    }
    return response.data.data;
}

export async function updateTemplate(clubId, productId, templateId, payload, module) {
    const response = await emailApiClient.put(`/emails/templates/${templateId}`, payload, {
        params: { clubId, productId, module },
    });
    if (!response.data.success) {
        throw new Error(response.data.errorMessage ?? "Failed to update template");
    }
    return response.data.data;
}

export async function deleteTemplate(clubId, productId, templateId, module) {
    const response = await emailApiClient.delete(`/emails/templates/${templateId}`, {
        params: { clubId, productId, module },
    });
    if (!response.data.success) {
        throw new Error(response.data.errorMessage ?? "Failed to delete template");
    }
}

// Global templates belong to the club, not to a module or product: every product of the club
// sees the same ones. productId is any of the club's products (it only locates the club's data).

export async function listGlobalTemplates(clubId, productId) {
    const response = await emailApiClient.get("/emails/global-templates", { params: { clubId, productId } });
    if (!response.data.success) {
        throw new Error(response.data.errorMessage ?? "Failed to load global templates");
    }
    return response.data.data;
}

/** payload: { clubId, productId, name, subject, body } */
export async function createGlobalTemplate(payload) {
    const response = await emailApiClient.post("/emails/global-templates", payload);
    if (!response.data.success) {
        throw new Error(response.data.errorMessage ?? "Failed to create global template");
    }
    return response.data.data;
}

export async function updateGlobalTemplate(clubId, productId, templateId, payload) {
    const response = await emailApiClient.put(`/emails/global-templates/${templateId}`, payload, {
        params: { clubId, productId },
    });
    if (!response.data.success) {
        throw new Error(response.data.errorMessage ?? "Failed to update global template");
    }
    return response.data.data;
}

export async function deleteGlobalTemplate(clubId, productId, templateId) {
    const response = await emailApiClient.delete(`/emails/global-templates/${templateId}`, {
        params: { clubId, productId },
    });
    if (!response.data.success) {
        throw new Error(response.data.errorMessage ?? "Failed to delete global template");
    }
}
