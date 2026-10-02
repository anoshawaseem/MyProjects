import { Building2, CheckCircle2, Clock, Settings2 } from "lucide-react";

export default function KpiCards({ clubs }) {
    const total = clubs.length;
    const active = clubs.filter((c) => c.status === "ACTIVE").length;
    const pending = clubs.filter((c) => c.status === "PENDING").length;
    const provisioning = clubs.filter((c) => c.status === "PROVISIONING").length;

    const cards = [
        { label: "Total Clubs", value: total, icon: Building2, accent: "text-indigo-600", bg: "bg-indigo-50" },
        { label: "Onboarded", value: active, icon: CheckCircle2, accent: "text-emerald-600", bg: "bg-emerald-50" },
        { label: "Pending", value: pending, icon: Clock, accent: "text-amber-600", bg: "bg-amber-50" },
        { label: "Provisioning", value: provisioning, icon: Settings2, accent: "text-amber-600", bg: "bg-amber-50" },
    ];

    return (
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 mb-6">
            {cards.map(({ label, value, icon: Icon, accent, bg }) => (
                <div
                    key={label}
                    className={`${bg} rounded-2xl p-5 flex flex-col gap-2 border border-black/5 shadow-sm hover:shadow-md transition-shadow`}
                >
                    <Icon className={`${accent} w-5 h-5`} />
                    <div className={`${accent} text-3xl font-extrabold leading-none`}>{value}</div>
                    <div className="text-sm font-semibold text-gray-600">{label}</div>
                </div>
            ))}
        </div>
    );
}