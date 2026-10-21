import { useEffect, useState } from "react";
import { useParams, useSearchParams, Link } from "react-router-dom";
import * as clubService from "../api/organizationService";
import { listAllProducts } from "../api/productService";
import {
    listTemplates,
    createTemplate,
    updateTemplate,
    deleteTemplate,
    listGlobalTemplates,
    createGlobalTemplate,
    updateGlobalTemplate,
    deleteGlobalTemplate,
} from "../api/emailTemplateService";
import { getClubModules, listTemplateTypes, setClubModules } from "../api/moduleService";
import { useAuth } from "../context/AuthContext";
import TemplateEditor from "../components/TemplateEditor";
import ModuleManager from "../components/ModuleManager";
import ConfirmDialog from "../components/ConfirmDialog";
import {
    ArrowLeft,
    Blocks,
    CheckCircle2,
    CircleDashed,
    FileText,
    Globe,
    Home,
    Info,
    Loader2,
    Pencil,
    Plus,
    Settings2,
    Trash2,
    AlertTriangle,
} from "lucide-react";

function errorText(err, fallback) {
    return err.response?.data?.errorMessage ?? err.message ?? fallback;
}

function formatDate(value) {
    return value ? new Date(value).toLocaleString() : "";
}

/**
 * Email templates for one club + product, organised by product module.
 *
 * Module-aware clubs get one tab per enabled module (?module=MEMBERSHIP deep-links to a
 * tab); each tab shows the module's required templates and the club's own extra ones.
 * They also get a "Global" tab (?scope=global) for the club's templates outside any module,
 * which are shared by all of the club's products.
 * Clubs created before modules keep the flat template list they always had.
 */
