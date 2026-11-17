import { useEffect, useRef, useState } from "react";
import { useParams, Link } from "react-router-dom";
import * as clubService from "../api/organizationService";
import { listAllProducts } from "../api/productService";
import { sendNotification, listNotificationRecipients } from "../api/notificationService";
import { ArrowLeft, MessageSquare, Loader2, CheckCircle2, AlertTriangle, Home } from "lucide-react";

const E164 = /^\+[1-9][0-9]{6,14}$/;
const MAX_LENGTH = 1600;
const POLL_INTERVAL_MS = 2000;
const POLL_ATTEMPTS = 15;

/** SMS segments: 160 chars (GSM-7) or 70 (Unicode) for one part; 153 / 67 per part when split. */
function segmentInfo(text) {
    const GSM_EXTRA = "£¥èéùìòÇØøÅå€ÆæßÉÄÖÑÜ§¿äöñüà";
    const unicode = [...text].some((ch) => ch.charCodeAt(0) > 127 && !GSM_EXTRA.includes(ch));
    const single = unicode ? 70 : 160;
    const multi = unicode ? 67 : 153;
    const segments = text.length === 0 ? 0 : text.length <= single ? 1 : Math.ceil(text.length / multi);
    return { unicode, segments };
}

const STATUS_STYLES = {
    SENT: "bg-emerald-50 text-emerald-700",
    DELIVERED: "bg-emerald-50 text-emerald-700",
    FAILED: "bg-red-50 text-red-700",
    PENDING: "bg-amber-50 text-amber-700",
};

