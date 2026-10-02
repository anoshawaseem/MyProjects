import apiClient from "./apiClient";

export async function login(credentials) {
    const response = await apiClient.post("/auth/login", credentials);

    if (!response.data.success || !response.data.data) {
        throw new Error(response.data.errorMessage ?? "Login failed");
    }

    const loginData = response.data.data;
    localStorage.setItem("accessToken", loginData.accessToken);
    localStorage.setItem("refreshToken", loginData.refreshToken);
    localStorage.setItem("role", loginData.role);
    if (loginData.organizationId) {
        localStorage.setItem("organizationId", loginData.organizationId);
    }

    return loginData;
}

export function logout() {
    localStorage.removeItem("accessToken");
    localStorage.removeItem("refreshToken");
    localStorage.removeItem("role");
    localStorage.removeItem("organizationId");
}

export function isAuthenticated() {
    return !!localStorage.getItem("accessToken");
}

export function getCurrentRole() {
    return localStorage.getItem("role");
}