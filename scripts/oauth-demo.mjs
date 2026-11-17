#!/usr/bin/env node
/**
 * Walks through MiComm's app sign-in (OAuth 2.0 client credentials with private key JWT) and
 * then calls the email API with the token it gets. No dependencies: Node 18+ only.
 *
 *   node scripts/oauth-demo.mjs --client-id mcc_xxx --key private.pem \
 *        --club <clubId> --product <productId> [--send-to you@example.com --from sender@club.com]
 *
 * Without --send-to it only reads the latest send (needs the email:read permission).
 * With --send-to it sends one test email (needs email:send).
 */
import { createPrivateKey, randomUUID, sign } from "node:crypto";
import { readFileSync } from "node:fs";

const args = Object.fromEntries(
    process.argv.slice(2).reduce((pairs, arg, i, all) => (arg.startsWith("--") ? [...pairs, [arg.slice(2), all[i + 1]]] : pairs), []),
);
const TOKEN_URL = args["token-url"] ?? "http://localhost:8081/oauth/token";
const EMAIL_API = args["email-api"] ?? "http://localhost:8082/api/v1";
// --assertion-only: just print a signed assertion (valid 5 minutes) to paste into Postman.
const ASSERTION_ONLY = process.argv.includes("--assertion-only");
for (const required of ASSERTION_ONLY ? ["client-id", "key"] : ["client-id", "key", "club", "product"]) {
    if (!args[required]) {
        console.error(`Missing --${required}. See the comment at the top of this file.`);
        process.exit(1);
    }
}

const b64url = (data) => Buffer.from(data).toString("base64url");

// ── Step 1: write the note (the "client assertion") ──────────────────────────────────────────
const privateKey = createPrivateKey(readFileSync(args.key));
const isEc = privateKey.asymmetricKeyType === "ec";
const alg = isEc ? "ES256" : "RS256";
const now = Math.floor(Date.now() / 1000);
const header = { alg, typ: "JWT" };
const claims = {
    iss: args["client-id"], // who I am
    sub: args["client-id"], // ...and who this is about (same)
    aud: TOKEN_URL, // only valid at MiComm's token desk
    iat: now, // written now
    exp: now + (ASSERTION_ONLY ? 290 : 60), // valid 60 seconds (just under 5 minutes when pasted by hand)
    jti: randomUUID(), // note number: usable once
};
if (!ASSERTION_ONLY) {
    console.log("\n1. Note to sign (client assertion):");
    console.log(JSON.stringify(claims, null, 2));
}

// ── Step 2: stamp it with the private key (the private key never leaves this machine) ────────
const signingInput = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(claims))}`;
// JWT ES256 needs the raw r||s signature form ("ieee-p1363"); RS256 uses the default.
const signature = sign("sha256", Buffer.from(signingInput), isEc ? { key: privateKey, dsaEncoding: "ieee-p1363" } : privateKey);
const assertion = `${signingInput}.${b64url(signature)}`;
if (ASSERTION_ONLY) {
    console.log("\nclient_assertion (use within 5 minutes, once):\n");
    console.log(assertion + "\n");
    process.exit(0);
}
console.log(`\n2. Signed with ${alg}. Only the signed note is sent, never the key:\n${assertion.slice(0, 60)}...`);

// ── Step 3-4: hand the note to MiComm, get a 15-minute pass ─────────────────────────────────
const tokenResponse = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
        grant_type: "client_credentials",
        client_assertion_type: "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
        client_assertion: assertion,
    }),
});
const token = await tokenResponse.json();
if (!tokenResponse.ok) {
    console.error(`\n3. MiComm refused (${tokenResponse.status}):`, token);
    process.exit(1);
}
console.log(`\n3. MiComm checked the signature and returned a token:`);
console.log(`   access_token: ${token.access_token.slice(0, 40)}...`);
console.log(`   expires_in:   ${token.expires_in} seconds`);
console.log(`   scope:        ${token.scope}`);

// Show the same note can't be used twice.
const replay = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
        grant_type: "client_credentials",
        client_assertion_type: "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
        client_assertion: assertion,
    }),
});
console.log(`\n4. Sending the same signed note again is refused (${replay.status}):`, (await replay.json()).error_description);

// ── Step 5-6: use the pass with the email API ───────────────────────────────────────────────
const auth = { Authorization: `Bearer ${token.access_token}` };
if (args["send-to"]) {
    const send = await fetch(`${EMAIL_API}/emails/send-bulk`, {
        method: "POST",
        headers: { ...auth, "Content-Type": "application/json", "Idempotency-Key": randomUUID() },
        body: JSON.stringify({
            clubId: args.club,
            productId: args.product,
            fromEmail: args.from,
            subject: "MiComm OAuth test",
            htmlBody: "<p>Hello {{name}}, this email was sent by an app signed in with its private key.</p>",
            recipients: [{ email: args["send-to"], variables: { name: "there" } }],
        }),
    });
    console.log(`\n5. Send with the token (${send.status}):`, JSON.stringify(await send.json()));
} else {
    const list = await fetch(`${EMAIL_API}/notifications?clubId=${args.club}&productId=${args.product}&limit=1`, { headers: auth });
    const body = await list.json();
    console.log(`\n5. Read the latest send with the token (${list.status}):`,
        list.ok ? (body.data.items[0] ? `${body.data.items[0].subject} (${body.data.items[0].status})` : "no sends yet") : body.errorMessage);
}

// An app token can't touch club settings: tenant-management ignores it.
const settings = await fetch(`${TOKEN_URL.replace("/oauth/token", "")}/clubs/${args.club}/api-keys`, { headers: auth });
console.log(`\n6. The same token can't manage the club (API keys list → ${settings.status}). Apps can only use the email API.\n`);
