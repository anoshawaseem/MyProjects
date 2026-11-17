# MiComm messaging API – integration brief

Use this to integrate an application with MiComm, which sends email (and SMS / WhatsApp) on
behalf of a club's product. Authentication is OAuth 2.0 client credentials with a private key
JWT (RFC 7523). No shared secret exists: the app signs a short-lived assertion with its private
key, exchanges it for a 15-minute access token and calls the API with that token.

## 1. Values the app needs (configuration, never hard-coded)

| Setting | Example (local dev) | Notes |
|---|---|---|
| `MICOMM_TOKEN_URL` | `http://localhost:8081/oauth/token` | Must exactly equal the assertion's `aud` claim. |
| `MICOMM_API_BASE_URL` | `http://localhost:8082` | Messaging API. |
| `MICOMM_CLIENT_ID` | `mcc_2nznk5efmiig3j8n` | From the club's **Apps** page in the MiComm portal. |
| `MICOMM_PRIVATE_KEY` | path to `private.pem` or its PEM text | EC P-256 (ES256) or RSA 2048+ (RS256). Secret: load from a secret store / env, never commit. |
| `MICOMM_CLUB_ID` | `04888ec2-d26f-4d4b-93a8-0334f0da3de9` | The app is bound to this one club. |
| `MICOMM_PRODUCT_ID` | `f721333c-d2ff-4276-a09e-0e8a573d562d` | The club's product the messages are sent for. |

The matching public key is registered in the portal when the app is created, together with its
scopes: `email:send`, `sms:send`, `whatsapp:send`, `email:read`, `templates:read`,
`templates:write`.

## 2. Getting an access token

### 2.1 Build the client assertion (a JWT signed with the private key)

Header: `{"alg": "ES256", "typ": "JWT"}` (or `RS256` for an RSA key; must match the key registered).

Claims:

| Claim | Value |
|---|---|
| `iss` | client_id |
| `sub` | client_id |
| `aud` | the token URL, exactly (`http://localhost:8081/oauth/token`) |
| `iat` | now (seconds) |
| `exp` | now + 60 s (max 5 minutes after `iat`) |
| `jti` | a new random UUID every time (each is accepted only once) |

ES256 signatures must be the raw 64-byte `r||s` form (JOSE), not DER. Standard JWT libraries
(jose, jjwt, nimbus, PyJWT, System.IdentityModel.Tokens.Jwt) do this already.

### 2.2 Exchange it

```
POST {MICOMM_TOKEN_URL}
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
&client_assertion_type=urn%3Aietf%3Aparams%3Aoauth%3Aclient-assertion-type%3Ajwt-bearer
&client_assertion=<signed JWT>
&scope=email:send email:read          (optional; default = all scopes the app has)
```

Success (200):
```json
{ "access_token": "eyJ...", "token_type": "Bearer", "expires_in": 900, "scope": "email:send email:read" }
```
Error (400 / 401): `{ "error": "invalid_client", "error_description": "..." }`.

### 2.3 Token handling rules

- Cache the token in memory; reuse it until ~60 s before `expires_in` runs out, then fetch a new
  one with a **new** assertion (new `jti`). Do not request a token per API call.
- If an API call returns 401/403 with an empty body, the token is missing or expired: refresh
  once and retry once.
- Keep the clock in sync (assertion timing allows 30 s skew).

## 3. Calling the API

All requests: `Authorization: Bearer <access_token>`, `Content-Type: application/json`.

Every response is wrapped:
```json
{ "success": true, "data": { ... }, "errorMessage": null }
{ "success": false, "data": null, "errorMessage": "human-readable reason" }
```

`clubId` in every request must be the app's own club, or the call is refused (403
"Access denied to club ...").

### 3.1 Idempotency (send endpoints)

Send `Idempotency-Key: <unique key per logical message>` (1–255 printable ASCII, no spaces; e.g.
`order-1234-confirmation` or a UUID stored with the business event). Retrying with the same key
and same body returns the original answer with header `Idempotent-Replayed: true` and sends
nothing again. Same key with a different body → 422. Key still in progress, or earlier attempt
only partly queued → 409. Always retry network failures / 503 with the **same** key.

### 3.2 Send email – `POST /api/v1/emails/send-bulk` (scope `email:send`)

Direct content:
```json
{
  "clubId": "…", "productId": "…",
  "fromEmail": "noreply@club.com",
  "subject": "Your booking {{bookingRef}}",
  "htmlBody": "<p>Hi {{memberName}}, see you at {{clubName}}.</p>",
  "type": "TRANSACTIONAL",
  "variables": { "bookingRef": "B-1001" },
  "recipients": [
    { "email": "member@example.com", "variables": { "memberName": "Ann" } },
    "plain@example.com"
  ]
}
```

Template instead of subject/htmlBody: `"templateId": "<uuid>"` or
`"moduleCode": "MEMBERSHIP", "templateKey": "WELCOME"`.

Rules:
- `fromEmail` required; `recipients` 1–5000 (string or `{email, variables}`).
- `type`: `TRANSACTIONAL` (default) or `CAMPAIGN`.
- Merge fields `{{name}}`: values come from (lowest → highest priority) MiComm's own
  `clubName` / `productName`, the send-level `variables`, then each recipient's `variables`.
  Templates are always merged; a direct send is merged only when any variables are given. Every
  placeholder needs a value for every recipient, otherwise 400 naming the recipients.
