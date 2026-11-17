import { useEffect, useState } from "react";
import { useParams, Link } from "react-router-dom";
import * as clubService from "../api/organizationService";
import { listOAuthClients, registerOAuthClient, revokeOAuthClient, TOKEN_URL } from "../api/oauthClientService";
import ConfirmDialog from "../components/ConfirmDialog";
import { AlertTriangle, AppWindow, ArrowLeft, CheckCircle2, Home, Loader2, Plus, Trash2 } from "lucide-react";

const SCOPES = [
    { code: "email:send", label: "Send email" },
    { code: "sms:send", label: "Send SMS" },
    { code: "whatsapp:send", label: "Send WhatsApp" },
    { code: "email:read", label: "Read send history" },
    { code: "templates:read", label: "Read templates" },
    { code: "templates:write", label: "Create and edit templates" },
];

function errorText(err, fallback) {
    return err.response?.data?.errorMessage ?? err.message ?? fallback;
}

function formatDate(value) {
    return value ? new Date(value).toLocaleString() : null;
}

function tomorrow() {
    const d = new Date();
    d.setDate(d.getDate() + 1);
    return d.toISOString().slice(0, 10);
}

function status(client) {
    if (client.revokedAt) return "Revoked";
    if (client.expiresAt && new Date(client.expiresAt) <= new Date()) return "Expired";
    return "Active";
}

const STATUS_STYLES = {
    Active: "bg-emerald-50 text-emerald-700",
    Expired: "bg-amber-50 text-amber-700",
    Revoked: "bg-gray-100 text-gray-500",
};

/**
 * OAuth applications: they sign in with their own private key (private key JWT) and get
 * short-lived tokens. MiComm only stores each application's public key.
 */
