import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import { AuthProvider } from "./context/AuthContext";
import ProtectedRoute from "./components/ProtectedRoute";
import LoginPage from "./pages/LoginPage";
import DashboardPage from "./pages/DashboardPage";
import ProductsPage from "./pages/ProductsPage";
import ClubProductsPage from "./pages/ClubProductsPage";
import ClubProductProvidersPage from "./pages/ClubProductProvidersPage";
import ClubApiKeysPage from "./pages/ClubApiKeysPage";
import ClubAuditLogPage from "./pages/ClubAuditLogPage";
import SendTestEmailPage from "./pages/SendTestEmailPage";
import EmailTemplatesPage from "./pages/EmailTemplatesPage";

export default function App() {
    return (
        <AuthProvider>
            <BrowserRouter>
                <Routes>
                    <Route path="/login" element={<LoginPage />} />
                    <Route
                        path="/dashboard"
                        element={
                            <ProtectedRoute>
                                <DashboardPage />
                            </ProtectedRoute>
                        }
                    />
                    <Route
                        path="/products"
                        element={
                            <ProtectedRoute>
                                <ProductsPage />
                            </ProtectedRoute>
                        }
                    />
                    <Route
                        path="/clubs/:clubId/products"
                        element={
                            <ProtectedRoute>
                                <ClubProductsPage />
                            </ProtectedRoute>
                        }
                    />
                    <Route
                        path="/clubs/:clubId/providers"
                        element={
                            <ProtectedRoute>
                                <ClubProductProvidersPage />
                            </ProtectedRoute>
                        }
                    />
                    <Route
                        path="/clubs/:clubId/audit-log"
                        element={
                            <ProtectedRoute>
                                <ClubAuditLogPage />
                            </ProtectedRoute>
                        }
                    />
                    <Route
                        path="/clubs/:clubId/api-keys"
                        element={
                            <ProtectedRoute>
                                <ClubApiKeysPage />
                            </ProtectedRoute>
                        }
                    />
                    <Route
                        path="/clubs/:clubId/products/:productId/test-email"
                        element={
                            <ProtectedRoute>
                                <SendTestEmailPage />
                            </ProtectedRoute>
                        }
                    />
                    <Route
                        path="/clubs/:clubId/products/:productId/email-templates"
                        element={
                            <ProtectedRoute>
                                <EmailTemplatesPage />
                            </ProtectedRoute>
                        }
                    />
                    <Route path="*" element={<Navigate to="/dashboard" replace />} />
                </Routes>
            </BrowserRouter>
        </AuthProvider>
    );
}