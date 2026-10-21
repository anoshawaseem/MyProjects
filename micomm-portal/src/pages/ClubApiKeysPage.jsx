import { useEffect, useState } from "react";
import { useParams, Link } from "react-router-dom";
import * as clubService from "../api/organizationService";
import { listApiKeys, createApiKey, revokeApiKey } from "../api/apiKeyService";
import ConfirmDialog from "../components/ConfirmDialog";
import {
    AlertTriangle,
    ArrowLeft,
    CheckCircle2,
    Copy,
    Home,
    KeyRound,
    Loader2,
    Plus,
    ShieldAlert,
    Trash2,
} from "lucide-react";

function errorText(err, fallback) {
    return err.response?.data?.errorMessage ?? err.message ?? fallback;
}

function formatDate(value) {
    return value ? new Date(value).toLocaleString() : null;
}

/** Tomorrow as yyyy-mm-dd, the earliest expiry the date picker offers. */
function tomorrow() {
    const d = new Date();
    d.setDate(d.getDate() + 1);
    return d.toISOString().slice(0, 10);
}

function keyStatus(key) {
    if (key.revokedAt) return "REVOKED";
    if (key.expiresAt && new Date(key.expiresAt) <= new Date()) return "EXPIRED";
    return "ACTIVE";
}

const STATUS_STYLES = {
    ACTIVE: "bg-emerald-50 text-emerald-700",
    EXPIRED: "bg-amber-50 text-amber-700",
    REVOKED: "bg-gray-100 text-gray-500",
};

/**
 * API keys let another application (e.g. MiClub) call MiComm's email API for this club without
 * a user's email and password. The full key is shown once, right after it is created.
 */
