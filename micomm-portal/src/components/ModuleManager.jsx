import { useState } from "react";
import { Blocks, Loader2 } from "lucide-react";

/**
 * ORG_ADMIN panel: choose which product modules a club has switched on.
 * Switching one on copies its default templates into the club.
 */
export default function ModuleManager({ modules, saving, onSave, onCancel }) {
    const [selected, setSelected] = useState(() => new Set(modules.filter((m) => m.enabled).map((m) => m.code)));

    function toggle(code) {
        setSelected((prev) => {
            const next = new Set(prev);
            if (next.has(code)) next.delete(code);
            else next.add(code);
            return next;
        });
    }

    const newlyEnabled = modules.filter((m) => !m.enabled && selected.has(m.code));
    const disabling = modules.filter((m) => m.enabled && !selected.has(m.code));

    return (
        <section className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm mb-6">
            <div className="flex items-center gap-2.5 mb-4">
                <Blocks className="w-5 h-5 text-indigo-600" />
                <h2 className="text-base font-bold text-gray-800">Modules for this club</h2>
            </div>

            {modules.length === 0 ? (
                <p className="text-sm text-gray-500">This product has no modules defined.</p>
            ) : (
                <div className="flex flex-col gap-2 mb-4">
                    {modules.map((m) => (
                        <label
                            key={m.code}
                            className="flex items-start gap-3 p-3 border border-gray-100 rounded-xl hover:bg-gray-50 cursor-pointer"
                        >
                            <input
                                type="checkbox"
                                checked={selected.has(m.code)}
                                onChange={() => toggle(m.code)}
                                className="mt-1 accent-indigo-600"
                            />
                            <span>
                                <span className="block text-sm font-semibold text-gray-800">
                                    {m.name} <span className="font-mono text-xs text-gray-400">{m.code}</span>
                                </span>
                                {m.description && <span className="block text-xs text-gray-500">{m.description}</span>}
                            </span>
                        </label>
                    ))}
                </div>
            )}

            {(newlyEnabled.length > 0 || disabling.length > 0) && (
                <div className="text-xs text-gray-600 bg-gray-50 rounded-lg px-3 py-2.5 mb-4 flex flex-col gap-1">
                    {newlyEnabled.length > 0 && (
                        <p>
                            Switching on {newlyEnabled.map((m) => m.name).join(", ")} adds its default templates to
                            this club.
                        </p>
                    )}
                    {disabling.length > 0 && (
                        <p>
                            Switching off {disabling.map((m) => m.name).join(", ")} hides its templates; they come back
                            unchanged if it is switched on again.
                        </p>
                    )}
                </div>
            )}

            <div className="flex gap-3">
                <button onClick={() => onSave([...selected])} disabled={saving} className="btn-primary">
                    {saving && <Loader2 className="w-4 h-4 animate-spin" />}
                    {saving ? "Saving..." : "Save modules"}
                </button>
                <button
                    onClick={onCancel}
                    className="px-4 py-2 text-sm font-semibold text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
                >
                    Cancel
                </button>
            </div>
        </section>
    );
}
