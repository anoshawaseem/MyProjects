import axios from "axios";

const emailApiClient = axios.create({
    baseURL: import.meta.env.VITE_EMAIL_API_BASE_URL,
});

emailApiClient.interceptors.request.use((config) => {
    const token = localStorage.getItem("accessToken");
    if (token) {
        config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
});

export default emailApiClient;