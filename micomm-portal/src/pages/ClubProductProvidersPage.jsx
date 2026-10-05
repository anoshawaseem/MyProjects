import { useEffect, useState } from "react";
import { useParams, Link } from "react-router-dom";
import * as clubService from "../api/organizationService";
import { getClubProducts, listAllProducts } from "../api/productService";
import {
    listProviderTypes,
    listProviderAccounts,
    createProviderAccount,
    getClubProductProviders,
    assignClubProductProvider,
} from "../api/providerService";
import { ArrowLeft, Plug, Plus, Loader2, CheckCircle2, AlertTriangle, Mail, MessageSquare, Send } from "lucide-react";

const EMPTY_CONFIG = {
    SENDGRID: { apiKey: "", fromEmail: "" },
    SMTP: { host: "", port: "587", username: "", password: "", fromEmail: "", useTls: true },
    TWILIO: { accountSid: "", authToken: "", fromNumber: "" },
};

export default function ClubProductProvidersPage() {
    const { clubId } = useParams();
    const [club, setClub] = useState(null);
    const [products, setProducts] = useState([]);
    const [providerTypes, setProviderTypes] = useState([]);
    const [accounts, setAccounts] = useState([]);
    const [selectedProductId, setSelectedProductId] = useState(null);
    const [assignments, setAssignments] = useState({ EMAIL: "", SMS: "" });
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const [message, setMessage] = useState(null);

    const [providerTypeId, setProviderTypeId] = useState("");
    const [accountName, setAccountName] = useState("");
    const [fields, setFields] = useState({});
    const [creatingAccount, setCreatingAccount] = useState(false);

    useEffect(() => {
        load();
    }, [clubId]);

    useEffect(() => {
        if (selectedProductId) loadAssignments(selectedProductId);
    }, [selectedProductId]);

    async function load() {
        setLoading(true);
        try {
            const [clubData, clubProducts, allProducts, types, accts] = await Promise.all([
                clubService.getOrganization(clubId),
                getClubProducts(clubId),
                listAllProducts(),
                listProviderTypes(),
                listProviderAccounts(clubId),
            ]);
            setClub(clubData);

            const productMap = new Map(allProducts.map((p) => [p.id, p]));
            const productList = clubProducts
                .filter((cp) => cp.status === "ACTIVE")
                .map((cp) => productMap.get(cp.productId))
                .filter(Boolean);

            setProducts(productList);
            setProviderTypes(types);
            setAccounts(accts);
            if (productList.length > 0) setSelectedProductId(productList[0].id);
        } catch (err) {
            setError(err.response?.data?.message ?? "Failed to load data");
        } finally {
            setLoading(false);
        }
    }

    async function loadAssignments(productId) {
        try {
            const mappings = await getClubProductProviders(clubId, productId);
            const next = { EMAIL: "", SMS: "" };
            mappings.forEach((m) => { next[m.channel] = m.providerAccountId; });
            setAssignments(next);
        } catch (err) {
            setError(err.response?.data?.message ?? "Failed to load provider assignments");
        }
    }

    function selectedTypeCode() {
        const type = providerTypes.find((t) => t.id === providerTypeId);
        return type?.code ?? null;
    }

    function handleProviderTypeChange(id) {
        setProviderTypeId(id);
        const type = providerTypes.find((t) => t.id === id);
        setFields(type ? { ...EMPTY_CONFIG[type.code] } : {});
    }

    function updateField(key, value) {
        setFields((prev) => ({ ...prev, [key]: value }));
    }

    async function handleCreateAccount(e) {
        e.preventDefault();
        setCreatingAccount(true);
        setError(null);
        try {
            const code = selectedTypeCode();
            let secretReference = "";
            let configJson = {};

            if (code === "SENDGRID") {
                secretReference = fields.apiKey ?? "";
                configJson = { fromEmail: fields.fromEmail };
            } else if (code === "SMTP") {
                secretReference = fields.password ?? "";
                configJson = {
                    host: fields.host,
                    port: fields.port,
                    username: fields.username,
                    fromEmail: fields.fromEmail,
                    useTls: fields.useTls,
                };
            } else if (code === "TWILIO") {
                secretReference = fields.authToken ?? "";
                configJson = { accountSid: fields.accountSid, fromNumber: fields.fromNumber };
            }

            const created = await createProviderAccount(
                clubId, providerTypeId, accountName, secretReference, JSON.stringify(configJson)
            );
            setAccounts((prev) => [...prev, created]);
            setProviderTypeId("");
            setAccountName("");
            setFields({});
            setMessage("Provider account created!");
        } catch (err) {
            setError(err.response?.data?.message ?? "Failed to create provider account");
        } finally {
            setCreatingAccount(false);
        }
    }

    async function handleAssign(channel, providerAccountId) {
        setAssignments((prev) => ({ ...prev, [channel]: providerAccountId }));
        if (!providerAccountId) return;
        try {
            await assignClubProductProvider(clubId, selectedProductId, channel, providerAccountId);
            setMessage(`${channel} provider assigned!`);
        } catch (err) {
            setError(err.response?.data?.message ?? "Failed to assign provider");
        }
    }

    function accountsForChannel(channel) {
        const typeIds = providerTypes.filter((t) => t.channel === channel).map((t) => t.id);
        return accounts.filter((a) => typeIds.includes(a.providerTypeId) && a.status === "ACTIVE");
    }

    const code = selectedTypeCode();

    return (
        <div className="min-h-screen bg-gray-50">
            <header className="flex items-center gap-3 px-8 py-4 bg-white border-b border-gray-200 shadow-sm">
                <Link to="/" className="text-gray-500 hover:text-gray-800">
                    <ArrowLeft className="w-5 h-5" />
                </Link>
                <h1 className="text-lg font-bold text-gray-800">
                    {club ? `Provider Setup — ${club.name}` : "Provider Setup"}
                </h1>
            </header>

            <main className="max-w-3xl mx-auto px-6 py-8">
                {loading ? (
                    <div className="flex items-center gap-2 text-sm text-gray-500 py-4">
                        <Loader2 className="w-4 h-4 animate-spin" /> Loading...
                    </div>
                ) : (
                    <>
                        <section className="bg-white border border-gray-200 rounded-2xl p-6 mb-5 shadow-sm">
                            <div className="flex items-center gap-2.5 mb-5">
                                <Plus className="w-5 h-5 text-indigo-600" />
                                <h2 className="text-base font-bold text-gray-800">Add Provider Account</h2>
                            </div>

                            <form onSubmit={handleCreateAccount} className="flex flex-col gap-4">
                                <div className="grid grid-cols-2 gap-4">
                                    <div className="flex flex-col gap-1.5">
                                        <label className="text-sm font-semibold text-gray-700">Provider Type</label>
                                        <select
                                            value={providerTypeId}
                                            onChange={(e) => handleProviderTypeChange(e.target.value)}
                                            required
                                            className="input"
                                        >
                                            <option value="">Select type...</option>
                                            {providerTypes.map((t) => (
                                                <option key={t.id} value={t.id}>{t.name} ({t.channel})</option>
                                            ))}
                                        </select>
                                    </div>
                                    <div className="flex flex-col gap-1.5">
                                        <label className="text-sm font-semibold text-gray-700">Account Name</label>
                                        <input
                                            value={accountName}
                                            onChange={(e) => setAccountName(e.target.value)}
                                            required
                                            placeholder="e.g. Harbour Club SendGrid"
                                            className="input"
                                        />
                                    </div>
                                </div>

                                {code === "SENDGRID" && (
                                    <div className="grid grid-cols-2 gap-4">
                                        <div className="flex flex-col gap-1.5">
                                            <label className="text-sm font-semibold text-gray-700">API Key</label>
                                            <input
                                                type="password"
                                                value={fields.apiKey ?? ""}
                                                onChange={(e) => updateField("apiKey", e.target.value)}
                                                required
                                                placeholder="SG.xxxxxxxx"
                                                className="input"
                                            />
                                        </div>
                                        <div className="flex flex-col gap-1.5">
                                            <label className="text-sm font-semibold text-gray-700">From Email</label>
                                            <input
                                                type="email"
                                                value={fields.fromEmail ?? ""}
                                                onChange={(e) => updateField("fromEmail", e.target.value)}
                                                required
                                                placeholder="noreply@club.com"
                                                className="input"
                                            />
                                        </div>
                                    </div>
                                )}

                                {code === "SMTP" && (
                                    <>
                                        <div className="grid grid-cols-2 gap-4">
                                            <div className="flex flex-col gap-1.5">
                                                <label className="text-sm font-semibold text-gray-700">Host</label>
                                                <input
                                                    value={fields.host ?? ""}
                                                    onChange={(e) => updateField("host", e.target.value)}
                                                    required
                                                    placeholder="smtp.club.com"
                                                    className="input"
                                                />
                                            </div>
                                            <div className="flex flex-col gap-1.5">
                                                <label className="text-sm font-semibold text-gray-700">Port</label>
                                                <input
                                                    value={fields.port ?? "587"}
                                                    onChange={(e) => updateField("port", e.target.value)}
                                                    required
                                                    placeholder="587"
                                                    className="input"
                                                />
                                            </div>
                                        </div>
                                        <div className="grid grid-cols-2 gap-4">
                                            <div className="flex flex-col gap-1.5">
                                                <label className="text-sm font-semibold text-gray-700">Username</label>
                                                <input
                                                    value={fields.username ?? ""}
                                                    onChange={(e) => updateField("username", e.target.value)}
                                                    required
                                                    placeholder="smtp-user"
                                                    className="input"
                                                />
                                            </div>
                                            <div className="flex flex-col gap-1.5">
                                                <label className="text-sm font-semibold text-gray-700">Password</label>
                                                <input
                                                    type="password"
                                                    value={fields.password ?? ""}
                                                    onChange={(e) => updateField("password", e.target.value)}
                                                    required
                                                    placeholder="••••••••"
                                                    className="input"
                                                />
                                            </div>
                                        </div>
                                        <div className="flex flex-col gap-1.5">
                                            <label className="text-sm font-semibold text-gray-700">From Email</label>
                                            <input
                                                type="email"
                                                value={fields.fromEmail ?? ""}
                                                onChange={(e) => updateField("fromEmail", e.target.value)}
                                                required
                                                placeholder="noreply@club.com"
                                                className="input"
                                            />
                                        </div>
                                        <label className="flex items-center gap-2 text-sm text-gray-700">
                                            <input
                                                type="checkbox"
                                                checked={fields.useTls ?? true}
                                                onChange={(e) => updateField("useTls", e.target.checked)}
                                            />
                                            Use TLS
                                        </label>
                                    </>
                                )}

                                {code === "TWILIO" && (
                                    <>
                                        <div className="grid grid-cols-2 gap-4">
                                            <div className="flex flex-col gap-1.5">
                                                <label className="text-sm font-semibold text-gray-700">Account SID</label>
                                                <input
                                                    value={fields.accountSid ?? ""}
                                                    onChange={(e) => updateField("accountSid", e.target.value)}
                                                    required
                                                    placeholder="ACxxxxxxxx"
                                                    className="input"
                                                />
                                            </div>
                                            <div className="flex flex-col gap-1.5">
                                                <label className="text-sm font-semibold text-gray-700">Auth Token</label>
                                                <input
                                                    type="password"
                                                    value={fields.authToken ?? ""}
                                                    onChange={(e) => updateField("authToken", e.target.value)}
                                                    required
                                                    placeholder="••••••••"
                                                    className="input"
                                                />
                                            </div>
                                        </div>
                                        <div className="flex flex-col gap-1.5">
                                            <label className="text-sm font-semibold text-gray-700">From Number</label>
                                            <input
                                                value={fields.fromNumber ?? ""}
                                                onChange={(e) => updateField("fromNumber", e.target.value)}
                                                required
                                                placeholder="+15551234567"
                                                className="input"
                                            />
                                        </div>
                                    </>
                                )}

                                {code && (
                                    <button type="submit" disabled={creatingAccount} className="btn-primary self-start">
                                        {creatingAccount && <Loader2 className="w-4 h-4 animate-spin" />}
                                        {creatingAccount ? "Creating..." : "Add Account"}
                                    </button>
                                )}
                            </form>
                        </section>

                        <section className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm">
                            <div className="flex items-center gap-2.5 mb-5">
                                <Plug className="w-5 h-5 text-indigo-600" />
                                <h2 className="text-base font-bold text-gray-800">Assign Providers per Product</h2>
                            </div>

                            <div className="flex gap-2 mb-5 flex-wrap">
                                {products.map((p) => (
                                    <button
                                        key={p.id}
                                        onClick={() => setSelectedProductId(p.id)}
                                        className={`px-3.5 py-2 rounded-lg text-sm font-semibold transition-colors ${
                                            selectedProductId === p.id
                                                ? "bg-indigo-600 text-white"
                                                : "bg-gray-100 text-gray-700 hover:bg-gray-200"
                                        }`}
                                    >
                                        {p.name}
                                    </button>
                                ))}
                            </div>

                            {selectedProductId && (
                                <div className="flex flex-col gap-4">
                                    <ChannelSelector
                                        icon={Mail}
                                        label="Email Provider"
                                        value={assignments.EMAIL}
                                        options={accountsForChannel("EMAIL")}
                                        onChange={(id) => handleAssign("EMAIL", id)}
                                    />
                                    <ChannelSelector
                                        icon={MessageSquare}
                                        label="SMS Provider"
                                        value={assignments.SMS}
                                        options={accountsForChannel("SMS")}
                                        onChange={(id) => handleAssign("SMS", id)}
                                    />
                                    {assignments.EMAIL && (
                                        <Link
                                            to={`/clubs/${clubId}/products/${selectedProductId}/test-email`}
                                            className="btn-primary self-start"
                                        >
                                            <Send className="w-4 h-4" />
                                            Test Send Email
                                        </Link>
                                    )}
                                </div>
                            )}
                        </section>

                        {message && (
                            <div className="flex items-center gap-2 px-4 py-3 bg-emerald-50 text-emerald-600 rounded-xl text-sm mt-4">
                                <CheckCircle2 className="w-4 h-4" /> {message}
                            </div>
                        )}
                        {error && (
                            <div className="flex items-center gap-2 px-4 py-3 bg-red-50 text-red-600 rounded-xl text-sm mt-4">
                                <AlertTriangle className="w-4 h-4" /> {error}
                            </div>
                        )}
                    </>
                )}
            </main>
        </div>
    );
}

function ChannelSelector({ icon: Icon, label, value, options, onChange }) {
    return (
        <div className="flex items-center gap-3 px-4 py-3 rounded-xl border border-gray-200">
            <Icon className="w-5 h-5 text-gray-400" />
            <span className="text-sm font-semibold text-gray-700 w-32">{label}</span>
            <select value={value} onChange={(e) => onChange(e.target.value)} className="input flex-1">
                <option value="">Not assigned</option>
                {options.map((a) => (
                    <option key={a.id} value={a.id}>{a.name}</option>
                ))}
            </select>
        </div>
    );
}