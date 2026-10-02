import { useEffect, useState } from "react";
import { useParams, Link } from "react-router-dom";
import * as clubService from "../api/organizationService";
import { getClubProducts, setClubProducts, listProducts } from "../api/productService";
import ProductMultiSelect from "../components/ProductMultiSelect";
import ConfirmDialog from "../components/ConfirmDialog";
import { ArrowLeft, Package, CheckCircle2, AlertTriangle, Loader2 } from "lucide-react";

export default function ClubProductsPage() {
    const { clubId } = useParams();
    const [club, setClub] = useState(null);
    const [allProducts, setAllProducts] = useState([]);
    const [initialProductIds, setInitialProductIds] = useState([]);
    const [selectedProductIds, setSelectedProductIds] = useState([]);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState(null);
    const [message, setMessage] = useState(null);
    const [confirmState, setConfirmState] = useState({ open: false, removedNames: [] });

    useEffect(() => {
        load();
    }, [clubId]);

    async function load() {
        setLoading(true);
        try {
            const [clubData, clubProducts, products] = await Promise.all([
                clubService.getOrganization(clubId),
                getClubProducts(clubId),
                listProducts(),
            ]);
            setClub(clubData);
            setAllProducts(products);
            const ids = clubProducts.map((cp) => cp.productId);
            setInitialProductIds(ids);
            setSelectedProductIds(ids);
        } catch (err) {
            setError(err.response?.data?.message ?? "Failed to load club products");
        } finally {
            setLoading(false);
        }
    }

    function handleSaveClick() {
        const removedIds = initialProductIds.filter((id) => !selectedProductIds.includes(id));

        if (removedIds.length > 0) {
            const removedNames = allProducts
                .filter((p) => removedIds.includes(p.id))
                .map((p) => p.name);
            setConfirmState({ open: true, removedNames });
        } else {
            doSave();
        }
    }

    async function doSave() {
        setConfirmState({ open: false, removedNames: [] });
        setSaving(true);
        setError(null);
        setMessage(null);
        try {
            await setClubProducts(clubId, selectedProductIds);
            setInitialProductIds(selectedProductIds);
            setMessage("Products updated successfully!");
        } catch (err) {
            setError(err.response?.data?.message ?? "Failed to save products");
        } finally {
            setSaving(false);
        }
    }

    return (
        <div className="min-h-screen bg-gray-50">
            <header className="flex items-center gap-3 px-8 py-4 bg-white border-b border-gray-200 shadow-sm">
                <Link to="/" className="text-gray-500 hover:text-gray-800">
                    <ArrowLeft className="w-5 h-5" />
                </Link>
                <h1 className="text-lg font-bold text-gray-800">
                    {club ? `Manage Products — ${club.name}` : "Manage Products"}
                </h1>
            </header>

            <main className="max-w-2xl mx-auto px-6 py-8">
                {loading ? (
                    <div className="flex items-center gap-2 text-sm text-gray-500 py-4">
                        <Loader2 className="w-4 h-4 animate-spin" /> Loading...
                    </div>
                ) : (
                    <section className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm">
                        <div className="flex items-center gap-2.5 mb-5">
                            <Package className="w-5 h-5 text-indigo-600" />
                            <h2 className="text-base font-bold text-gray-800">Select Products</h2>
                        </div>

                        <ProductMultiSelect selectedIds={selectedProductIds} onChange={setSelectedProductIds} />

                        <button onClick={handleSaveClick} disabled={saving} className="btn-primary mt-5">
                            {saving && <Loader2 className="w-4 h-4 animate-spin" />}
                            {saving ? "Saving..." : "Save Changes"}
                        </button>

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
                    </section>
                )}
            </main>

            <ConfirmDialog
                open={confirmState.open}
                title="Remove Product(s)?"
                message={`Removing "${confirmState.removedNames.join(", ")}" will permanently DELETE its tenant schema and all data in it for this club.\n\nThis cannot be undone.`}
                confirmLabel="Delete Schema"
                cancelLabel="Cancel"
                danger
                onConfirm={doSave}
                onCancel={() => setConfirmState({ open: false, removedNames: [] })}
            />
        </div>
    );
}