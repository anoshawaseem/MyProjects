import { useEffect, useState } from "react";
import { listProducts } from "../api/productService";
import { Loader2, Package } from "lucide-react";

export default function ProductMultiSelect({ selectedIds, onChange }) {
    const [products, setProducts] = useState([]);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        listProducts()
            .then(setProducts)
            .catch((err) => console.error("Failed to load products:", err.response?.status, err.response?.data))
            .finally(() => setLoading(false));
    }, []);

    const toggle = (productId) => {
        const next = new Set(selectedIds);
        next.has(productId) ? next.delete(productId) : next.add(productId);
        onChange(Array.from(next));
    };

    if (loading) {
        return (
            <div className="flex items-center gap-2 text-sm text-gray-500 py-4">
                <Loader2 className="w-4 h-4 animate-spin" /> Loading products...
            </div>
        );
    }

    if (products.length === 0) {
        return <div className="text-sm text-gray-400 py-4">No products available.</div>;
    }

    return (
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {products.map((product) => {
                const checked = selectedIds.includes(product.id);
                return (
                    <label
                        key={product.id}
                        className={`flex items-center gap-3 px-4 py-3 rounded-xl border cursor-pointer transition-colors ${
                            checked
                                ? "border-indigo-400 bg-indigo-50"
                                : "border-gray-200 hover:border-gray-300 bg-white"
                        }`}
                    >
                        <input
                            type="checkbox"
                            checked={checked}
                            onChange={() => toggle(product.id)}
                            className="w-4 h-4 accent-indigo-600"
                        />
                        <Package className="w-4 h-4 text-gray-400" />
                        <span className="text-sm font-medium text-gray-800">{product.name}</span>
                    </label>
                );
            })}
        </div>
    );
}