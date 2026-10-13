package co.edu.corhuila.amarantashop.sales.adapter.in.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the bearer token of every route under /api/ and hands the caller to the controller. There is one
 * exception, decided in the spec (DP-1): a visitor may place an online order WITHOUT a token, because buying does
 * not require an account. The exception is only for the absence of a token. One that is present but invalid or
 * expired is a 401 on every route, so a broken session is never silently turned into a guest purchase (CA-C1.4).
 */
public class AuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = "auth.principal";

    private final Rs256Verifier verifier;
    private final ObjectMapper json;
    private final Clock clock;

    public AuthFilter(Rs256Verifier verifier, ObjectMapper json, Clock clock) {
        this.verifier = verifier;
        this.json = json;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null && isGuestRoute(request)) {
            chain.doFilter(request, response);
            return;
        }
        if (header == null || !header.startsWith("Bearer ") || header.length() == "Bearer ".length()) {
            reject(response, header == null ? "a bearer token is required" : "the token is invalid or has expired");
            return;
        }
        try {
            request.setAttribute(PRINCIPAL_ATTRIBUTE, verifier.verify(header.substring("Bearer ".length()), clock.instant()));
        } catch (Rs256Verifier.InvalidTokenException e) {
            reject(response, "the token is invalid or has expired");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isGuestRoute(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && request.getRequestURI().matches("/api/v1/orders/?");
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ApiError.of(ApiError.UNAUTHORIZED, message));
    }
}
