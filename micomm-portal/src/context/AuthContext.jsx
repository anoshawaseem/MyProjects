import { createContext, useContext, useState } from "react";
import * as authService from "../api/authService";

const AuthContext = createContext(undefined);

export function AuthProvider({ children }) {
    const [isAuthenticated, setIsAuthenticated] = useState(authService.isAuthenticated());
    const [role, setRole] = useState(authService.getCurrentRole());

    async function login(credentials) {
        const result = await authService.login(credentials);
        setIsAuthenticated(true);
        setRole(result.role);
    }

    function logout() {
        authService.logout();
        setIsAuthenticated(false);
        setRole(null);
    }

    return (
        <AuthContext.Provider value={{ isAuthenticated, role, login, logout }}>
            {children}
        </AuthContext.Provider>
    );
}

export function useAuth() {
    const context = useContext(AuthContext);
    if (!context) {
        throw new Error("useAuth must be used within AuthProvider");
    }
    return context;
}