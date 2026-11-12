package co.edu.corhuila.amarantashop.sales.adapter.in.http;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

/** Signs tokens for the tests with a key pair made on the spot: no key file ever enters the repository. */
final class TestTokens {

    private final KeyPair pair;

    TestTokens() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            pair = generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The public key as a PEM in a single line, with the \n escapes a .env file stores. */
    String publicKeyAsEnvValue() {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPublic().getEncoded());
        return ("-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----").replace("\n", "\\n");
    }

    String token(Map<String, Object> claims) {
        return sign("{\"alg\":\"RS256\",\"typ\":\"JWT\"}", claims, pair.getPrivate());
    }

    String tokenWithAlgorithm(String alg, Map<String, Object> claims) {
        return sign("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}", claims, pair.getPrivate());
    }

    /** A token signed by a key the service does not trust. */
    static String signedByAnotherKey(Map<String, Object> claims) {
        return new TestTokens().token(claims);
    }

    private static String sign(String header, Map<String, Object> claims, PrivateKey key) {
        try {
            StringBuilder body = new StringBuilder("{");
            claims.forEach((k, v) -> body.append(body.length() > 1 ? "," : "").append('"').append(k).append("\":")
                    .append(v instanceof Number ? v.toString() : "\"" + v + "\""));
            String signingInput = b64(header) + "." + b64(body.append('}').toString());
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(key);
            rsa.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(rsa.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
