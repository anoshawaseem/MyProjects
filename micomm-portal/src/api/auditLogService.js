import apiClient from "./apiClient";

/**
 * One page of a club's audit log, newest first: { items, nextCursor }.
 * filters: { productId, action, from, to } (from/to are ISO date-times); pass nextCursor back as cursor.
 */
export async function listAuditLogs(clubId, filters = {}, cursor) {
    const response = await apiClient.get(`/clubs/${clubId}/audit-logs`, {
        params: { ...filters, cursor, limit: 50 },
    });
    if (!response.data.success) {
        throw new Error(response.data.errorMessage ?? "Failed to load audit log");
    }
    return response.data.data;
}