export default function SendTestSmsPage() {
    const { clubId, productId } = useParams();
    const [club, setClub] = useState(null);
    const [product, setProduct] = useState(null);
    const [loading, setLoading] = useState(true);
    const [sending, setSending] = useState(false);
    const [error, setError] = useState(null);
    const [result, setResult] = useState(null);
    const [recipientStatuses, setRecipientStatuses] = useState(null);
    const [polling, setPolling] = useState(false);

    const [text, setText] = useState("");
    const [recipientsText, setRecipientsText] = useState("");
    const pollTimer = useRef(null);

    useEffect(() => {
        load();
        return () => clearTimeout(pollTimer.current);
    }, [clubId, productId]);

    async function load() {
        setLoading(true);
        try {
            const [clubData, allProducts] = await Promise.all([
                clubService.getOrganization(clubId),
                listAllProducts(),
            ]);
            setClub(clubData);
            setProduct(allProducts.find((p) => p.id === productId) ?? null);
        } catch (err) {
            setError(err.response?.data?.message ?? "Failed to load data");
        } finally {
            setLoading(false);
        }
    }

    /** Polls the send's recipients until none is PENDING (or the attempts run out). */
    function pollStatuses(notificationId, attempt = 1) {
        pollTimer.current = setTimeout(async () => {
            try {
                const page = await listNotificationRecipients(clubId, productId, notificationId);
                const items = page.items ?? [];
                setRecipientStatuses(items);
                const done = items.length > 0 && items.every((r) => r.status !== "PENDING");
                if (!done && attempt < POLL_ATTEMPTS) {
                    pollStatuses(notificationId, attempt + 1);
                } else {
                    setPolling(false);
                }
            } catch {
                // The send's rows are written by the worker; until then the lookup can 404.
                if (attempt < POLL_ATTEMPTS) {
                    pollStatuses(notificationId, attempt + 1);
                } else {
                    setPolling(false);
                }
            }
        }, POLL_INTERVAL_MS);
    }

    async function handleSend(e) {
        e.preventDefault();
        clearTimeout(pollTimer.current);
        setError(null);
        setResult(null);
        setRecipientStatuses(null);

        const phones = [...new Set(recipientsText.split(/[\n,]/).map((r) => r.replace(/[\s()-]/g, "")).filter(Boolean))];
        if (phones.length === 0) {
            setError("Enter at least one phone number");
            return;
        }
        const invalid = phones.filter((p) => !E164.test(p));
        if (invalid.length > 0) {
            setError(`Not in international format (e.g. +923001234567): ${invalid.join(", ")}`);
            return;
        }

        setSending(true);
        try {
            const response = await sendNotification({
                clubId,
                productId,
                channels: ["SMS"],
                type: "TRANSACTIONAL",
                sms: { text },
                recipients: phones.map((phone) => ({ phone })),
            });
            setResult(response);
            setPolling(true);
            pollStatuses(response.requestId);
        } catch (err) {
            setError(err.response?.data?.errorMessage ?? err.message ?? "Failed to send SMS");
        } finally {
            setSending(false);
        }
    }

    const { unicode, segments } = segmentInfo(text);

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
                        {club && product ? `Test Send SMS — ${club.name} / ${product.name}` : "Test Send SMS"}
                    </h1>
                </div>
            </header>

            <main className="max-w-2xl mx-auto px-6 py-8">
                {loading ? (
                    <div className="flex items-center gap-2 text-sm text-gray-500 py-4">
                        <Loader2 className="w-4 h-4 animate-spin" /> Loading...
                    </div>
                ) : (
                    <section className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm">
                        <div className="flex items-center gap-2.5 mb-5">
                            <MessageSquare className="w-5 h-5 text-indigo-600" />
                            <h2 className="text-base font-bold text-gray-800">Send SMS</h2>
                        </div>

                        <form onSubmit={handleSend} className="flex flex-col gap-4">
                            <div className="flex flex-col gap-1.5">
                                <label className="text-sm font-semibold text-gray-700">Message</label>
                                <textarea
                                    value={text}
                                    onChange={(e) => setText(e.target.value)}
                                    required
                                    maxLength={MAX_LENGTH}
                                    rows={5}
                                    placeholder="Hello from MiComm!"
                                    className="input text-sm"
                                />
                                <p className="text-xs text-gray-500">
                                    {text.length} / {MAX_LENGTH} characters · {segments} SMS segment{segments === 1 ? "" : "s"}
                                    {unicode && " (contains special characters: 70 per segment)"}
                                </p>
                            </div>

                            <div className="flex flex-col gap-1.5">
                                <label className="text-sm font-semibold text-gray-700">
                                    Phone numbers (one per line, or comma-separated)
                                </label>
                                <textarea
                                    value={recipientsText}
                                    onChange={(e) => setRecipientsText(e.target.value)}
                                    required
                                    rows={4}
                                    placeholder={"+923001234567\n+447700900123"}
                                    className="input font-mono text-sm"
                                />
                                <p className="text-xs text-gray-500">
                                    International format with country code, starting with +.
                                </p>
                            </div>

                            <button type="submit" disabled={sending} className="btn-primary self-start">
                                {sending && <Loader2 className="w-4 h-4 animate-spin" />}
                                {sending ? "Sending..." : "Send Test SMS"}
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

                        {result && (
                            <div className="mt-6">
                                <div className="flex items-center gap-2 mb-2">
                                    <h3 className="text-sm font-bold text-gray-800">Delivery status</h3>
                                    {polling && <Loader2 className="w-3.5 h-3.5 animate-spin text-gray-400" />}
                                </div>
                                {!recipientStatuses || recipientStatuses.length === 0 ? (
                                    <p className="text-sm text-gray-500">Waiting for the provider...</p>
                                ) : (
                                    <table className="w-full border-collapse">
                                        <thead>
                                            <tr>
                                                <th className="th">Phone</th>
                                                <th className="th">Status</th>
                                                <th className="th">Details</th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            {recipientStatuses.map((r) => (
                                                <tr key={r.id}>
                                                    <td className="td font-mono text-xs">{r.address}</td>
                                                    <td className="td">
                                                        <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${STATUS_STYLES[r.status] ?? "bg-gray-100 text-gray-700"}`}>
                                                            {r.status}
                                                        </span>
                                                    </td>
                                                    <td className="td text-xs text-gray-600 break-all">
                                                        {r.errorMessage ?? (r.providerMessageId ? `Twilio SID ${r.providerMessageId}` : "—")}
                                                    </td>
                                                </tr>
                                            ))}
                                        </tbody>
                                    </table>
                                )}
                            </div>
                        )}
                    </section>
                )}
            </main>
        </div>
    );
}
