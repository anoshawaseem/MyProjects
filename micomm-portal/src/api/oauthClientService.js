import apiClient from "./apiClient";

function unwrap(response, fallback) {
    if (!response.data.success) {
        throw new Error(response.data.errorMessage ?? fallback);
    }
    return response.data.data;
}

/** The club's OAuth applications, newest first. */
export async function listOAuthClients(clubId) {
    const response = await apiClient.get(`/clubs/${clubId}/oauth-clients`);
    return unwrap(response, "Failed to load applications");
}

/** payload: { name, publicKeyPem, scopes: [...], expiresAt? } */
export async function registerOAuthClient(clubId, payload) {
    const response = await apiClient.post(`/clubs/${clubId}/oauth-clients`, payload);
    return unwrap(response, "Failed to register application");
}

export async function revokeOAuthClient(clubId, id) {
    const response = await apiClient.delete(`/clubs/${clubId}/oauth-clients/${id}`);
    unwrap(response, "Failed to revoke application");
}

/** The URL applications request tokens from, and must use as "aud" in their assertions. */
export const TOKEN_URL = `${import.meta.env.VITE_API_BASE_URL}/oauth/token`;
