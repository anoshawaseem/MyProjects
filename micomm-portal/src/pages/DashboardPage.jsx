import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import * as clubService from "../api/organizationService";
import ProductMultiSelect from "../components/ProductMultiSelect";
import KpiCards from "../components/KpiCards";
import { setClubProducts, getClubProducts } from "../api/productService";
import { COUNTRIES } from "../constants/countries";
import { getTimezones } from "../constants/timezones";
import {
    Building2,
    CheckCircle2,
    AlertTriangle,
    Loader2,
    LogOut,
    PartyPopper,
    Package,
    ListChecks,
    Settings,
} from "lucide-react";
import { forwardRef } from "react";

const TIMEZONES = getTimezones();

export default function DashboardPage() {
    const { logout, role } = useAuth();

    const [name, setName] = useState("");
    const [slug, setSlug] = useState("");
    const [country, setCountry] = useState("");
    const [timezone, setTimezone] = useState("UTC");
    const [contactName, setContactName] = useState("");
    const [contactEmail, setContactEmail] = useState("");
    const [contactPhone, setContactPhone] = useState("");
    const [club, setClub] = useState(null);
    const [allClubs, setAllClubs] = useState([]);
    const [loadingClubs, setLoadingClubs] = useState(true);
    const [tenantDatabase, setTenantDatabase] = useState(null);
    const [error, setError] = useState(null);
    const [creating, setCreating] = useState(false);
    const [onboarding, setOnboarding] = useState(false);
    const [selectedProductIds, setSelectedProductIds] = useState([]);
    const [savingProducts, setSavingProducts] = useState(false);
    const [productsSavedMessage, setProductsSavedMessage] = useState(null);
    const [onboardingClubId, setOnboardingClubId] = useState(null);
    const tenantDatabaseRef = useRef(null);
    const [confirmAction, setConfirmAction] = useState(null);
    const [promptSelectedProductIds, setPromptSelectedProductIds] = useState([]);
    const [productPromptClubId, setProductPromptClubId] = useState(null);
    const [savingPromptProducts, setSavingPromptProducts] = useState(false);
    const [viewingClub, setViewingClub] = useState(null);

    useEffect(() => {
        refreshClubList();
    }, []);

    useEffect(() => {
        if (tenantDatabase && tenantDatabaseRef.current) {
            tenantDatabaseRef.current.scrollIntoView({ behavior: "smooth", block: "start" });
        }
    }, [tenantDatabase]);

    async function refreshClubList() {
        setLoadingClubs(true);
        try {
            setAllClubs(await clubService.listClubs());
        } catch (err) {
            console.error("Failed to load club list:", err);
        } finally {
            setLoadingClubs(false);
        }
    }

    async function handleCreateClub(e) {
        e.preventDefault();
        setError(null);

        setConfirmAction({
            type: "create",
            name,
            slug,
            country,
            timezone,
            contactName,
            contactEmail,
            contactPhone,
        });

        setCreating(true);
        setTenantDatabase(null);

        try {
            const created = await clubService.createOrganization({
                name,
                slug,
                country,
                timezone,
                contactName,
                contactEmail,
                contactPhone,
            });
            setClub(created);
            await refreshClubList();
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to create club");
        } finally {
            setCreating(false);
        }
    }

    async function handleTriggerOnboarding() {
        if (!club) return;
        setError(null);

        try {
            const products = await getClubProducts(club.id);
            if (!products || products.length === 0) {
                setProductPromptClubId(club.id);
                setPromptSelectedProductIds([]);
                return;
            }
        } catch (err) {
            console.error("Product check failed, proceeding with onboarding attempt:", err);
        }

        setOnboarding(true);
        try {
            const results = await clubService.triggerOnboarding(club.id);
            setTenantDatabase(results);
            setClub(await clubService.getOrganization(club.id));
            await refreshClubList();
        } catch (err) {
            if (isNoProductsError(err)) {
                setProductPromptClubId(club.id);
                setPromptSelectedProductIds([]);
            } else {
                setError(err instanceof Error ? err.message : "Onboarding failed");
            }
        } finally {
            setOnboarding(false);
        }
    }

    async function handleOnboardFromList(clubId) {
        setError(null);

        try {
            const products = await getClubProducts(clubId);
            if (!products || products.length === 0) {
                setProductPromptClubId(clubId);
                setPromptSelectedProductIds([]);
                return;
            }
        } catch (err) {
            console.error("Product check failed, proceeding with onboarding attempt:", err);
        }

        setOnboardingClubId(clubId);
        try {
            const results = await clubService.triggerOnboarding(clubId);
            setTenantDatabase(results);
            const updated = await clubService.getOrganization(clubId);
            if (club?.id === clubId) {
                setClub(updated);
            }
            await refreshClubList();
        } catch (err) {
            if (isNoProductsError(err)) {
                setProductPromptClubId(clubId);
                setPromptSelectedProductIds([]);
            } else {
                setError(err instanceof Error ? err.message : "Onboarding failed");
            }
        } finally {
            setOnboardingClubId(null);
        }
    }

    async function handleSaveProducts() {
        setSavingProducts(true);
        setError(null);
        setProductsSavedMessage(null);
        try {
            await setClubProducts(club.id, selectedProductIds);
            setProductsSavedMessage("Products saved successfully!");
        } catch (err) {
            setError(err.response?.data?.message ?? "Failed to save products");
        } finally {
            setSavingProducts(false);
        }
    }

    function isNoProductsError(err) {
        const msg =
            err?.response?.data?.message ??
            (err instanceof Error ? err.message : "") ??
            "";
        return msg.toLowerCase().includes("no products selected");
    }

    async function handleSavePromptProducts() {
        if (!productPromptClubId) return;
        setSavingPromptProducts(true);
        setError(null);
        try {
            await setClubProducts(productPromptClubId, promptSelectedProductIds);
            const clubIdToOnboard = productPromptClubId;
            setProductPromptClubId(null);
            await handleOnboardFromList(clubIdToOnboard);
        } catch (err) {
            setError(err.response?.data?.message ?? "Failed to save products");
        } finally {
            setSavingPromptProducts(false);
        }
    }

    function handleCancelProductPrompt() {
        setProductPromptClubId(null);
        setPromptSelectedProductIds([]);
    }

    function handleConfirmDialogCancel() {
        setConfirmAction(null);
    }

    function handleConfirmDialogConfirm() {
        if (confirmAction?.type === "create") {
            handleCreateClub();
        } else {
            handleTriggerOnboarding();
        }
    }

    return (
        <div className="min-h-screen bg-gray-50">
            <header className="flex justify-between items-center px-8 py-4 bg-white border-b border-gray-200 shadow-sm">
                <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-lg bg-gradient-to-br from-indigo-600 to-purple-600 text-white flex items-center justify-center font-bold">
                        M
                    </div>
                    <h1 className="text-lg font-bold text-gray-800">MiComm Admin Portal</h1>
                </div>
                <div className="flex items-center gap-3">
                    <span className="px-3 py-1 bg-indigo-50 text-indigo-600 rounded-full text-xs font-semibold">
                        {role}
                    </span>
                    <Link
                        to="/products"
                        className="flex items-center gap-1.5 px-4 py-2 bg-white border border-gray-200 rounded-lg text-sm font-semibold text-gray-700 hover:bg-gray-50 transition-colors"
                    >
                        <Package className="w-4 h-4" /> Products
                    </Link>
                    <button
                        onClick={logout}
                        className="flex items-center gap-1.5 px-4 py-2 bg-white border border-gray-200 rounded-lg text-sm font-semibold text-gray-700 hover:bg-gray-50 transition-colors"
                    >
                        <LogOut className="w-4 h-4" /> Logout
                    </button>
                </div>
            </header>

            <main className="max-w-5xl mx-auto px-6 py-8">
                {!loadingClubs && <KpiCards clubs={allClubs} />}

                <Card icon={Building2} title="Create Club">
                    <form onSubmit={handleCreateClub} className="flex flex-col gap-4">
                        <Field label="Club Name">
                            <input
                                value={name}
                                onChange={(e) => setName(e.target.value)}
                                required
                                placeholder="e.g. Royal Freshwater"
                                className="input"
                            />
                        </Field>
                        <Field label="Slug" hint="Used to generate the tenant schema name">
                            <input
                                value={slug}
                                onChange={(e) => setSlug(e.target.value)}
                                required
                                placeholder="e.g. royal-freshwater"
                                className="input"
                            />
                        </Field>
                        <Field label="Country">
                            <select
                                value={country}
                                onChange={(e) => setCountry(e.target.value)}
                                className="input"
                            >
                                <option value="">Select a country</option>
                                {COUNTRIES.map((c) => (
                                    <option key={c} value={c}>{c}</option>
                                ))}
                            </select>
                        </Field>
                        <Field label="Timezone" hint="IANA timezone identifier, e.g. Asia/Dubai">
                            <select
                                value={timezone}
                                onChange={(e) => setTimezone(e.target.value)}
                                className="input"
                            >
                                {TIMEZONES.map((tz) => (
                                    <option key={tz} value={tz}>{tz}</option>
                                ))}
                            </select>
                        </Field>
                        <Field label="Contact Name">
                            <input
                                value={contactName}
                                onChange={(e) => setContactName(e.target.value)}
                                placeholder="e.g. Jane Doe"
                                className="input"
                            />
                        </Field>
                        <Field label="Contact Email">
                            <input
                                type="email"
                                value={contactEmail}
                                onChange={(e) => setContactEmail(e.target.value)}
                                placeholder="e.g. jane@royalfreshwater.com"
                                className="input"
                            />
                        </Field>
                        <Field label="Contact Phone">
                            <input
                                value={contactPhone}
                                onChange={(e) => setContactPhone(e.target.value)}
                                placeholder="e.g. +971 50 123 4567"
                                className="input"
                            />
                        </Field>
                        <button type="submit" disabled={creating} className="btn-primary self-start">
                            {creating && <Loader2 className="w-4 h-4 animate-spin" />}
                            {creating ? "Creating..." : "Create Club"}
                        </button>
                    </form>
                </Card>

                {error && (
                    <div className="flex items-center gap-2 px-4 py-3 bg-red-50 text-red-600 rounded-xl text-sm mb-5">
                        <AlertTriangle className="w-4 h-4" /> {error}
                    </div>
                )}

                {club && (
                    <Card icon={CheckCircle2} title="Club Created">
                        <InfoGrid>
                            <InfoRow label="ID" value={club.id} mono />
                            <InfoRow label="Name" value={club.name} />
                            <InfoRow label="Slug" value={club.slug} mono />
                            <InfoRow label="Country" value={club.country ?? "—"} />
                            <InfoRow label="Timezone" value={club.timezone ?? "—"} />
                            <InfoRow label="Contact Name" value={club.contactName ?? "—"} />
                            <InfoRow label="Contact Email" value={club.contactEmail ?? "—"} />
                            <InfoRow label="Contact Phone" value={club.contactPhone ?? "—"} />
                            <InfoRow label="Status" value={<StatusPill status={club.status} />} />
                        </InfoGrid>

                        {club.status === "PENDING" && (
                            <button
                                onClick={handleTriggerOnboarding}
                                disabled={onboarding}
                                className="btn-primary mt-5"
                            >
                                {onboarding && <Loader2 className="w-4 h-4 animate-spin" />}
                                {onboarding ? "Provisioning tenant database..." : "Trigger Onboarding"}
                            </button>
                        )}

                        <div className="mt-6 pt-6 border-t border-gray-100">
                            <SectionHeader icon={Package} title="Select Products" />
                            <ProductMultiSelect selectedIds={selectedProductIds} onChange={setSelectedProductIds} />
                            <button
                                onClick={handleSaveProducts}
                                disabled={savingProducts}
                                className="btn-primary mt-4"
                            >
                                {savingProducts && <Loader2 className="w-4 h-4 animate-spin" />}
                                {savingProducts ? "Saving..." : "Save Products"}
                            </button>
                            {productsSavedMessage && (
                                <div className="flex items-center gap-2 px-4 py-3 bg-emerald-50 text-emerald-600 rounded-xl text-sm mt-4">
                                    <CheckCircle2 className="w-4 h-4" /> {productsSavedMessage}
                                </div>
                            )}
                        </div>
                    </Card>
                )}

                {tenantDatabase && (
                    <Card
                        ref={tenantDatabaseRef}
                        icon={PartyPopper}
                        title="Tenant Schemas Provisioned"
                        accent="border-emerald-200 bg-emerald-50"
                    >
                        <div className="flex flex-col gap-3">
                            {tenantDatabase.map((db) => (
                                <InfoGrid key={db.databaseIdentifier}>
                                    <InfoRow label="Schema" value={db.databaseIdentifier} mono />
                                    <InfoRow label="Status" value={<StatusPill status={db.status} />} />
                                    <InfoRow label="Schema Version" value={db.schemaVersion ?? "—"} />
                                </InfoGrid>
                            ))}
                        </div>
                    </Card>
                )}

                {!loadingClubs && allClubs.length > 0 && (
                    <Card icon={ListChecks} title="All Clubs">
                        <div className="overflow-x-auto">
                            <table className="w-full border-collapse">
                                <thead>
                                    <tr>
                                        <th className="th">Name</th>
                                        <th className="th">Slug</th>
                                        <th className="th">Country</th>
                                        <th className="th">Timezone</th>
                                        <th className="th">Status</th>
                                        <th className="th"></th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {allClubs.map((c) => (
                                        <tr key={c.id}>
                                            <td className="td">
                                                <button
                                                    onClick={() => setViewingClub(c)}
                                                    className="text-indigo-600 font-semibold hover:underline text-left"
                                                >
                                                    {c.name}
                                                </button>
                                            </td>
                                            <td className="td font-mono text-xs">{c.slug}</td>
                                            <td className="td">{c.country ?? "—"}</td>
                                            <td className="td">{c.timezone ?? "—"}</td>
                                            <td className="td"><StatusPill status={c.status} /></td>
                                            <td className="td">
                                                <div className="flex flex-col gap-1.5">
                                                    {c.status === "PENDING" && (
                                                        <button
                                                            onClick={() => handleOnboardFromList(c.id)}
                                                            disabled={onboardingClubId === c.id}
                                                            className="flex items-center gap-1 text-xs font-semibold text-emerald-600 hover:underline disabled:opacity-50"
                                                        >
                                                            {onboardingClubId === c.id ? (
                                                                <Loader2 className="w-3.5 h-3.5 animate-spin" />
                                                            ) : (
                                                                <PartyPopper className="w-3.5 h-3.5" />
                                                            )}
                                                            {onboardingClubId === c.id ? "Onboarding..." : "Complete Onboarding"}
                                                        </button>
                                                    )}
                                                    <Link
                                                        to={`/clubs/${c.id}/products`}
                                                        className="flex items-center gap-1 text-xs font-semibold text-indigo-600 hover:underline"
                                                    >
                                                        <Settings className="w-3.5 h-3.5" /> Products
                                                    </Link>
                                                    <Link
                                                        to={`/clubs/${c.id}/providers`}
                                                        className="flex items-center gap-1 text-xs font-semibold text-indigo-600 hover:underline"
                                                    >
                                                        <Settings className="w-3.5 h-3.5" /> Providers
                                                    </Link>
                                                </div>
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    </Card>
                )}
            </main>
            <ConfirmDialog
                open={!!confirmAction}
                title={
                    confirmAction?.type === "create"
                        ? "Create this club?"
                        : "Complete onboarding?"
                }
                message={
                    confirmAction?.type === "create"
                        ? `Create club "${name}" with slug "${slug}"?\n\n` +
                          `Country: ${country || "—"}\n` +
                          `Timezone: ${timezone}\n` +
                          `Contact: ${contactName || "—"} (${contactEmail || "—"}, ${contactPhone || "—"})\n\n` +
                          `This will submit the club for onboarding.`
                        : "This will provision the tenant database and schemas for this club. Continue?"
                }
                confirmLabel={confirmAction?.type === "create" ? "Create Club" : "Onboard"}
                onConfirm={handleConfirmDialogConfirm}
                onCancel={handleConfirmDialogCancel}
            />

            {productPromptClubId && (
                <div
                    className="fixed inset-0 z-50 flex items-center justify-center"
                    onClick={handleCancelProductPrompt}
                >
                    <div className="absolute inset-0 bg-black/40 backdrop-blur-sm" />
                    <div
                        className="relative bg-white rounded-2xl shadow-2xl w-full max-w-lg mx-4 p-6"
                        onClick={(e) => e.stopPropagation()}
                    >
                        <button
                            onClick={handleCancelProductPrompt}
                            className="absolute top-4 right-4 text-gray-400 hover:text-gray-600"
                        >
                            ✕
                        </button>

                        <SectionHeader icon={Package} title="Select Products to Continue Onboarding" />
                        <p className="text-sm text-gray-500 mb-4">
                            This club has no products selected yet. Please choose at least one
                            product before onboarding can proceed.
                        </p>

                        <ProductMultiSelect
                            selectedIds={promptSelectedProductIds}
                            onChange={setPromptSelectedProductIds}
                        />

                        <div className="flex justify-end gap-3 mt-6">
                            <button
                                onClick={handleCancelProductPrompt}
                                disabled={savingPromptProducts}
                                className="px-4 py-2 text-sm font-semibold text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors disabled:opacity-50"
                            >
                                Cancel
                            </button>
                            <button
                                onClick={handleSavePromptProducts}
                                disabled={savingPromptProducts || promptSelectedProductIds.length === 0}
                                className="flex items-center gap-1.5 px-4 py-2 text-sm font-semibold text-white bg-indigo-600 rounded-lg hover:bg-indigo-700 transition-colors disabled:opacity-50"
                            >
                                {savingPromptProducts && <Loader2 className="w-4 h-4 animate-spin" />}
                                {savingPromptProducts ? "Saving & Onboarding..." : "Save & Continue Onboarding"}
                            </button>
                        </div>
                    </div>
                </div>
            )}

            {viewingClub && (
                <div
                    className="fixed inset-0 z-50 flex items-center justify-center"
                    onClick={() => setViewingClub(null)}
                >
                    <div className="absolute inset-0 bg-black/40 backdrop-blur-sm" />
                    <div
                        className="relative bg-white rounded-2xl shadow-2xl w-full max-w-lg mx-4 p-6"
                        onClick={(e) => e.stopPropagation()}
                    >
                        <button
                            onClick={() => setViewingClub(null)}
                            className="absolute top-4 right-4 text-gray-400 hover:text-gray-600"
                        >
                            ✕
                        </button>

                        <SectionHeader icon={Building2} title="Club Details" />

                        <InfoGrid>
                            <InfoRow label="ID" value={viewingClub.id} mono />
                            <InfoRow label="Name" value={viewingClub.name} />
                            <InfoRow label="Slug" value={viewingClub.slug} mono />
                            <InfoRow label="Country" value={viewingClub.country ?? "—"} />
                            <InfoRow label="Timezone" value={viewingClub.timezone ?? "—"} />
                            <InfoRow label="Contact Name" value={viewingClub.contactName ?? "—"} />
                            <InfoRow label="Contact Email" value={viewingClub.contactEmail ?? "—"} />
                            <InfoRow label="Contact Phone" value={viewingClub.contactPhone ?? "—"} />
                            <InfoRow label="Status" value={<StatusPill status={viewingClub.status} />} />
                        </InfoGrid>

                        <div className="flex justify-end mt-6">
                            <button
                                onClick={() => setViewingClub(null)}
                                className="px-4 py-2 text-sm font-semibold text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
                            >
                                Close
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}

const Card = forwardRef(function Card({ icon: Icon, title, children, accent = "" }, ref) {
    return (
        <section ref={ref} className={`bg-white border border-gray-200 rounded-2xl p-6 mb-5 shadow-sm ${accent}`}>
            <SectionHeader icon={Icon} title={title} />
            {children}
        </section>
    );
});

function SectionHeader({ icon: Icon, title }) {
    return (
        <div className="flex items-center gap-2.5 mb-5">
            <Icon className="w-5 h-5 text-indigo-600" />
            <h2 className="text-base font-bold text-gray-800">{title}</h2>
        </div>
    );
}

function Field({ label, hint, children }) {
    return (
        <div className="flex flex-col gap-1.5">
            <label className="text-sm font-semibold text-gray-700">{label}</label>
            {children}
            {hint && <span className="text-xs text-gray-400">{hint}</span>}
        </div>
    );
}

function InfoGrid({ children }) {
    return <div className="flex flex-col gap-2.5">{children}</div>;
}

function InfoRow({ label, value, mono }) {
    return (
        <div className="flex justify-between items-center py-2 border-b border-gray-100 last:border-0">
            <span className="text-sm text-gray-500 font-medium">{label}</span>
            <span className={`text-sm text-gray-800 font-semibold ${mono ? "font-mono text-xs" : ""}`}>{value}</span>
        </div>
    );
}

function StatusPill({ status }) {
    const config = {
        ACTIVE: "bg-emerald-100 text-emerald-700",
        PENDING: "bg-amber-100 text-amber-700",
        PROVISIONING: "bg-amber-100 text-amber-700",
        SUSPENDED: "bg-red-100 text-red-700",
    };
    const cls = config[status] ?? "bg-gray-100 text-gray-600";

    return (
        <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold ${cls}`}>
            <span className="w-1.5 h-1.5 rounded-full bg-current" />
            {status}
        </span>
    );
}

function ConfirmDialog({
    open,
    title,
    message,
    confirmLabel,
    onConfirm,
    onCancel,
}) {
    return (
        <div
            className="fixed inset-0 flex items-center justify-center z-50"
            style={{
                display: open ? "flex" : "none",
            }}
        >
            <div className="flex flex-col gap-3 p-6 bg-white border border-gray-200 rounded-2xl shadow-lg max-w-md w-full">
                <h3 className="text-lg font-bold text-gray-800">{title}</h3>
                <p className="text-sm text-gray-600">{message}</p>
                <div className="flex gap-3">
                    <button
                        onClick={onCancel}
                        className="flex items-center gap-1.5 px-4 py-2 bg-white border border-gray-200 rounded-lg text-sm font-semibold text-gray-700 hover:bg-gray-50 transition-colors"
                    >
                        Cancel
                    </button>
                    <button
                        onClick={onConfirm}
                        className="flex items-center gap-1.5 px-4 py-2 bg-indigo-50 text-indigo-600 rounded-lg text-sm font-semibold hover:bg-indigo-100 transition-colors"
                    >
                        {confirmLabel}
                    </button>
                </div>
            </div>
        </div>
    );
}