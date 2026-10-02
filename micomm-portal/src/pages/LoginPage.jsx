import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

export default function LoginPage() {
    const [email, setEmail] = useState("");
    const [password, setPassword] = useState("");
    const [error, setError] = useState(null);
    const [loading, setLoading] = useState(false);

    const { login } = useAuth();
    const navigate = useNavigate();

    async function handleSubmit(e) {
        e.preventDefault();
        setError(null);
        setLoading(true);

        try {
            await login({ email, password });
            navigate("/dashboard");
        } catch (err) {
            setError(err instanceof Error ? err.message : "Login failed");
        } finally {
            setLoading(false);
        }
    }

    return (
        <div style={styles.page}>
            <div style={styles.backgroundGradient} />

            <div style={styles.card}>
                <div style={styles.logoWrap}>
                    <div style={styles.logoCircle}>M</div>
                    <h1 style={styles.brandName}>MiComm</h1>
                </div>

                <p style={styles.subtitle}>Sign in to your admin portal</p>

                <form onSubmit={handleSubmit} style={styles.form}>
                    <div style={styles.fieldGroup}>
                        <label style={styles.label}>Email</label>
                        <input
                            type="email"
                            value={email}
                            onChange={(e) => setEmail(e.target.value)}
                            required
                            placeholder="you@company.com"
                            style={styles.input}
                            onFocus={(e) => (e.target.style.borderColor = "#4f46e5")}
                            onBlur={(e) => (e.target.style.borderColor = "#e5e7eb")}
                        />
                    </div>

                    <div style={styles.fieldGroup}>
                        <label style={styles.label}>Password</label>
                        <input
                            type="password"
                            value={password}
                            onChange={(e) => setPassword(e.target.value)}
                            required
                            placeholder="••••••••"
                            style={styles.input}
                            onFocus={(e) => (e.target.style.borderColor = "#4f46e5")}
                            onBlur={(e) => (e.target.style.borderColor = "#e5e7eb")}
                        />
                    </div>

                    {error && (
                        <div style={styles.errorBox}>
                            <span style={styles.errorIcon}>⚠</span> {error}
                        </div>
                    )}

                    <button
                        type="submit"
                        disabled={loading}
                        style={{
                            ...styles.button,
                            opacity: loading ? 0.7 : 1,
                            cursor: loading ? "not-allowed" : "pointer",
                        }}
                    >
                        {loading ? "Signing in..." : "Sign In"}
                    </button>
                </form>
            </div>
        </div>
    );
}

const styles = {
    page: {
        position: "relative",
        display: "flex",
        justifyContent: "center",
        alignItems: "center",
        height: "100vh",
        overflow: "hidden",
    },
    backgroundGradient: {
        position: "absolute",
        inset: 0,
        background:
            "linear-gradient(135deg, #4f46e5 0%, #7c3aed 50%, #db2777 100%)",
        zIndex: 0,
    },
    card: {
        position: "relative",
        zIndex: 1,
        width: "380px",
        padding: "40px 36px",
        backgroundColor: "#ffffff",
        borderRadius: "20px",
        boxShadow: "0 20px 60px rgba(0,0,0,0.3)",
    },
    logoWrap: {
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        gap: "10px",
        marginBottom: "8px",
    },
    logoCircle: {
        width: "40px",
        height: "40px",
        borderRadius: "10px",
        background:
            "linear-gradient(135deg, #4f46e5, #7c3aed)",
        color: "#fff",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        fontWeight: 700,
        fontSize: "18px",
    },
    brandName: {
        fontSize: "22px",
        fontWeight: 700,
        color: "#1f2937",
    },
    subtitle: {
        textAlign: "center",
        color: "#6b7280",
        fontSize: "14px",
        marginBottom: "28px",
    },
    form: {
        display: "flex",
        flexDirection: "column",
        gap: "18px",
    },
    fieldGroup: {
        display: "flex",
        flexDirection: "column",
        gap: "6px",
    },
    label: {
        fontSize: "13px",
        fontWeight: 600,
        color: "#374151",
    },
    input: {
        padding: "11px 14px",
        fontSize: "14px",
        border: "1.5px solid #e5e7eb",
        borderRadius: "10px",
        outline: "none",
        transition: "border-color 0.15s ease",
    },
    button: {
        marginTop: "6px",
        padding: "12px",
        fontSize: "15px",
        fontWeight: 600,
        background:
            "linear-gradient(135deg, #4f46e5, #7c3aed)",
        color: "#fff",
        border: "none",
        borderRadius: "10px",
        transition:
            "transform 0.1s ease, box-shadow 0.15s ease",
        boxShadow: "0 4px 14px rgba(79, 70, 229, 0.4)",
    },
    errorBox: {
        display: "flex",
        alignItems: "center",
        gap: "8px",
        padding: "10px 14px",
        backgroundColor: "#fee2e2",
        color: "#dc2626",
        borderRadius: "8px",
        fontSize: "13px",
    },
    errorIcon: {
        fontSize: "14px",
    },
};