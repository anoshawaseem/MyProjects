import { useMemo, useRef, useState } from "react";
import { Braces, Eye, FileText, Loader2, AlertTriangle } from "lucide-react";

const PLACEHOLDER = /\{\{\s*([A-Za-z][A-Za-z0-9_]*)\s*}}/g;

function placeholdersIn(...texts) {
    const names = new Set();
    for (const text of texts) {
        for (const match of (text ?? "").matchAll(PLACEHOLDER)) names.add(match[1]);
    }
    return [...names];
}

function escapeHtml(value) {
    return value
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}

// Same rules as the server's TemplateRenderer: values are HTML-escaped in the body, not in the subject.
function render(text, samples, html) {
    return (text ?? "").replace(PLACEHOLDER, (_, name) => {
        const value = samples[name]?.trim() ? samples[name] : `[${name}]`;
        return html ? escapeHtml(value) : value;
    });
}

/**
 * Create / edit form for one email template.
 *
 * variables: placeholders offered as insert buttons ([{ name, required }]). Placeholders
 * typed into the content are picked up for the preview too.
 * strict: variables are exactly what the module template type provides at send time, so
 * warn about unused required ones and unknown extras. Free-form templates are not strict.
 */
export default function TemplateEditor({
    title,
    initial,
    variables = [],
    strict = false,
    contextLabel,
    saving,
    onSave,
    onCancel,
}) {
    const [name, setName] = useState(initial.name ?? "");
    const [subject, setSubject] = useState(initial.subject ?? "");
    const [body, setBody] = useState(initial.body ?? "");
    const [samples, setSamples] = useState({});
    const [lastFocused, setLastFocused] = useState("body");
    const subjectRef = useRef(null);
    const bodyRef = useRef(null);

    const declared = variables.map((v) => v.name);
    const used = useMemo(() => placeholdersIn(subject, body), [subject, body]);
    const allNames = [...new Set([...declared, ...used])];
    const undeclared = strict ? used.filter((n) => !declared.includes(n)) : [];
    const missingRequired = strict
        ? variables.filter((v) => v.required && !used.includes(v.name)).map((v) => v.name)
        : [];

    function insertVariable(variableName) {
        const token = `{{${variableName}}}`;
        const isSubject = lastFocused === "subject";
        const el = isSubject ? subjectRef.current : bodyRef.current;
        const value = isSubject ? subject : body;
        const setValue = isSubject ? setSubject : setBody;
        const start = el?.selectionStart ?? value.length;
        const end = el?.selectionEnd ?? value.length;
        setValue(value.slice(0, start) + token + value.slice(end));
        requestAnimationFrame(() => {
            el?.focus();
            el?.setSelectionRange(start + token.length, start + token.length);
        });
    }

    function handleSubmit(e) {
        e.preventDefault();
        onSave({ name, subject, body });
    }

    return (
        <section className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm mb-6">
            <div className="flex flex-wrap items-center gap-2.5 mb-5">
                <FileText className="w-5 h-5 text-indigo-600" />
                <h2 className="text-base font-bold text-gray-800">{title}</h2>
                {contextLabel && (
                    <span className="px-2.5 py-0.5 bg-indigo-50 text-indigo-600 rounded-full text-xs font-semibold">
                        {contextLabel}
                    </span>
                )}
            </div>

            <div className="grid gap-6 lg:grid-cols-2">
                <form onSubmit={handleSubmit} className="flex flex-col gap-4 min-w-0">
                    <div className="flex flex-col gap-1.5">
                        <label className="text-sm font-semibold text-gray-700">Template name</label>
                        <input
                            value={name}
                            onChange={(e) => setName(e.target.value)}
                            required
                            placeholder="e.g. Welcome email"
                            className="input"
                        />
                    </div>
                    <div className="flex flex-col gap-1.5">
                        <label className="text-sm font-semibold text-gray-700">Subject</label>
                        <input
                            ref={subjectRef}
                            value={subject}
                            onChange={(e) => setSubject(e.target.value)}
                            onFocus={() => setLastFocused("subject")}
                            required
                            maxLength={500}
                            placeholder="Welcome to {{clubName}}"
                            className="input"
                        />
                    </div>
                    <div className="flex flex-col gap-1.5">
                        <label className="text-sm font-semibold text-gray-700">Body (HTML)</label>
                        <textarea
                            ref={bodyRef}
                            value={body}
                            onChange={(e) => setBody(e.target.value)}
                            onFocus={() => setLastFocused("body")}
                            required
                            rows={12}
                            placeholder="<p>Hi {{memberName}},</p>"
                            className="input font-mono text-sm"
                        />
                    </div>

                    {declared.length > 0 && (
                        <div className="flex flex-col gap-2">
                            <p className="flex items-center gap-1.5 text-xs font-semibold text-gray-500 uppercase">
                                <Braces className="w-3.5 h-3.5" /> Insert variable ({lastFocused})
                            </p>
                            <div className="flex flex-wrap gap-2">
                                {variables.map((v) => (
                                    <button
                                        key={v.name}
                                        type="button"
                                        onClick={() => insertVariable(v.name)}
                                        className="px-2.5 py-1 text-xs font-mono rounded-md border border-indigo-200 bg-indigo-50 text-indigo-700 hover:bg-indigo-100"
                                        title={v.required ? "Required" : "Optional"}
                                    >
                                        {`{{${v.name}}}`}
                                        {v.required && <span className="text-red-500">*</span>}
                                    </button>
                                ))}
                            </div>
                        </div>
                    )}

                    {(missingRequired.length > 0 || undeclared.length > 0) && (
                        <div className="flex flex-col gap-1 px-3 py-2.5 bg-amber-50 text-amber-700 rounded-lg text-xs">
                            {missingRequired.length > 0 && (
                                <p className="flex items-center gap-1.5">
                                    <AlertTriangle className="w-3.5 h-3.5 shrink-0" />
                                    Not used yet: {missingRequired.map((n) => `{{${n}}}`).join(", ")}
                                </p>
                            )}
                            {undeclared.length > 0 && (
                                <p className="flex items-center gap-1.5">
                                    <AlertTriangle className="w-3.5 h-3.5 shrink-0" />
                                    Not provided by this module (sends will fail unless the caller passes them):{" "}
                                    {undeclared.map((n) => `{{${n}}}`).join(", ")}
                                </p>
                            )}
                        </div>
                    )}

                    <div className="flex gap-3">
                        <button type="submit" disabled={saving} className="btn-primary">
                            {saving && <Loader2 className="w-4 h-4 animate-spin" />}
                            {saving ? "Saving..." : "Save template"}
                        </button>
                        <button
                            type="button"
                            onClick={onCancel}
                            className="px-4 py-2 text-sm font-semibold text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
                        >
                            Cancel
                        </button>
                    </div>
                </form>

                <div className="flex flex-col gap-4 min-w-0">
                    <p className="flex items-center gap-1.5 text-xs font-semibold text-gray-500 uppercase">
                        <Eye className="w-3.5 h-3.5" /> Preview
                    </p>
                    {allNames.length > 0 && (
                        <div className="grid gap-2 sm:grid-cols-2">
                            {allNames.map((n) => (
                                <label key={n} className="flex flex-col gap-1 text-xs text-gray-500">
                                    <span className="font-mono">{n}</span>
                                    <input
                                        value={samples[n] ?? ""}
                                        onChange={(e) => setSamples((s) => ({ ...s, [n]: e.target.value }))}
                                        placeholder="Sample value"
                                        className="input !py-1.5"
                                    />
                                </label>
                            ))}
                        </div>
                    )}
                    <div className="border border-gray-200 rounded-xl overflow-hidden">
                        <div className="px-4 py-2.5 bg-gray-50 border-b border-gray-200 text-sm">
                            <span className="text-gray-500">Subject: </span>
                            <span className="font-semibold text-gray-800 break-words">
                                {render(subject, samples, false) || <span className="text-gray-400">(empty)</span>}
                            </span>
                        </div>
                        {/* Sandboxed: template HTML can't run scripts or reach the portal. */}
                        <iframe
                            title="Email preview"
                            sandbox=""
                            srcDoc={render(body, samples, true)}
                            className="w-full h-80 bg-white"
                        />
                    </div>
                </div>
            </div>
        </section>
    );
}
