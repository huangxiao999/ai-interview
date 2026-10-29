package dev.interviewcoach.identity;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

final class SessionDatabaseFailureFilter extends OncePerRequestFilter {

    private final ObjectMapper mapper;

    SessionDatabaseFailureFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException ex) {
            if (!containsDataAccessFailure(ex) || response.isCommitted()) {
                throw ex;
            }
            unavailable(response);
        } catch (RuntimeException ex) {
            if (!containsDataAccessFailure(ex) || response.isCommitted()) {
                throw ex;
            }
            unavailable(response);
        }
    }

    private boolean containsDataAccessFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessException) {
                return true;
            }
        }
        return false;
    }

    private void unavailable(HttpServletResponse response) throws IOException {
        // Session JDBC can fail before Spring Security reaches its own exception handlers.
        JsonSecurityResponses.write(response, mapper, 503,
                new JsonSecurityResponses.ErrorBody("SERVICE_UNAVAILABLE", "暂时无法核实登录状态"));
    }
}
