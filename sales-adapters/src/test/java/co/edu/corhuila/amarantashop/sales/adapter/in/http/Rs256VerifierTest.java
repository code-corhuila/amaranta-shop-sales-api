package co.edu.corhuila.amarantashop.sales.adapter.in.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.corhuila.amarantashop.sales.adapter.in.http.Rs256Verifier.InvalidTokenException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Each service validates the token itself, with a closed list of algorithms (norm 5.3.7, Annex C). */
class Rs256VerifierTest {

    private static final Instant NOW = Instant.parse("2026-10-12T15:00:00Z");
    private static final String USER = "11111111-1111-4111-8111-111111111111";

    private TestTokens tokens;
    private Rs256Verifier verifier;

    @BeforeEach
    void setUp() {
        tokens = new TestTokens();
        verifier = new Rs256Verifier(tokens.publicKeyAsEnvValue());
    }

    private Map<String, Object> claims(long exp) {
        return Map.of("sub", USER, "role", "RECEPTIONIST", "exp", exp);
    }

    @Test
    void acceptsAValidTokenAndReturnsWhoItIsAndTheirRole() throws Exception {
        var principal = verifier.verify(tokens.token(claims(NOW.getEpochSecond() + 900)), NOW);

        assertThat(principal.userId()).isEqualTo(USER);
        assertThat(principal.role()).isEqualTo("RECEPTIONIST");
    }

    @Test
    void acceptsTheKeyAsItIsStoredInAnEnvFile_withLiteralBackslashN() {
        assertThat(tokens.publicKeyAsEnvValue()).contains("\\n").doesNotContain("\n");
        assertThat(verifier).isNotNull();
    }

    @Test
    void refusesAnExpiredToken() {
        assertThatThrownBy(() -> verifier.verify(tokens.token(claims(NOW.getEpochSecond() - 31)), NOW))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void toleratesAClockSkewOfThirtySeconds() throws Exception {
        assertThat(verifier.verify(tokens.token(claims(NOW.getEpochSecond() - 29)), NOW).userId()).isEqualTo(USER);
    }

    @Test
    void refusesAnAlgorithmOtherThanRs256() {
        for (String alg : new String[] {"none", "HS256", "RS512"}) {
            assertThatThrownBy(() -> verifier.verify(tokens.tokenWithAlgorithm(alg, claims(NOW.getEpochSecond() + 900)), NOW))
                    .as("alg " + alg).isInstanceOf(InvalidTokenException.class);
        }
    }

    @Test
    void refusesATokenSignedWithAnotherKey() {
        String forged = TestTokens.signedByAnotherKey(claims(NOW.getEpochSecond() + 900));

        assertThatThrownBy(() -> verifier.verify(forged, NOW)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void refusesATokenWithoutExpOrWithoutSubject() {
        assertThatThrownBy(() -> verifier.verify(tokens.token(Map.of("sub", USER)), NOW)).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> verifier.verify(tokens.token(Map.of("exp", NOW.getEpochSecond() + 900)), NOW))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void refusesWhatIsNotAToken() {
        for (String garbage : new String[] {"", "a.b", "a.b.c", "not a token at all", "....."}) {
            assertThatThrownBy(() -> verifier.verify(garbage, NOW)).as(garbage).isInstanceOf(InvalidTokenException.class);
        }
    }

    @Test
    void refusesAConfigurationThatIsNotAPublicKey() {
        assertThatThrownBy(() -> new Rs256Verifier("secret")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rs256Verifier(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
