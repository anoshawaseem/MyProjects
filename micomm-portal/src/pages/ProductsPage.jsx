import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { listAllProducts, createProduct, updateProduct, deleteProduct } from "../api/productService";
import ConfirmDialog from "../components/ConfirmDialog";
import { ArrowLeft, Plus, Trash2, Package, Loader2, CheckCircle2, AlertTriangle } from "lucide-react";

export default function ProductsPage() {
    const [products, setProducts] = useState([]);
    const [loading, setLoading] = useState(true);
    const [code, setCode] = useState("");
    const [name, setName] = useState("");
    const [description, setDescription] = useState("");
    const [creating, setCreating] = useState(false);
    const [error, setError] = useState(null);
    const [message, setMessage] = useState(null);
    const [deleteTarget, setDeleteTarget] = useState(null);

    useEffect(() => {
        refresh();
    }, []);

    async function refresh() {
        setLoading(true);
        try {
            setProducts(await listAllProducts());
        } catch (err) {
            setError(err.response?.data?.message ?? "Failed to load products");
        } finally {
            setLoading(false);
        }
    }

    async function handleCreate(e) {
        e.preventDefault();
        setCreating(true);
        setError(null);
        setMessage(null);
        try {
            await createProduct(code, name, description);
            setCode("");
            setName("");
            setDescription("");
            setMessage("Product created successfully!");
            await refresh();
        } catch (err) {
            setError(err.response?.data?.message ?? "Failed to create product");
        } finally {
            setCreating(false);
        }
    }

    async function handleToggleStatus(product) {
        try {
            const newStatus = product.status === "ACTIVE" ? "INACTIVE" : "ACTIVE";
            await updateProduct(product.id, { status: newStatus });
            await refresh();
        } catch (err) {
            setError(err.response?.data?.message ?? "Failed to update product");
        }
    }

    async function confirmDelete() {
        try {
            await deleteProduct(deleteTarget.id);
            setDeleteTarget(null);
            await refresh();
        } catch (err) {
            setError(err.response?.data?.message ?? "Failed to delete product");
            setDeleteTarget(null);
        }
    }

    return (
        <div className="min-h-screen bg-gray-50">
            <header className="flex items-center gap-3 px-8 py-4 bg-white border-b border-gray-200 shadow-sm">
                <Link to="/" className="text-gray-500 hover:text-gray-800">
                    <ArrowLeft className="w-5 h-5" />
                </Link>
                <h1 className="text-lg font-bold text-gray-800">Manage Products</h1>
            </header>

            <main className="max-w-3xl mx-auto px-6 py-8">
                <section className="bg-white border border-gray-200 rounded-2xl p-6 mb-5 shadow-sm">
                    <div className="flex items-center gap-2.5 mb-5">
                        <Plus className="w-5 h-5 text-indigo-600" />
                        <h2 className="text-base font-bold text-gray-800">Add Product</h2>
                    </div>

                    <form onSubmit={handleCreate} className="flex flex-col gap-4">
                        <div className="grid grid-cols-2 gap-4">
                            <div className="flex flex-col gap-1.5">
                                <label className="text-sm font-semibold text-gray-700">Code</label>
                                <input
                                    value={code}
                                    onChange={(e) => setCode(e.target.value)}
                                    required
                                    placeholder="e.g. topyacht"
                                    className="input"
                                />
                            </div>
                            <div className="flex flex-col gap-1.5">
                                <label className="text-sm font-semibold text-gray-700">Name</label>
                                <input
                                    value={name}
                                    onChange={(e) => setName(e.target.value)}
                                    required
                                    placeholder="e.g. TopYacht"
                                    className="input"
                                />
                            </div>
                        </div>
                        <div className="flex flex-col gap-1.5">
                            <label className="text-sm font-semibold text-gray-700">Description</label>
                            <input
                                value={description}
                                onChange={(e) => setDescription(e.target.value)}
                                placeholder="Optional description"
                                className="input"
                            />
                        </div>
                        <button type="submit" disabled={creating} className="btn-primary self-start">
                            {creating && <Loader2 className="w-4 h-4 animate-spin" />}
                            {creating ? "Creating..." : "Add Product"}
                        </button>
                    </form>

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

                <section className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm">
                    <div className="flex items-center gap-2.5 mb-5">
                        <Package className="w-5 h-5 text-indigo-600" />
                        <h2 className="text-base font-bold text-gray-800">All Products</h2>
                    </div>

                    {loading ? (
                        <div className="flex items-center gap-2 text-sm text-gray-500 py-4">
                            <Loader2 className="w-4 h-4 animate-spin" /> Loading...
                        </div>
                    ) : products.length === 0 ? (
                        <div className="text-sm text-gray-400 py-4">No products yet.</div>
                    ) : (
                        <div className="flex flex-col gap-2">
                            {products.map((p) => (
                                <div
                                    key={p.id}
                                    className="flex items-center justify-between px-4 py-3 rounded-xl border border-gray-200"
                                >
                                    <div>
                                        <div className="text-sm font-semibold text-gray-800">{p.name}</div>
                                        <div className="text-xs text-gray-400 font-mono">{p.code}</div>
                                    </div>
                                    <div className="flex items-center gap-3">
                                        <span
                                            className={`px-2.5 py-1 rounded-full text-xs font-semibold ${
                                                p.status === "ACTIVE"
                                                    ? "bg-emerald-100 text-emerald-700"
                                                    : "bg-gray-100 text-gray-500"
                                            }`}
                                        >
                                            {p.status}
                                        </span>
                                        <button
                                            onClick={() => handleToggleStatus(p)}
                                            className="text-xs font-semibold text-indigo-600 hover:underline"
                                        >
                                            {p.status === "ACTIVE" ? "Deactivate" : "Activate"}
                                        </button>
                                        <button
                                            onClick={() => setDeleteTarget(p)}
                                            className="text-gray-400 hover:text-red-500"
                                        >
                                            <Trash2 className="w-4 h-4" />
                                        </button>
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}
                </section>
            </main>

            <ConfirmDialog
                open={!!deleteTarget}
                title="Deactivate Product?"
                message={`Are you sure you want to deactivate "${deleteTarget?.name}"? Clubs using this product will keep their existing data, but it will no longer be selectable for new onboarding.`}
                confirmLabel="Deactivate"
                cancelLabel="Cancel"
                danger
                onConfirm={confirmDelete}
                onCancel={() => setDeleteTarget(null)}
            />
        </div>
    );
}