package dev.interviewcoach.identity;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

final class SessionIdentityFilter extends OncePerRequestFilter {

    private final JdbcAccountRepository accounts;
    private final ObjectMapper mapper;

    SessionIdentityFilter(JdbcAccountRepository accounts, ObjectMapper mapper) {
        this.accounts = accounts;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AccountPrincipal principal) {
            try {
                var current = accounts.findById(principal.accountId());
                if (current.isEmpty() || current.get().credentialVersion() != principal.credentialVersion()
                        || !current.get().authorities().equals(principal.authorities())) {
                    // A late Session JDBC save cannot restore authority after a credential-version change.
                    SecurityContextHolder.clearContext();
                    var session = request.getSession(false);
                    if (session != null) {
                        session.invalidate();
                    }
                    JsonSecurityResponses.write(response, mapper, 401,
                            new JsonSecurityResponses.ErrorBody("AUTHENTICATION_REQUIRED", "登录已失效，请重新登录"));
                    return;
                }
            } catch (DataAccessException | IllegalArgumentException ex) {
                JsonSecurityResponses.write(response, mapper, 503,
                        new JsonSecurityResponses.ErrorBody("SERVICE_UNAVAILABLE", "暂时无法核实登录状态"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