- Values are HTML-escaped into the HTML body.

Response 202:
```json
{ "success": true, "data": { "requestId": "uuid", "status": "QUEUED", "recipientCount": 2 } }
```
Sending is asynchronous; `requestId` is used to read the result (3.4).

### 3.3 Send one message on several channels – `POST /api/v1/notifications/send`

Needs the scope for each channel used (`email:send`, `sms:send`, `whatsapp:send`). Only clubs
created on the newer schema support it (otherwise 400 – use send-bulk for email).

```json
{
  "clubId": "…", "productId": "…",
  "channels": ["EMAIL", "SMS", "WHATSAPP"],
  "type": "CAMPAIGN",
  "moduleCode": "EVENTS",
  "title": "Regatta on {{day}}",
  "body": "Hi {{memberName}},\nboats launch at 9am.",
  "email":    { "fromEmail": "optional", "subject": "optional override", "html": "optional override" },
  "sms":      { "text": "optional override (max 1600 chars)" },
  "whatsapp": { "text": "optional override" },
  "variables": { "day": "Saturday" },
  "recipients": [
    { "email": "a@example.com", "phone": "+447700900001", "whatsapp": "+447700900001",
      "variables": { "memberName": "Ann" } }
  ]
}
```

- Content: EMAIL subject = `email.subject` else `title`; HTML = `email.html` else `body` (escaped,
  line breaks kept); `fromEmail` defaults to the club's email provider address. SMS / WhatsApp
  text = override else `title` + newline + `body`.
- Recipients: give whatever addresses each person has (phones in E.164, e.g. `+447700900001`).
  Each channel goes to the people with an address for it; WhatsApp falls back to `phone`. Each
  person needs an address on at least one requested channel.
- `PUSH` is not available yet.

Response 202:
```json
{ "success": true, "data": { "requestId": "uuid", "status": "QUEUED", "recipientCount": 1,
  "channels": { "EMAIL": 1, "SMS": 1, "WHATSAPP": 1 } } }
```

### 3.4 Read results (scope `email:read`)

- `GET /api/v1/notifications/{requestId}?clubId=…&productId=…` – the send (one entry per
  channel): `id, productId, type, channel, sender, subject, body, htmlBody, status,
  recipientCount, successCount, failureCount, sentAt, createdAt`.
- `GET /api/v1/notifications/{requestId}/recipients?clubId=…&productId=…[&failedOnly=true][&limit=50][&cursor=…]`
  – per recipient: `id, channel, address, status, providerMessageId, errorMessage, sentAt,
  deliveredAt, createdAt`.
- `GET /api/v1/notifications?clubId=…&productId=…[&channel=EMAIL][&module=…][&from=ISO][&to=ISO][&limit=50][&cursor=…]`
  – newest first; default range last 30 days (max 366); limit max 200.

Paged responses: `data = { "items": [...], "nextCursor": "…" | null }`; pass `cursor=nextCursor`
for the next page.

Status values: `PENDING`, `SENDING` → final `SENT`, `PARTIAL` (some failed) or `FAILED`.
Results usually arrive within seconds; poll with backoff (e.g. 2 s, 5 s, 15 s), not in a tight loop.

### 3.5 Templates (optional)

`GET /api/v1/emails/templates?clubId=…&productId=…` (scope `templates:read`) lists the
templates usable with `templateId` / `moduleCode` + `templateKey`. Create / update / delete need
`templates:write`.

## 4. Errors and what the app should do

| Status | Meaning | Action |
|---|---|---|
| 400 | Invalid request (message says what: missing field, merge field, no provider, bad channel) | Fix the request; do not retry as-is. |
| 401 / 403, empty body | No / expired / invalid token | Get a new token, retry once. |
| 403 with message | Wrong club, or missing scope ("cannot send SMS; it needs the scope(s) sms:send") | Configuration problem; do not retry. |
| 409 | Idempotency key still in progress / partly queued earlier | Retry later with the same key, or check the earlier send. |
| 422 | Idempotency key reused with a different body | Use a new key for a new message. |
| 424 | Club / provider could not be resolved | Retry later; alert if persistent. |
| 503 | Queue unavailable, or the send was only partly queued | Retry with backoff and the **same** Idempotency-Key. |
| 5xx | Server error | Retry with backoff and the same key. |

## 5. Implementation checklist

1. Config values from section 1 via env / secret store; private key never logged or committed.
2. A `MicommTokenProvider`: builds and signs the assertion, calls the token URL, caches the token
   until shortly before expiry, thread-safe, refreshes once on 401/403-empty.
3. A `MicommClient` with `sendEmail(...)`, `sendNotification(...)`, `getNotification(id)`,
   `getRecipients(id, cursor)`; always sends `clubId` / `productId` from config.
4. Idempotency key derived from the business event (e.g. `booking-{id}-confirmation`) and reused
   on every retry of that event.
5. Retries with exponential backoff for 503 / 5xx / network errors only.
6. Never log recipient lists or message bodies at info level; log `requestId`.
7. Tests against a stubbed MiComm (token endpoint + send endpoint), including token expiry and
   idempotent retry.
