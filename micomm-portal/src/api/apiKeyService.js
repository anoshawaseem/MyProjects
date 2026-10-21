import apiClient from "./apiClient";

function unwrap(response, fallback) {
    if (!response.data.success) {
        throw new Error(response.data.errorMessage ?? fallback);
    }
    return response.data.data;
}

/** The club's API keys, newest first. Never includes the secret part of a key. */
export async function listApiKeys(clubId) {
    const response = await apiClient.get(`/clubs/${clubId}/api-keys`);
    return unwrap(response, "Failed to load API keys");
}

/**
 * Creates a key. Returns { apiKey, key }: `key` is the full key, shown only this once.
 * expiresAt is an ISO date-time, or undefined for a key that never expires.
 */
export async function createApiKey(clubId, name, expiresAt) {
    const response = await apiClient.post(`/clubs/${clubId}/api-keys`, { name, expiresAt });
    return unwrap(response, "Failed to create API key");
}

/** Revokes a key; it stops working within a minute. */
export async function revokeApiKey(clubId, apiKeyId) {
    const response = await apiClient.delete(`/clubs/${clubId}/api-keys/${apiKeyId}`);
    unwrap(response, "Failed to revoke API key");
}
