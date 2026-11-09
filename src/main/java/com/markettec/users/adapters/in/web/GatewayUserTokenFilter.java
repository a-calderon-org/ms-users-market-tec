package com.markettec.users.adapters.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gateway token relocation (MTEC-12/D04). Behind Container Apps authentication, {@code Authorization} carries
 * the APIM managed-identity token that the platform already validated; APIM moves the internal user JWT to
 * {@link #USER_TOKEN_HEADER} before replacing it. With this filter the application sees only the relocated
 * values as {@code Authorization} (or none), so the gateway token can never be read as a user credential.
 * The relocated header is not trusted by itself: every value is still verified by the internal JWT verifier.
 */
public final class GatewayUserTokenFilter extends OncePerRequestFilter {
    public static final String USER_TOKEN_HEADER = "X-MTEC-User-Authorization";
    private static final String AUTHORIZATION = "Authorization";

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        chain.doFilter(new Relocated(request), response);
    }

    private static final class Relocated extends HttpServletRequestWrapper {
        private final List<String> userTokens;

        Relocated(HttpServletRequest request) {
            super(request);
            userTokens = Collections.list(request.getHeaders(USER_TOKEN_HEADER));
        }

        private static boolean hidden(String name) {
            return AUTHORIZATION.equalsIgnoreCase(name) || USER_TOKEN_HEADER.equalsIgnoreCase(name);
        }

        @Override public String getHeader(String name) {
            if (AUTHORIZATION.equalsIgnoreCase(name)) return userTokens.isEmpty() ? null : userTokens.get(0);
            return USER_TOKEN_HEADER.equalsIgnoreCase(name) ? null : super.getHeader(name);
        }

        @Override public Enumeration<String> getHeaders(String name) {
            if (AUTHORIZATION.equalsIgnoreCase(name)) return Collections.enumeration(userTokens);
            return USER_TOKEN_HEADER.equalsIgnoreCase(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override public Enumeration<String> getHeaderNames() {
            var names = new ArrayList<String>();
            for (var it = super.getHeaderNames(); it.hasMoreElements(); ) {
                String name = it.nextElement();
                if (!hidden(name)) names.add(name);
            }
            if (!userTokens.isEmpty()) names.add(AUTHORIZATION);
            return Collections.enumeration(names);
        }
    }
}
