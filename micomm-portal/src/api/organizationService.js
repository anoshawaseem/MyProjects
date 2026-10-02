import apiClient from "./apiClient";

export async function createOrganization(payload) {
    const response = await apiClient.post("/clubs", payload);
    return response.data.data ?? response.data;
}

export async function getOrganization(id) {
    const response = await apiClient.get(`/clubs/${id}`);
    return response.data.data;
}

export async function listClubs() {
    const response = await apiClient.get(`/clubs`);
    return response.data.data;
}

export async function triggerOnboarding(clubId) {
    const response = await apiClient.post(`/clubs/${clubId}/onboarding`);
    return response.data.data;
}