export default function EmailTemplatesPage() {
    const { clubId, productId } = useParams();
    const [searchParams, setSearchParams] = useSearchParams();
    const { role } = useAuth();
    const isOrgAdmin = role === "ORG_ADMIN";

    const [club, setClub] = useState(null);
    const [product, setProduct] = useState(null);
    const [clubModules, setClubModulesState] = useState({ supportsModules: false, modules: [] });
    // Templates + module template types, tagged with the view they were loaded for.
    const [moduleData, setModuleData] = useState({ key: null, templates: [], types: [] });
    const [reloadCount, setReloadCount] = useState(0);

    const [loading, setLoading] = useState(true);
    // False when the club / its modules could not be loaded: show only the error then.
    const [baseLoaded, setBaseLoaded] = useState(false);
    const [error, setError] = useState(null);
    const [message, setMessage] = useState(null);

    const [editor, setEditor] = useState(null);
    const [saving, setSaving] = useState(false);
    const [managingModules, setManagingModules] = useState(false);
    const [savingModules, setSavingModules] = useState(false);
    const [pendingDelete, setPendingDelete] = useState(null);

    const enabledModules = clubModules.modules.filter((m) => m.enabled);
    const requestedModule = searchParams.get("module")?.toUpperCase();
    // The Global tab: chosen explicitly, or the only tab when no module is switched on.
    const isGlobal =
        clubModules.supportsModules && (searchParams.get("scope") === "global" || enabledModules.length === 0);
    const activeModule =
        clubModules.supportsModules && !isGlobal
            ? (enabledModules.find((m) => m.code === requestedModule) ?? enabledModules[0] ?? null)
            : null;
    const activeCode = activeModule?.code ?? null;
    const canList = baseLoaded && (!clubModules.supportsModules || activeCode !== null || isGlobal);
    const viewKey = `${isGlobal ? "global" : (activeCode ?? "-")}|${reloadCount}`;
    const loadingModule = canList && moduleData.key !== viewKey;
    const { templates, types } = moduleData;

    useEffect(() => {
        let cancelled = false;
        Promise.all([
            clubService.getOrganization(clubId),
            listAllProducts().catch(() => []),
            getClubModules(clubId, productId),
        ])
            .then(([clubData, allProducts, modulesData]) => {
                if (cancelled) return;
                setClub(clubData);
                setProduct(allProducts.find((p) => p.id === productId) ?? null);
                setClubModulesState(modulesData);
                setBaseLoaded(true);
            })
            .catch((err) => !cancelled && setError(errorText(err, "Failed to load club")))
            .finally(() => !cancelled && setLoading(false));
        return () => {
            cancelled = true;
        };
    }, [clubId, productId]);

    useEffect(() => {
        if (!canList) return undefined;
        let cancelled = false;
        Promise.all([
            isGlobal
                ? listGlobalTemplates(clubId, productId)
                : listTemplates(clubId, productId, activeCode ?? undefined),
            activeCode ? listTemplateTypes(productId, activeCode) : Promise.resolve([]),
        ])
            .then(([templateList, typeList]) => {
                if (cancelled) return;
                setModuleData({
                    key: viewKey,
                    templates: templateList,
                    types: typeList,
                });
            })
            .catch((err) => {
                if (cancelled) return;
                setError(errorText(err, "Failed to load templates"));
                setModuleData({ key: viewKey, templates: [], types: [] });
            });
        return () => {
            cancelled = true;
        };
    }, [canList, viewKey, isGlobal, activeCode, clubId, productId]);

    const reload = () => setReloadCount((n) => n + 1);

    function selectView(params) {
        setEditor(null);
        setMessage(null);
        setError(null);
        setSearchParams(params);
    }

    const selectModule = (code) => selectView({ module: code });
    const selectGlobal = () => selectView({ scope: "global" });

    // Free-form templates have no fixed variables; offer every variable the module knows as optional.
    function moduleVariables() {
        const names = [...new Set(types.flatMap((t) => t.variables.map((v) => v.name)))];
        return names.map((name) => ({ name, required: false }));
    }

    function openEditor(editorState) {
        setMessage(null);
        setError(null);
        setEditor(editorState);
    }

    function openCreateFromType(type) {
        const emailDefault = type.defaults.find((d) => d.channel === "EMAIL");
        openEditor({
            mode: "create",
            title: `Create "${type.name}"`,
            templateKey: type.key,
            variables: type.variables,
            strict: true,
            initial: { name: type.name, subject: emailDefault?.subject ?? "", body: emailDefault?.body ?? "" },
        });
    }

    function openCreateFreeForm() {
        openEditor({
            mode: "create",
            title: "New template",
            templateKey: null,
            variables: moduleVariables(),
            strict: false,
            initial: {},
        });
    }

    function openEdit(template) {
        const type = types.find((t) => t.key === template.templateKey);
        openEditor({
            mode: "edit",
            id: template.id,
            title: `Edit "${template.name}"`,
            templateKey: template.templateKey,
            variables: type ? type.variables : moduleVariables(),
            strict: Boolean(type),
            initial: template,
        });
    }

    async function handleSave({ name, subject, body }) {
        setSaving(true);
        setError(null);
        setMessage(null);
        try {
            if (isGlobal) {
                if (editor.mode === "edit") {
                    await updateGlobalTemplate(clubId, productId, editor.id, { name, subject, body });
                    setMessage("Template updated");
                } else {
                    await createGlobalTemplate({ clubId, productId, name, subject, body });
                    setMessage("Template created");
                }
            } else if (editor.mode === "edit") {
                await updateTemplate(clubId, productId, editor.id, { name, subject, body }, activeCode ?? undefined);
                setMessage("Template updated");
            } else {
                await createTemplate({
                    clubId,
                    productId,
                    moduleCode: activeCode ?? undefined,
                    templateKey: editor.templateKey ?? undefined,
                    name,
                    subject,
                    body,
                });
                setMessage("Template created");
            }
            setEditor(null);
            reload();
        } catch (err) {
            setError(errorText(err, "Failed to save template"));
        } finally {
            setSaving(false);
        }
    }

    async function handleDelete() {
        const template = pendingDelete;
        setPendingDelete(null);
        setError(null);
        try {
            if (isGlobal) {
                await deleteGlobalTemplate(clubId, productId, template.id);
            } else {
                await deleteTemplate(clubId, productId, template.id, activeCode ?? undefined);
            }
            setMessage(`Deleted "${template.name}"`);
            if (editor?.id === template.id) setEditor(null);
            reload();
        } catch (err) {
            setError(errorText(err, "Failed to delete template"));
        }
    }

    async function handleSaveModules(codes) {
        setSavingModules(true);
        setError(null);
        setMessage(null);
        try {
            const updated = await setClubModules(clubId, productId, codes);
            setClubModulesState(updated);
            setManagingModules(false);
            setMessage("Modules updated");
        } catch (err) {
            setError(errorText(err, "Failed to update modules"));
        } finally {
            setSavingModules(false);
        }
    }

    const keyedTemplates = new Map(templates.filter((t) => t.templateKey).map((t) => [t.templateKey, t]));
    const otherTemplates = templates.filter((t) => !t.templateKey || !types.some((ty) => ty.key === t.templateKey));

    return (
        <div className="min-h-screen bg-gray-50">
            <header className="flex flex-wrap items-center justify-between gap-3 px-4 sm:px-8 py-4 bg-white border-b border-gray-200 shadow-sm">
                <div className="flex items-center gap-3 min-w-0">
                    <Link to="/dashboard" className="text-gray-500 hover:text-gray-800" title="Home">
                        <Home className="w-5 h-5" />
                    </Link>
                    <Link
                        to={`/clubs/${clubId}/products/${productId}/test-email`}
                        className="text-gray-500 hover:text-gray-800"
                        title="Send test email"
                    >
                        <ArrowLeft className="w-5 h-5" />
                    </Link>
                    <h1 className="text-lg font-bold text-gray-800 truncate">
                        Email Templates{club ? ` — ${club.name}` : ""}
                        {product ? ` / ${product.name}` : ""}
                    </h1>
                </div>
                {isOrgAdmin && clubModules.supportsModules && !loading && (
                    <button
                        onClick={() => setManagingModules((v) => !v)}
                        className="flex items-center gap-1.5 px-4 py-2 text-sm font-semibold text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
                    >
                        <Settings2 className="w-4 h-4" /> Manage modules
                    </button>
                )}
            </header>

            <main className="max-w-6xl mx-auto px-4 sm:px-6 py-8">
                {error && (
                    <div className="flex items-center gap-2 px-4 py-3 bg-red-50 text-red-600 rounded-xl text-sm mb-5">
                        <AlertTriangle className="w-4 h-4 shrink-0" /> {error}
                    </div>
                )}
                {message && (
                    <div className="flex items-center gap-2 px-4 py-3 bg-emerald-50 text-emerald-600 rounded-xl text-sm mb-5">
                        <CheckCircle2 className="w-4 h-4 shrink-0" /> {message}
                    </div>
                )}

                {loading ? (
                    <div className="flex items-center gap-2 text-sm text-gray-500 py-8">
                        <Loader2 className="w-4 h-4 animate-spin" /> Loading...
                    </div>
                ) : !baseLoaded ? null : (
                    <>
                        {managingModules && (
                            <ModuleManager
                                modules={clubModules.modules}
                                saving={savingModules}
                                onSave={handleSaveModules}
                                onCancel={() => setManagingModules(false)}
                            />
                        )}

                        {!clubModules.supportsModules && (
                            <div className="flex items-start gap-2 px-4 py-3 bg-blue-50 text-blue-700 rounded-xl text-sm mb-5">
                                <Info className="w-4 h-4 mt-0.5 shrink-0" />
                                This club was set up before modules, so its templates are shared across the product.
                                Modules are available for clubs created from now on.
                            </div>
                        )}

                        {clubModules.supportsModules && enabledModules.length === 0 && (
                            <section className="bg-white border border-gray-200 rounded-2xl p-8 shadow-sm text-center">
                                <Blocks className="w-8 h-8 text-indigo-300 mx-auto mb-3" />
                                <p className="text-sm font-semibold text-gray-800">No modules are switched on for this club</p>
                                <p className="text-sm text-gray-500 mt-1">
                                    {isOrgAdmin
                                        ? "Switch on a module (e.g. Membership) to get its templates."
                                        : "Ask an organisation admin to switch on the modules this club uses."}
                                </p>
                                {isOrgAdmin && !managingModules && (
                                    <button onClick={() => setManagingModules(true)} className="btn-primary mx-auto mt-4">
                                        <Settings2 className="w-4 h-4" /> Manage modules
                                    </button>
                                )}
                            </section>
                        )}

                        {clubModules.supportsModules && (
                            <nav className="flex gap-1 overflow-x-auto overflow-y-hidden border-b border-gray-200 mb-6" aria-label="Modules">
                                {enabledModules.map((m) => (
                                    <TabButton key={m.code} active={m.code === activeCode} onClick={() => selectModule(m.code)}>
                                        {m.name}
                                    </TabButton>
                                ))}
                                <TabButton active={isGlobal} onClick={selectGlobal}>
                                    <Globe className="w-4 h-4" /> Global
                                </TabButton>
                            </nav>
                        )}

                        {editor && (
                            <TemplateEditor
                                key={editor.id ?? editor.templateKey ?? "new"}
                                title={editor.title}
                                initial={editor.initial}
                                variables={editor.variables}
                                strict={editor.strict}
                                contextLabel={
                                    isGlobal
                                        ? "Global · all products"
                                        : [activeModule?.name, editor.templateKey].filter(Boolean).join(" · ")
                                }
                                saving={saving}
                                onSave={handleSave}
                                onCancel={() => setEditor(null)}
                            />
                        )}

                        {(!clubModules.supportsModules || activeCode || isGlobal) && (
                            <div className="flex flex-col gap-6">
                                {activeCode && (
                                    <section className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm">
                                        <div className="mb-4">
                                            <h2 className="text-base font-bold text-gray-800">
                                                {activeModule.name} templates
                                            </h2>
                                            <p className="text-sm text-gray-500">
                                                The emails the {activeModule.name.toLowerCase()} module sends. Each one
                                                starts from MiComm's default and can be edited for this club.
                                            </p>
                                        </div>
                                        {loadingModule ? (
                                            <div className="flex items-center gap-2 text-sm text-gray-500 py-2">
                                                <Loader2 className="w-4 h-4 animate-spin" /> Loading...
                                            </div>
                                        ) : types.length === 0 ? (
                                            <p className="text-sm text-gray-500">This module defines no templates.</p>
                                        ) : (
                                            <div className="flex flex-col gap-3">
                                                {types.map((type) => {
                                                    const template = keyedTemplates.get(type.key);
                                                    return (
                                                        <div
                                                            key={type.key}
                                                            className="flex flex-wrap items-center justify-between gap-3 p-4 border border-gray-100 rounded-xl"
                                                        >
                                                            <div className="min-w-0">
                                                                <p className="flex items-center gap-2 text-sm font-semibold text-gray-800">
                                                                    {template ? (
                                                                        <CheckCircle2 className="w-4 h-4 text-emerald-500 shrink-0" />
                                                                    ) : (
                                                                        <CircleDashed className="w-4 h-4 text-amber-500 shrink-0" />
                                                                    )}
                                                                    {type.name}
                                                                    <span className="font-mono text-xs font-normal text-gray-400">
                                                                        {type.key}
                                                                    </span>
                                                                </p>
                                                                <p className="text-xs text-gray-500 mt-0.5">
                                                                    {template
                                                                        ? `${template.subject} · updated ${formatDate(template.updatedAt)}`
                                                                        : (type.description ?? "Not set up for this club")}
                                                                </p>
                                                                {type.variables.length > 0 && (
                                                                    <p className="text-xs text-gray-400 font-mono mt-1 break-words">
                                                                        {type.variables.map((v) => `{{${v.name}}}`).join(" ")}
                                                                    </p>
                                                                )}
                                                            </div>
                                                            {template ? (
                                                                <div className="flex gap-1">
                                                                    <IconButton title="Edit" onClick={() => openEdit(template)}>
                                                                        <Pencil className="w-4 h-4" />
                                                                    </IconButton>
                                                                    <IconButton
                                                                        title="Delete"
                                                                        danger
                                                                        onClick={() => setPendingDelete(template)}
                                                                    >
                                                                        <Trash2 className="w-4 h-4" />
                                                                    </IconButton>
                                                                </div>
                                                            ) : (
                                                                <button
                                                                    onClick={() => openCreateFromType(type)}
                                                                    className="flex items-center gap-1.5 px-3 py-1.5 text-sm font-semibold text-indigo-600 bg-indigo-50 rounded-lg hover:bg-indigo-100"
                                                                >
                                                                    <Plus className="w-4 h-4" />
                                                                    {type.defaults.some((d) => d.channel === "EMAIL")
                                                                        ? "Create from default"
                                                                        : "Create"}
                                                                </button>
                                                            )}
                                                        </div>
                                                    );
                                                })}
                                            </div>
                                        )}
                                    </section>
                                )}

                                <section className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm">
                                    <div className="flex flex-wrap items-start justify-between gap-3 mb-4">
                                        <div>
                                            <h2 className="flex items-center gap-2 text-base font-bold text-gray-800">
                                                <FileText className="w-5 h-5 text-indigo-600" />
                                                {isGlobal ? "Global templates" : activeCode ? "Other templates" : "Saved templates"}
                                            </h2>
                                            {isGlobal && (
                                                <p className="text-sm text-gray-500">
                                                    The club's own emails outside any module, available in all of its products.
                                                </p>
                                            )}
                                            {activeCode && (
                                                <p className="text-sm text-gray-500">
                                                    The club's own {activeModule.name.toLowerCase()} emails, for one-off sends.
                                                </p>
                                            )}
                                        </div>
                                        <button onClick={openCreateFreeForm} className="btn-primary !py-2">
                                            <Plus className="w-4 h-4" /> New template
                                        </button>
                                    </div>

                                    {loadingModule ? (
                                        <div className="flex items-center gap-2 text-sm text-gray-500 py-2">
                                            <Loader2 className="w-4 h-4 animate-spin" /> Loading...
                                        </div>
                                    ) : otherTemplates.length === 0 ? (
                                        <p className="text-sm text-gray-500">No templates yet.</p>
                                    ) : (
                                        <div className="flex flex-col gap-3">
                                            {otherTemplates.map((t) => (
                                                <div
                                                    key={t.id}
                                                    className="flex flex-wrap items-center justify-between gap-3 p-4 border border-gray-100 rounded-xl hover:bg-gray-50 transition-colors"
                                                >
                                                    <div className="min-w-0">
                                                        <p className="text-sm font-semibold text-gray-800">{t.name}</p>
                                                        <p className="text-xs text-gray-500 break-words">
                                                            {t.subject} · updated {formatDate(t.updatedAt)}
                                                        </p>
                                                    </div>
                                                    <div className="flex gap-1">
                                                        <IconButton title="Edit" onClick={() => openEdit(t)}>
                                                            <Pencil className="w-4 h-4" />
                                                        </IconButton>
                                                        <IconButton title="Delete" danger onClick={() => setPendingDelete(t)}>
                                                            <Trash2 className="w-4 h-4" />
                                                        </IconButton>
                                                    </div>
                                                </div>
                                            ))}
                                        </div>
                                    )}
                                </section>
                            </div>
                        )}
                    </>
                )}
            </main>

            <ConfirmDialog
                open={pendingDelete !== null}
                danger
                title="Delete template?"
                message={
                    pendingDelete?.templateKey
                        ? `"${pendingDelete?.name}" will be deleted. You can create it again from the module's default.`
                        : `"${pendingDelete?.name}" will be deleted. This cannot be undone.`
                }
                confirmLabel="Delete"
                onConfirm={handleDelete}
                onCancel={() => setPendingDelete(null)}
            />
        </div>
    );
}

function TabButton({ active, onClick, children }) {
    return (
        <button
            onClick={onClick}
            className={`flex items-center gap-1.5 px-4 py-2.5 text-sm font-semibold whitespace-nowrap border-b-2 -mb-px transition-colors ${
                active ? "border-indigo-600 text-indigo-600" : "border-transparent text-gray-500 hover:text-gray-800"
            }`}
        >
            {children}
        </button>
    );
}

function IconButton({ title, danger, onClick, children }) {
    return (
        <button
            title={title}
            aria-label={title}
            onClick={onClick}
            className={`p-2 rounded-lg text-gray-500 transition-colors ${
                danger ? "hover:text-red-600 hover:bg-red-50" : "hover:text-indigo-600 hover:bg-indigo-50"
            }`}
        >
            {children}
        </button>
    );
}