export default function ClubAppsPage() {
    const { clubId } = useParams();
    const [club, setClub] = useState(null);
    const [clients, setClients] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const [message, setMessage] = useState(null);

    const [name, setName] = useState("");
    const [publicKeyPem, setPublicKeyPem] = useState("");
    const [scopes, setScopes] = useState(["email:send", "email:read"]);
    const [expiryDate, setExpiryDate] = useState("");
    const [saving, setSaving] = useState(false);
    const [pendingRevoke, setPendingRevoke] = useState(null);

    useEffect(() => {
        let cancelled = false;
        Promise.all([clubService.getOrganization(clubId), listOAuthClients(clubId)])
            .then(([clubData, list]) => {
                if (cancelled) return;
                setClub(clubData);
                setClients(list);
            })
            .catch((err) => !cancelled && setError(errorText(err, "Failed to load applications")))
            .finally(() => !cancelled && setLoading(false));
        return () => {
            cancelled = true;
        };
    }, [clubId]);

    function toggleScope(code) {
        setScopes((prev) => (prev.includes(code) ? prev.filter((s) => s !== code) : [...prev, code]));
    }

    async function handleRegister(e) {
        e.preventDefault();
        setSaving(true);
        setError(null);
        setMessage(null);
        try {
            const created = await registerOAuthClient(clubId, {
                name: name.trim(),
                publicKeyPem: publicKeyPem.trim(),
                scopes,
                expiresAt: expiryDate ? new Date(`${expiryDate}T23:59:59`).toISOString() : undefined,
            });
            setMessage(`Registered "${created.name}". Its client ID is ${created.clientId}.`);
            setName("");
            setPublicKeyPem("");
            setExpiryDate("");
            setClients(await listOAuthClients(clubId));
        } catch (err) {
            setError(errorText(err, "Failed to register application"));
        } finally {
            setSaving(false);
        }
    }

    async function handleRevoke() {
        const client = pendingRevoke;
        setPendingRevoke(null);
        setError(null);
        setMessage(null);
        try {
            await revokeOAuthClient(clubId, client.id);
            setMessage(`Revoked "${client.name}". It can't get new tokens; any current token expires within 15 minutes.`);
            setClients(await listOAuthClients(clubId));
        } catch (err) {
            setError(errorText(err, "Failed to revoke application"));
        }
    }

    return (
        <div className="min-h-screen bg-gray-50">
            <header className="flex items-center gap-3 px-4 sm:px-8 py-4 bg-white border-b border-gray-200 shadow-sm">
                <Link to="/dashboard" className="text-gray-500 hover:text-gray-800" title="Home">
                    <Home className="w-5 h-5" />
                </Link>
                <Link to="/dashboard" className="text-gray-500 hover:text-gray-800" title="Back">
                    <ArrowLeft className="w-5 h-5" />
                </Link>
                <h1 className="text-lg font-bold text-gray-800 truncate">Apps{club ? ` — ${club.name}` : ""}</h1>
            </header>

            <main className="max-w-4xl mx-auto px-4 sm:px-6 py-8 flex flex-col gap-5">
                {error && (
                    <div className="flex items-center gap-2 px-4 py-3 bg-red-50 text-red-600 rounded-xl text-sm">
                        <AlertTriangle className="w-4 h-4 shrink-0" /> {error}
                    </div>
                )}
                {message && (
                    <div className="flex items-center gap-2 px-4 py-3 bg-emerald-50 text-emerald-600 rounded-xl text-sm">
                        <CheckCircle2 className="w-4 h-4 shrink-0" /> {message}
                    </div>
                )}

                <p className="text-sm text-gray-600">
                    Apps sign in with their own private key and get a token that lasts 15 minutes. MiComm only keeps
                    the app's public key, so nothing stored here can be used to act as the app. Give each app only the
                    permissions it needs.
                </p>

                {loading ? (
                    <div className="flex items-center gap-2 text-sm text-gray-500 py-4">
                        <Loader2 className="w-4 h-4 animate-spin" /> Loading...
                    </div>
                ) : (
                    <>
                        <section className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm">
                            <div className="flex items-center gap-2.5 mb-5">
                                <Plus className="w-5 h-5 text-indigo-600" />
                                <h2 className="text-base font-bold text-gray-800">Register an app</h2>
                            </div>
                            <form onSubmit={handleRegister} className="flex flex-col gap-4">
                                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                                    <div className="flex flex-col gap-1.5">
                                        <label htmlFor="app-name" className="text-sm font-semibold text-gray-700">Name</label>
                                        <input id="app-name" value={name} onChange={(e) => setName(e.target.value)} required
                                            maxLength={100} placeholder="e.g. MiClub backend" className="input" />
                                    </div>
                                    <div className="flex flex-col gap-1.5">
                                        <label htmlFor="app-expiry" className="text-sm font-semibold text-gray-700">
                                            Expires on <span className="font-normal text-gray-400">(optional)</span>
                                        </label>
                                        <input id="app-expiry" type="date" value={expiryDate} min={tomorrow()}
                                            onChange={(e) => setExpiryDate(e.target.value)} className="input" />
                                    </div>
                                </div>
                                <div className="flex flex-col gap-1.5">
                                    <label htmlFor="app-key" className="text-sm font-semibold text-gray-700">Public key</label>
                                    <textarea id="app-key" value={publicKeyPem} onChange={(e) => setPublicKeyPem(e.target.value)}
                                        required rows={6} spellCheck={false} className="input font-mono text-xs"
                                        placeholder={"-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----"} />
                                    <p className="text-xs text-gray-500">
                                        RSA (2048 bits or more) or EC P-256. Paste the public key only, never the private key.
                                    </p>
                                </div>
                                <fieldset className="flex flex-col gap-2">
                                    <legend className="text-sm font-semibold text-gray-700 mb-1">Permissions</legend>
                                    {SCOPES.map((s) => (
                                        <label key={s.code} className="flex items-center gap-2 text-sm text-gray-700">
                                            <input type="checkbox" checked={scopes.includes(s.code)} onChange={() => toggleScope(s.code)} />
                                            {s.label} <span className="font-mono text-xs text-gray-400">{s.code}</span>
                                        </label>
                                    ))}
                                </fieldset>
                                <button type="submit" disabled={saving || !name.trim() || !publicKeyPem.trim() || scopes.length === 0}
                                    className="btn-primary self-start">
                                    {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <AppWindow className="w-4 h-4" />}
                                    {saving ? "Registering..." : "Register app"}
                                </button>
                            </form>
                        </section>

                        <section className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm">
                            <div className="flex items-center gap-2.5 mb-5">
                                <AppWindow className="w-5 h-5 text-indigo-600" />
                                <h2 className="text-base font-bold text-gray-800">Apps</h2>
                            </div>
                            {clients.length === 0 ? (
                                <p className="text-sm text-gray-500">No apps registered yet.</p>
                            ) : (
                                <div className="overflow-x-auto">
                                    <table className="w-full border-collapse text-sm">
                                        <thead>
                                            <tr className="text-left text-xs uppercase tracking-wide text-gray-500 border-b border-gray-100">
                                                <th className="py-2 pr-4 font-semibold">App</th>
                                                <th className="py-2 pr-4 font-semibold">Client ID</th>
                                                <th className="py-2 pr-4 font-semibold">Permissions</th>
                                                <th className="py-2 pr-4 font-semibold">Status</th>
                                                <th className="py-2 pr-4 font-semibold">Last used</th>
                                                <th className="py-2 font-semibold"><span className="sr-only">Actions</span></th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            {clients.map((c) => {
                                                const st = status(c);
                                                return (
                                                    <tr key={c.id} className="border-b border-gray-50 last:border-0 align-top">
                                                        <td className="py-3 pr-4">
                                                            <p className="font-semibold text-gray-800">{c.name}</p>
                                                            <p className="text-xs text-gray-400">{c.keyAlgorithm} · created {formatDate(c.createdAt)}</p>
                                                        </td>
                                                        <td className="py-3 pr-4 font-mono text-xs text-gray-700 whitespace-nowrap select-all">{c.clientId}</td>
                                                        <td className="py-3 pr-4 font-mono text-xs text-gray-600">{c.scopes.join(" ")}</td>
                                                        <td className="py-3 pr-4">
                                                            <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${STATUS_STYLES[st]}`}>{st}</span>
                                                        </td>
                                                        <td className="py-3 pr-4 text-gray-600 whitespace-nowrap">{formatDate(c.lastUsedAt) ?? "Never"}</td>
                                                        <td className="py-3 text-right">
                                                            {st !== "Revoked" && (
                                                                <button onClick={() => setPendingRevoke(c)}
                                                                    className="inline-flex items-center gap-1 px-2.5 py-1.5 text-xs font-semibold text-red-600 rounded-lg hover:bg-red-50">
                                                                    <Trash2 className="w-3.5 h-3.5" /> Revoke
                                                                </button>
                                                            )}
                                                        </td>
                                                    </tr>
                                                );
                                            })}
                                        </tbody>
                                    </table>
                                </div>
                            )}
                        </section>

                        <section className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm text-sm text-gray-700">
                            <h2 className="text-base font-bold text-gray-800 mb-3">Connecting an app</h2>
                            <ol className="list-decimal pl-5 flex flex-col gap-2">
                                <li>
                                    On the app's server, create a key pair and keep the private key there:
                                    <pre className="mt-1 p-3 bg-gray-50 rounded-lg text-xs overflow-x-auto">{`openssl ecparam -name prime256v1 -genkey -noout -out private.pem
openssl ec -in private.pem -pubout -out public.pem`}</pre>
                                    Register the contents of <code>public.pem</code> above.
                                </li>
                                <li>
                                    To get a token, sign a JWT with the private key (ES256 for EC keys, RS256 for RSA) with
                                    {" "}<code>iss</code> and <code>sub</code> = the client ID, <code>aud</code> = <code className="break-all">{TOKEN_URL}</code>,
                                    a unique <code>jti</code>, and an expiry of at most 5 minutes.
                                </li>
                                <li>
                                    POST it to <code className="break-all">{TOKEN_URL}</code> as a form with
                                    {" "}<code>grant_type=client_credentials</code>,
                                    {" "}<code>client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer</code> and
                                    {" "}<code>client_assertion=&lt;the JWT&gt;</code>.
                                </li>
                                <li>
                                    Call the email API with <code>Authorization: Bearer &lt;access_token&gt;</code>. Get a new token
                                    when it expires (every 15 minutes).
                                </li>
                            </ol>
                        </section>
                    </>
                )}
            </main>

            <ConfirmDialog
                open={pendingRevoke !== null}
                danger
                title="Revoke this app?"
                message={`"${pendingRevoke?.name}" won't be able to get new tokens. A token it already has keeps working until it expires (at most 15 minutes). This can't be undone.`}
                confirmLabel="Revoke app"
                onConfirm={handleRevoke}
                onCancel={() => setPendingRevoke(null)}
            />
        </div>
    );
}
