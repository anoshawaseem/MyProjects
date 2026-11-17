import emailApiClient from "./emailApiClient";

/** One message on one or more channels (POST /notifications/send). */
export async function sendNotification(payload) {
    const response = await emailApiClient.post("/notifications/send", payload);
    if (!response.data.success) {
        throw new Error(response.data.errorMessage ?? "Failed to send");
    }
    return response.data.data;
}

/** The recipients of a send with their per-address status ({ items, nextCursor }). */
export async function listNotificationRecipients(clubId, productId, notificationId) {
    const response = await emailApiClient.get(`/notifications/${notificationId}/recipients`, {
        params: { clubId, productId },
    });
    if (!response.data.success) {
        throw new Error(response.data.errorMessage ?? "Failed to load recipients");
    }
    return response.data.data;
}
