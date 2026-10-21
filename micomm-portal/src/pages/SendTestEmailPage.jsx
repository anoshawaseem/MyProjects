import { useEffect, useState } from "react";
import { useParams, Link } from "react-router-dom";
import * as clubService from "../api/organizationService";
import { listAllProducts } from "../api/productService";
import { sendBulkEmail } from "../api/emailService";
import { listGlobalTemplates, listTemplates } from "../api/emailTemplateService";
import { ArrowLeft, Send, Loader2, CheckCircle2, AlertTriangle, FileText, Home } from "lucide-react";

export default function SendTestEmailPage() {
    const { clubId, productId } = useParams();
    const [club, setClub] = useState(null);
    const [product, setProduct] = useState(null);
    const [loading, setLoading] = useState(true);
    const [sending, setSending] = useState(false);
    const [error, setError] = useState(null);
    const [result, setResult] = useState(null);

    const [templates, setTemplates] = useState([]);
    const [selectedTemplateId, setSelectedTemplateId] = useState("");

    const [fromEmail, setFromEmail] = useState("");
    const [subject, setSubject] = useState("");
    const [htmlBody, setHtmlBody] = useState("");
    const [recipientsText, setRecipientsText] = useState("");

    useEffect(() => {
        load();
    }, [clubId, productId]);

    async function load() {
        setLoading(true);
        try {
            const [clubData, allProducts, templateList, globalList] = await Promise.all([
                clubService.getOrganization(clubId),
                listAllProducts(),
                listTemplates(clubId, productId).catch(() => []),
                // Clubs created before global templates have none (the call is rejected).
                listGlobalTemplates(clubId, productId).catch(() => []),
            ]);
            setClub(clubData);
            setProduct(allProducts.find((p) => p.id === productId) ?? null);
            setTemplates([...templateList, ...globalList.map((t) => ({ ...t, name: `${t.name} (global)` }))]);
        } catch (err) {
            setError(err.response?.data?.message ?? "Failed to load data");
        } finally {
            setLoading(false);
        }
    }

    function handleTemplateSelect(templateId) {
        setSelectedTemplateId(templateId);
        if (!templateId) return;
        const tpl = templates.find((t) => t.id === templateId);
        if (tpl) {
            setSubject(tpl.subject);
            setHtmlBody(tpl.body);
        }
    }

    async function handleSend(e) {
        e.preventDefault();
        setSending(true);
        setError(null);
        setResult(null);

        const recipients = recipientsText
            .split(/[\n,]/)
            .map((r) => r.trim())
            .filter(Boolean);

        if (recipients.length === 0) {
            setError("Enter at least one recipient email address");
            setSending(false);
            return;
        }

        try {
            const response = await sendBulkEmail({
                clubId,
                productId,
                fromEmail,
                subject,
                htmlBody,
                recipients,
            });
            setResult(response);
        } catch (err) {
            setError(err.response?.data?.errorMessage ?? err.message ?? "Failed to send email");
        } finally {
            setSending(false);
        }
    }

    return (
        <div className="min-h-screen bg-gray-50">
            <header className="flex items-center justify-between px-8 py-4 bg-white border-b border-gray-200 shadow-sm">
                <div className="flex items-center gap-3">
                    <Link to="/dashboard" className="text-gray-500 hover:text-gray-800" title="Home">
                        <Home className="w-5 h-5" />
                    </Link>
                    <Link to={`/clubs/${clubId}/providers`} className="text-gray-500 hover:text-gray-800">
                        <ArrowLeft className="w-5 h-5" />
                    </Link>
                    <h1 className="text-lg font-bold text-gray-800">
                        {club && product ? `Test Send Email — ${club.name} / ${product.name}` : "Test Send Email"}
                    </h1>
                </div>
                <Link
                    to={`/clubs/${clubId}/products/${productId}/email-templates`}
                    className="flex items-center gap-1.5 px-4 py-2 bg-white border border-gray-200 rounded-lg text-sm font-semibold text-gray-700 hover:bg-gray-50 transition-colors"
                >
                    <FileText className="w-4 h-4" /> Manage Templates
                </Link>
            </header>

            <main className="max-w-2xl mx-auto px-6 py-8">
                {loading ? (
                    <div className="flex items-center gap-2 text-sm text-gray-500 py-4">
                        <Loader2 className="w-4 h-4 animate-spin" /> Loading...
                    </div>
                ) : (
                    <section className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm">
                        <div className="flex items-center gap-2.5 mb-5">
                            <Send className="w-5 h-5 text-indigo-600" />
                            <h2 className="text-base font-bold text-gray-800">Send Bulk Email</h2>
                        </div>

                        <form onSubmit={handleSend} className="flex flex-col gap-4">
                            {templates.length > 0 && (
                                <div className="flex flex-col gap-1.5">
                                    <label className="text-sm font-semibold text-gray-700">Use a Template (optional)</label>
                                    <select
                                        value={selectedTemplateId}
                                        onChange={(e) => handleTemplateSelect(e.target.value)}
                                        className="input"
                                    >
                                        <option value="">— None, write manually —</option>
                                        {templates.map((t) => (
                                            <option key={t.id} value={t.id}>{t.name}</option>
                                        ))}
                                    </select>
                                </div>
                            )}

                            <div className="flex flex-col gap-1.5">
                                <label className="text-sm font-semibold text-gray-700">From Email</label>
                                <input
                                    type="email"
                                    value={fromEmail}
                                    onChange={(e) => setFromEmail(e.target.value)}
                                    required
                                    placeholder="noreply@club.com"
                                    className="input"
                                />
                            </div>

                            <div className="flex flex-col gap-1.5">
                                <label className="text-sm font-semibold text-gray-700">Subject</label>
                                <input
                                    value={subject}
                                    onChange={(e) => setSubject(e.target.value)}
                                    required
                                    placeholder="Test email subject"
                                    className="input"
                                />
                            </div>

                            <div className="flex flex-col gap-1.5">
                                <label className="text-sm font-semibold text-gray-700">Body (HTML)</label>
                                <textarea
                                    value={htmlBody}
                                    onChange={(e) => setHtmlBody(e.target.value)}
                                    required
                                    rows={6}
                                    placeholder="<p>Hello from MiComm!</p>"
                                    className="input font-mono text-sm"
                                />
                            </div>

                            <div className="flex flex-col gap-1.5">
                                <label className="text-sm font-semibold text-gray-700">
                                    Recipients (one per line, or comma-separated)
                                </label>
                                <textarea
                                    value={recipientsText}
                                    onChange={(e) => setRecipientsText(e.target.value)}
                                    required
                                    rows={4}
                                    placeholder={"jane@example.com\njohn@example.com"}
                                    className="input font-mono text-sm"
                                />
                            </div>

                            <button type="submit" disabled={sending} className="btn-primary self-start">
                                {sending && <Loader2 className="w-4 h-4 animate-spin" />}
                                {sending ? "Sending..." : "Send Test Email"}
                            </button>
                        </form>

                        {result && (
                            <div className="flex items-center gap-2 px-4 py-3 bg-emerald-50 text-emerald-600 rounded-xl text-sm mt-4">
                                <CheckCircle2 className="w-4 h-4" />
                                Queued! Request ID: {result.requestId} — {result.recipientCount} recipient(s), status: {result.status}
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
        </div>
    );
}