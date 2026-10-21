import apiClient from "./apiClient";

export async function listProviderTypes() {
    const res = await apiClient.get("/provider-types");
    return res.data.data;
}

export async function listProviderAccounts(clubId) {
    const res = await apiClient.get(`/clubs/${clubId}/provider-accounts`);
    return res.data.data;
}

export async function createProviderAccount(clubId, providerTypeId, name, secretReference, configJson) {
    const res = await apiClient.post(`/clubs/${clubId}/provider-accounts`, {
        providerTypeId, name, secretReference, configJson,
    });
    return res.data.data;
}

export async function deleteProviderAccount(clubId, id) {
    await apiClient.delete(`/clubs/${clubId}/provider-accounts/${id}`);
}

export async function getClubProductProviders(clubId, productId) {
    const res = await apiClient.get(`/clubs/${clubId}/products/${productId}/providers`);
    return res.data.data;
}

/** Modules that send through their own account: [{ moduleCode, channel, providerAccountId }]. */
export async function getModuleProviders(clubId, productId) {
    const res = await apiClient.get(`/clubs/${clubId}/products/${productId}/module-providers`);
    return res.data.data;
}

export async function assignModuleProvider(clubId, productId, moduleCode, channel, providerAccountId) {
    const res = await apiClient.put(`/clubs/${clubId}/products/${productId}/module-providers`, {
        moduleCode, channel, providerAccountId,
    });
    return res.data.data;
}

/** The module goes back to the product's provider for this channel. */
export async function clearModuleProvider(clubId, productId, moduleCode, channel) {
    const res = await apiClient.delete(`/clubs/${clubId}/products/${productId}/module-providers`, {
        params: { moduleCode, channel },
    });
    return res.data.data;
}

export async function assignClubProductProvider(clubId, productId, channel, providerAccountId) {
    const res = await apiClient.put(`/clubs/${clubId}/products/${productId}/providers`, {
        channel, providerAccountId,
    });
    return res.data.data;
}