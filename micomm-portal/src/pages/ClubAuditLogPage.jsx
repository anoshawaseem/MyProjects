import { useEffect, useState } from "react";
import { useParams, Link } from "react-router-dom";
import * as clubService from "../api/organizationService";
import { getClubProducts, listAllProducts } from "../api/productService";
import { listAuditLogs } from "../api/auditLogService";
import { AlertTriangle, ArrowLeft, Bot, History, Home, KeyRound, Loader2, User } from "lucide-react";

/** Readable names for the actions MiComm records (common-lib AuditActions). */
const ACTIONS = {
    USER_LOGIN: "Signed in",
    USER_LOGIN_FAILED: "Sign-in failed",
    CLUB_ONBOARDED: "Club onboarded",
    CLUB_PRODUCTS_UPDATED: "Products changed",
    MODULES_UPDATED: "Modules changed",
    PROVIDER_ACCOUNT_CREATED: "Provider account added",
    PROVIDER_ACCOUNT_DISABLED: "Provider account disabled",
    PRODUCT_PROVIDER_ASSIGNED: "Product provider set",
    MODULE_PROVIDER_ASSIGNED: "Module provider set",
    MODULE_PROVIDER_CLEARED: "Module provider removed",
    API_KEY_CREATED: "API key created",
    API_KEY_REVOKED: "API key revoked",
    EMAIL_SEND_QUEUED: "Email sent",
    TEMPLATE_CREATED: "Template created",
    TEMPLATE_UPDATED: "Template edited",
    TEMPLATE_DELETED: "Template deleted",
    GLOBAL_TEMPLATE_CREATED: "Global template created",
    GLOBAL_TEMPLATE_UPDATED: "Global template edited",
    GLOBAL_TEMPLATE_DELETED: "Global template deleted",
};

const ACTOR_ICONS = { USER: User, API_KEY: KeyRound, SYSTEM: Bot };

// Shown in the details column; actorType/actorId/ip have their own columns or are internal.
const HIDDEN_DETAILS = new Set(["actorType", "actorId", "ip"]);

function errorText(err, fallback) {
    return err.response?.data?.errorMessage ?? err.message ?? fallback;
}

/** "2026-10-19" (a date input) → start of that day, or of the next day for an exclusive end. */
function dayStart(value, addDays = 0) {
    if (!value) return undefined;
    const d = new Date(`${value}T00:00:00`);
    d.setDate(d.getDate() + addDays);
    return d.toISOString();
}

function describeDetails(entry) {
    const details = entry.details;
    if (!details || typeof details !== "object") return details ? String(details) : "";
    return Object.entries(details)
        .filter(([key, value]) => !HIDDEN_DETAILS.has(key) && value !== null && value !== "")
        .map(([key, value]) => `${key}: ${Array.isArray(value) ? value.join(", ") : value}`)
        .join(" · ");
}

/**
 * Everything recorded for a club: sign-ins, setup changes, API keys, templates and sends, with who
 * did it. Entries come from the club's own data and cannot be edited or deleted.
 */