export default function ClubApiKeysPage() {
    const { clubId } = useParams();
    const [club, setClub] = useState(null);
    const [keys, setKeys] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const [message, setMessage] = useState(null);

    const [name, setName] = useState("");
    const [expiryDate, setExpiryDate] = useState("");
    const [creating, setCreating] = useState(false);
    // { name, key } of the key just created; cleared when the user confirms they saved it.
    const [newKey, setNewKey] = useState(null);
    const [copied, setCopied] = useState(false);
    const [pendingRevoke, setPendingRevoke] = useState(null);

    useEffect(() => {
        let cancelled = false;
        Promise.all([clubService.getOrganization(clubId), listApiKeys(clubId)])
            .then(([clubData, keyList]) => {
                if (cancelled) return;
                setClub(clubData);
                setKeys(keyList);
            })
            .catch((err) => !cancelled && setError(errorText(err, "Failed to load API keys")))
            .finally(() => !cancelled && setLoading(false));
        return () => {
            cancelled = true;
        };
    }, [clubId]);

    async function reloadKeys() {
        setKeys(await listApiKeys(clubId));
    }

    async function handleCreate(e) {
        e.preventDefault();
        setCreating(true);
        setError(null);
        setMessage(null);
        try {
            // The key stops working at the end of the chosen day, in the user's time zone.
            const expiresAt = expiryDate ? new Date(`${expiryDate}T23:59:59`).toISOString() : undefined;
            const created = await createApiKey(clubId, name.trim(), expiresAt);
            setNewKey({ name: created.apiKey.name, key: created.key });
            setCopied(false);
            setName("");
            setExpiryDate("");
            await reloadKeys();
        } catch (err) {
            setError(errorText(err, "Failed to create API key"));
        } finally {
            setCreating(false);
        }
    }

    async function handleCopy() {
        try {
            await navigator.clipboard.writeText(newKey.key);
            setCopied(true);
        } catch {
            setError("Could not copy automatically. Select the key and copy it manually.");
        }
    }

    async function handleRevoke() {
        const key = pendingRevoke;
        setPendingRevoke(null);
        setError(null);
        setMessage(null);
        try {
            await revokeApiKey(clubId, key.id);
            setMessage(`Revoked "${key.name}". It stops working within a minute.`);
            await reloadKeys();
        } catch (err) {
            setError(errorText(err, "Failed to revoke API key"));
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
                <h1 className="text-lg font-bold text-gray-800 truncate">
                    API Keys{club ? ` — ${club.name}` : ""}
                </h1>
            </header>

            <main className="max-w-4xl mx-auto px-4 sm:px-6 py-8">
                {error && (
                    <div className="flex items-center gap-2 px-4 py-3 bg-red-50 text-red-600 rounded-xl text-sm mb-5">
                        <AlertTriangle className="w-4 h-4 shrink-0" /> {error}
                    </div>
                )}
                {message && (
                    <div className="flex items-center gap-2 px-4 py-3 bg-emerald-50 text-emerald-600 rounded-xl text-sm mb-5">
                        <CheckCircle2 className="w-4 h-4 shrink-0" /> {message}
                    </div>
                )}

                {loading ? (
                    <div className="flex items-center gap-2 text-sm text-gray-500 py-4">
                        <Loader2 className="w-4 h-4 animate-spin" /> Loading...
                    </div>
                ) : (
                    <div className="flex flex-col gap-5">
                        <p className="text-sm text-gray-600">
                            An API key lets another application, such as MiClub, send email and read email history for
                            this club through MiComm's API. Create a separate key for each application, so you can
                            revoke one without affecting the others.
                        </p>

                        {newKey && (
                            <section className="bg-amber-50 border border-amber-200 rounded-2xl p-6">
                                <div className="flex items-start gap-2.5 mb-3">
                                    <ShieldAlert className="w-5 h-5 text-amber-600 shrink-0 mt-0.5" />
                                    <div>
                                        <h2 className="text-base font-bold text-gray-800">
                                            Copy the key for "{newKey.name}" now
                                        </h2>
                                        <p className="text-sm text-gray-600">
                                            This is the only time it will be shown. Store it in the application's secret
                                            settings. If it's lost, create a new key and revoke this one.
                                        </p>
                                    </div>
                                </div>
                                <div className="flex flex-wrap items-center gap-2">
                                    <code className="flex-1 min-w-0 px-3 py-2 bg-white border border-amber-200 rounded-lg text-sm font-mono break-all select-all">
                                        {newKey.key}
                                    </code>
                                    <button type="button" onClick={handleCopy} className="btn-primary !py-2">
                                        {copied ? <CheckCircle2 className="w-4 h-4" /> : <Copy className="w-4 h-4" />}
                                        {copied ? "Copied" : "Copy key"}
                                    </button>
                                </div>
                                <button
                                    type="button"
                                    onClick={() => setNewKey(null)}
                                    className="mt-4 px-4 py-2 text-sm font-semibold text-gray-700 bg-white border border-gray-200 rounded-lg hover:bg-gray-50"
                                >
                                    I've saved the key
                                </button>
                            </section>
                        )}

                        <section className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm">
                            <div className="flex items-center gap-2.5 mb-5">
                                <Plus className="w-5 h-5 text-indigo-600" />
                                <h2 className="text-base font-bold text-gray-800">Create API key</h2>
                            </div>
                            <form onSubmit={handleCreate} className="flex flex-col gap-4">
                                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                                    <div className="flex flex-col gap-1.5">
                                        <label htmlFor="api-key-name" className="text-sm font-semibold text-gray-700">
                                            Name
                                        </label>
                                        <input
                                            id="api-key-name"
                                            value={name}
                                            onChange={(e) => setName(e.target.value)}
                                            required
                                            maxLength={100}
                                            placeholder="e.g. MiClub production"
                                            className="input"
                                        />
                                    </div>
                                    <div className="flex flex-col gap-1.5">
                                        <label htmlFor="api-key-expiry" className="text-sm font-semibold text-gray-700">
                                            Expires on <span className="font-normal text-gray-400">(optional)</span>
                                        </label>
                                        <input
                                            id="api-key-expiry"
                                            type="date"
                                            value={expiryDate}
                                            min={tomorrow()}
                                            onChange={(e) => setExpiryDate(e.target.value)}
                                            className="input"
                                        />
                                    </div>
                                </div>
                                <p className="text-xs text-gray-500">
                                    Without an expiry date, the key works until you revoke it.
                                </p>
                                <button
                                    type="submit"
                                    disabled={creating || !name.trim()}
                                    className="btn-primary self-start"
                                >
                                    {creating ? <Loader2 className="w-4 h-4 animate-spin" /> : <KeyRound className="w-4 h-4" />}
                                    {creating ? "Creating..." : "Create key"}
                                </button>
                            </form>
                        </section>

                        <section className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm">
                            <div className="flex items-center gap-2.5 mb-5">
                                <KeyRound className="w-5 h-5 text-indigo-600" />
                                <h2 className="text-base font-bold text-gray-800">Keys</h2>
                            </div>
                            {keys.length === 0 ? (
                                <p className="text-sm text-gray-500">
                                    This club has no API keys yet. Create one when an application needs to connect.
                                </p>
                            ) : (
                                <div className="overflow-x-auto">
                                    <table className="w-full border-collapse text-sm">
                                        <thead>
                                            <tr className="text-left text-xs uppercase tracking-wide text-gray-500 border-b border-gray-100">
                                                <th className="py-2 pr-4 font-semibold">Name</th>
                                                <th className="py-2 pr-4 font-semibold">Key</th>
                                                <th className="py-2 pr-4 font-semibold">Status</th>
                                                <th className="py-2 pr-4 font-semibold">Last used</th>
                                                <th className="py-2 pr-4 font-semibold">Expires</th>
                                                <th className="py-2 font-semibold"><span className="sr-only">Actions</span></th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            {keys.map((k) => {
                                                const status = keyStatus(k);
                                                return (
                                                    <tr key={k.id} className="border-b border-gray-50 last:border-0 align-top">
                                                        <td className="py-3 pr-4">
                                                            <p className="font-semibold text-gray-800">{k.name}</p>
                                                            <p className="text-xs text-gray-400">Created {formatDate(k.createdAt)}</p>
                                                        </td>
                                                        <td className="py-3 pr-4 font-mono text-xs text-gray-600 whitespace-nowrap">
                                                            {k.keyPrefix}_…
                                                        </td>
                                                        <td className="py-3 pr-4">
                                                            <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${STATUS_STYLES[status]}`}>
                                                                {status === "ACTIVE" ? "Active" : status === "EXPIRED" ? "Expired" : "Revoked"}
                                                            </span>
                                                        </td>
                                                        <td className="py-3 pr-4 text-gray-600 whitespace-nowrap">
                                                            {formatDate(k.lastUsedAt) ?? "Never"}
                                                        </td>
                                                        <td className="py-3 pr-4 text-gray-600 whitespace-nowrap">
                                                            {status === "REVOKED"
                                                                ? `Revoked ${formatDate(k.revokedAt)}`
                                                                : (formatDate(k.expiresAt) ?? "Never")}
                                                        </td>
                                                        <td className="py-3 text-right">
                                                            {status !== "REVOKED" && (
                                                                <button
                                                                    onClick={() => setPendingRevoke(k)}
                                                                    className="inline-flex items-center gap-1 px-2.5 py-1.5 text-xs font-semibold text-red-600 rounded-lg hover:bg-red-50"
                                                                >
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
                    </div>
                )}
            </main>

            <ConfirmDialog
                open={pendingRevoke !== null}
                danger
                title="Revoke this API key?"
                message={`Applications using "${pendingRevoke?.name}" will stop working within a minute. This can't be undone; you can create a new key instead.`}
                confirmLabel="Revoke key"
                onConfirm={handleRevoke}
                onCancel={() => setPendingRevoke(null)}
            />
        </div>
    );
}
