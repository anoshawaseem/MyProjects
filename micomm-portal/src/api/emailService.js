import emailApiClient from "./emailApiClient";

export async function sendBulkEmail(payload) {
    const response = await emailApiClient.post("/emails/send-bulk", payload);
    if (!response.data.success) {
        throw new Error(response.data.errorMessage ?? "Failed to send email");
    }
    return response.data.data;
}