export default function ClubAuditLogPage() {
    const { clubId } = useParams();
    const [club, setClub] = useState(null);
    const [products, setProducts] = useState([]);
    // Entries and paging cursor, tagged with the filters they were loaded for.
    const [result, setResult] = useState({ key: null, entries: [], nextCursor: null });
    const [loadingMore, setLoadingMore] = useState(false);
    const [error, setError] = useState(null);

    const [action, setAction] = useState("");
    const [productId, setProductId] = useState("");
    const [fromDate, setFromDate] = useState("");
    const [toDate, setToDate] = useState("");

    const filters = {
        action: action || undefined,
        productId: productId || undefined,
        from: dayStart(fromDate),
        to: dayStart(toDate, 1),
    };
    const filterKey = JSON.stringify(filters);
    const loading = result.key !== filterKey;
    const { entries, nextCursor } = result;

    useEffect(() => {
        Promise.all([clubService.getOrganization(clubId), getClubProducts(clubId), listAllProducts()])
            .then(([clubData, clubProducts, allProducts]) => {
                setClub(clubData);
                const byId = new Map(allProducts.map((p) => [p.id, p]));
                setProducts(clubProducts.map((cp) => byId.get(cp.productId)).filter(Boolean));
            })
            .catch(() => {});
    }, [clubId]);

    useEffect(() => {
        let cancelled = false;
        listAuditLogs(clubId, JSON.parse(filterKey))
            .then((page) => {
                if (cancelled) return;
                setError(null);
                setResult({ key: filterKey, entries: page.items, nextCursor: page.nextCursor });
            })
            .catch((err) => {
                if (cancelled) return;
                setError(errorText(err, "Failed to load audit log"));
                setResult({ key: filterKey, entries: [], nextCursor: null });
            });
        return () => {
            cancelled = true;
        };
    }, [clubId, filterKey]);

    async function loadMore() {
        setLoadingMore(true);
        try {
            const page = await listAuditLogs(clubId, filters, nextCursor);
            setResult((prev) => ({ ...prev, entries: [...prev.entries, ...page.items], nextCursor: page.nextCursor }));
        } catch (err) {
            setError(errorText(err, "Failed to load more entries"));
        } finally {
            setLoadingMore(false);
        }
    }

    const productName = (id) => products.find((p) => p.id === id)?.name ?? (id ? "Other product" : "Club-wide");

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
                    Audit Log{club ? ` — ${club.name}` : ""}
                </h1>
            </header>

            <main className="max-w-6xl mx-auto px-4 sm:px-6 py-8 flex flex-col gap-5">
                <section className="bg-white border border-gray-200 rounded-2xl p-5 shadow-sm">
                    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
                        <div className="flex flex-col gap-1.5">
                            <label htmlFor="audit-action" className="text-sm font-semibold text-gray-700">Action</label>
                            <select id="audit-action" value={action} onChange={(e) => setAction(e.target.value)} className="input">
                                <option value="">All actions</option>
                                {Object.entries(ACTIONS).map(([code, label]) => (
                                    <option key={code} value={code}>{label}</option>
                                ))}
                            </select>
                        </div>
                        <div className="flex flex-col gap-1.5">
                            <label htmlFor="audit-product" className="text-sm font-semibold text-gray-700">Product</label>
                            <select id="audit-product" value={productId} onChange={(e) => setProductId(e.target.value)} className="input">
                                <option value="">All products</option>
                                {products.map((p) => (
                                    <option key={p.id} value={p.id}>{p.name}</option>
                                ))}
                            </select>
                        </div>
                        <div className="flex flex-col gap-1.5">
                            <label htmlFor="audit-from" className="text-sm font-semibold text-gray-700">From</label>
                            <input id="audit-from" type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)} className="input" />
                        </div>
                        <div className="flex flex-col gap-1.5">
                            <label htmlFor="audit-to" className="text-sm font-semibold text-gray-700">To</label>
                            <input id="audit-to" type="date" value={toDate} min={fromDate || undefined} onChange={(e) => setToDate(e.target.value)} className="input" />
                        </div>
                    </div>
                </section>

                {error && (
                    <div className="flex items-center gap-2 px-4 py-3 bg-red-50 text-red-600 rounded-xl text-sm">
                        <AlertTriangle className="w-4 h-4 shrink-0" /> {error}
                    </div>
                )}

                <section className="bg-white border border-gray-200 rounded-2xl p-5 shadow-sm">
                    <div className="flex items-center gap-2.5 mb-4">
                        <History className="w-5 h-5 text-indigo-600" />
                        <h2 className="text-base font-bold text-gray-800">Activity</h2>
                    </div>
                    {loading ? (
                        <div className="flex items-center gap-2 text-sm text-gray-500 py-4">
                            <Loader2 className="w-4 h-4 animate-spin" /> Loading...
                        </div>
                    ) : entries.length === 0 ? (
                        <p className="text-sm text-gray-500">
                            No activity recorded{action || productId || fromDate || toDate ? " for these filters" : " yet"}.
                            Actions are recorded from the moment the club is onboarded.
                        </p>
                    ) : (
                        <>
                            <div className="overflow-x-auto">
                                <table className="w-full border-collapse text-sm">
                                    <thead>
                                        <tr className="text-left text-xs uppercase tracking-wide text-gray-500 border-b border-gray-100">
                                            <th className="py-2 pr-4 font-semibold">When</th>
                                            <th className="py-2 pr-4 font-semibold">Who</th>
                                            <th className="py-2 pr-4 font-semibold">Action</th>
                                            <th className="py-2 pr-4 font-semibold">Product</th>
                                            <th className="py-2 font-semibold">Details</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {entries.map((e) => {
                                            const Icon = ACTOR_ICONS[e.actorType] ?? User;
                                            return (
                                                <tr key={e.id} className="border-b border-gray-50 last:border-0 align-top">
                                                    <td className="py-3 pr-4 text-gray-600 whitespace-nowrap tabular-nums">
                                                        {new Date(e.createdAt).toLocaleString()}
                                                    </td>
                                                    <td className="py-3 pr-4">
                                                        <span className="flex items-center gap-1.5 text-gray-800">
                                                            <Icon className="w-3.5 h-3.5 text-gray-400 shrink-0" />
                                                            <span className="break-all">{e.actor ?? "Unknown"}</span>
                                                        </span>
                                                        {e.details?.ip && (
                                                            <span className="text-xs text-gray-400 font-mono">{e.details.ip}</span>
                                                        )}
                                                    </td>
                                                    <td className="py-3 pr-4 whitespace-nowrap">
                                                        <span className={`font-semibold ${e.action === "USER_LOGIN_FAILED" ? "text-red-600" : "text-gray-800"}`}>
                                                            {ACTIONS[e.action] ?? e.action}
                                                        </span>
                                                    </td>
                                                    <td className="py-3 pr-4 text-gray-600 whitespace-nowrap">{productName(e.productId)}</td>
                                                    <td className="py-3 text-gray-600 min-w-[16rem]">
                                                        <span className="break-words">{describeDetails(e)}</span>
                                                        {e.entityId && (
                                                            <span className="block text-xs text-gray-400 font-mono break-all">
                                                                {e.entityType} {e.entityId}
                                                            </span>
                                                        )}
                                                    </td>
                                                </tr>
                                            );
                                        })}
                                    </tbody>
                                </table>
                            </div>
                            {nextCursor && (
                                <button
                                    onClick={loadMore}
                                    disabled={loadingMore}
                                    className="mt-4 px-4 py-2 text-sm font-semibold text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 flex items-center gap-2"
                                >
                                    {loadingMore && <Loader2 className="w-4 h-4 animate-spin" />}
                                    {loadingMore ? "Loading..." : "Load older entries"}
                                </button>
                            )}
                        </>
                    )}
                </section>
            </main>
        </div>
    );
}
