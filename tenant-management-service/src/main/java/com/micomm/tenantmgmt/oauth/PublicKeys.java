package com.micomm.tenantmgmt.oauth;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Reads applications' public keys. Accepts X.509 "-----BEGIN PUBLIC KEY-----" PEM for RSA keys
 * of at least 2048 bits (signing with RS256) or EC keys on P-256 (ES256); anything weaker or of
 * another kind is refused, so a client can never fall back to a weak or shared-secret algorithm.
 */
final class PublicKeys {

    static final String RS256 = "RS256";
    static final String ES256 = "ES256";

    private static final int MIN_RSA_BITS = 2048;

    /** A parsed key and the one algorithm its signatures are accepted with. */
    record ParsedKey(PublicKey key, String algorithm) {
    }

    private PublicKeys() {
    }

    static ParsedKey parse(String pem) {
        if (pem == null || !pem.contains("-----BEGIN PUBLIC KEY-----")) {
            throw new IllegalArgumentException(describeWrongKey(pem));
        }
        byte[] der;
        try {
            String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Public key PEM is not valid base64");
        }

        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        try {
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(spec);
            int bits = ((RSAPublicKey) key).getModulus().bitLength();
            if (bits < MIN_RSA_BITS) {
                throw new IllegalArgumentException("RSA keys must be at least " + MIN_RSA_BITS + " bits (got " + bits + ")");
            }
            return new ParsedKey(key, RS256);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception notRsa) {
            // Not an RSA key; try EC below.
        }
        try {
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(spec);
            int fieldSize = ((ECPublicKey) key).getParams().getCurve().getField().getFieldSize();
            if (fieldSize != 256) {
                throw new IllegalArgumentException("EC keys must use the P-256 curve");
            }
            return new ParsedKey(key, ES256);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception notEc) {
            throw new IllegalArgumentException("Public key must be an RSA (2048+ bits) or EC P-256 key");
        }
    }

    /** Says what was pasted instead of a public key, and how to get the right one. */
    private static String describeWrongKey(String text) {
        String t = text == null ? "" : text;
        if (t.contains("PRIVATE KEY")) {
            return "That is a PRIVATE key. Never share it: paste the public key instead"
                    + " (openssl ec -in private.pem -pubout -out public.pem, then paste public.pem)";
        }
        if (t.contains("BEGIN RSA PUBLIC KEY")) {
            return "That RSA key is in the older PKCS#1 format; convert it with"
                    + " openssl rsa -RSAPublicKey_in -in key.pem -pubout -out public.pem and paste public.pem";
        }
        if (t.contains("CERTIFICATE")) {
            return "That is a certificate; extract its public key with"
                    + " openssl x509 -in cert.pem -pubkey -noout > public.pem and paste public.pem";
        }
        if (t.trim().startsWith("ssh-") || t.trim().startsWith("ecdsa-")) {
            return "SSH keys are not supported; create a key with"
                    + " openssl ecparam -name prime256v1 -genkey -noout -out private.pem and"
                    + " openssl ec -in private.pem -pubout -out public.pem, then paste public.pem";
        }
        return "Paste the whole public key, from -----BEGIN PUBLIC KEY----- to -----END PUBLIC KEY-----";
    }
}
