package dev.interviewcoach.identity;

import java.io.IOException;
import java.util.regex.Pattern;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

final class LoginInputFilter extends OncePerRequestFilter {

    private static final Pattern LOGIN_NAME = Pattern.compile("[A-Za-z0-9._-]{3,64}");
    private final ObjectMapper mapper;

    LoginInputFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if ("POST".equals(request.getMethod()) && "/api/auth/login".equals(request.getServletPath())) {
            String contentType = request.getContentType();
            if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT)
                    .startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)) {
                JsonSecurityResponses.write(response, mapper, 415,
                        new JsonSecurityResponses.ErrorBody("UNSUPPORTED_MEDIA_TYPE", "请使用表单提交登录信息"));
                return;
            }
            String name = request.getParameter("username");
            String password = request.getParameter("password");
            if (name == null || name.isEmpty() || password == null || password.isEmpty()) {
                JsonSecurityResponses.write(response, mapper, 400,
                        new JsonSecurityResponses.ErrorBody("INVALID_INPUT", "请输入账号和密码"));
                return;
            }
            // Validate the raw value before Spring Security trims the username in its form filter.
            if (!LOGIN_NAME.matcher(name).matches()) {
                JsonSecurityResponses.write(response, mapper, 400,
                        new JsonSecurityResponses.ErrorBody("INVALID_INPUT", "登录名格式不正确"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
