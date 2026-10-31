package dev.interviewcoach.identity;

import java.io.IOException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.core.Ordered;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.session.web.http.DefaultCookieSerializer;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class LocalSecurityConfiguration {

    @Bean
    SecurityFilterChain localSecurityFilterChain(HttpSecurity http, AccountAuthenticationProvider provider,
            JdbcAccountRepository accounts, ObjectMapper mapper) throws Exception {
        http.authenticationProvider(provider);
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness",
                        "/actuator/health/readiness", "/api/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers("/api/materials/**").authenticated()
                .requestMatchers("/api/maintenance/knowledge/**").hasAuthority("KNOWLEDGE_MAINTAINER")
                .anyRequest().denyAll());
        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, ex) -> JsonSecurityResponses.write(response,
                        mapper, 401, new JsonSecurityResponses.ErrorBody("AUTHENTICATION_REQUIRED",
                                "登录已失效，请重新登录")))
                .accessDeniedHandler((request, response, ex) -> JsonSecurityResponses.write(response,
                        mapper, 403, new JsonSecurityResponses.ErrorBody(
                                ex instanceof org.springframework.security.web.csrf.CsrfException
                                        ? "CSRF_INVALID" : "ACCESS_DENIED",
                                "请求被拒绝"))));
        http.formLogin(form -> form.loginPage("/api/auth/login")
                .loginProcessingUrl("/api/auth/login")
                .usernameParameter("username").passwordParameter("password")
                .successHandler((request, response, authentication) -> writeIdentity(response, mapper, authentication))
                .failureHandler((request, response, error) -> writeLoginFailure(response, mapper, error)));
        http.logout(logout -> logout.logoutUrl("/api/auth/logout")
                .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("SESSION")
                .logoutSuccessHandler((request, response, authentication) -> {
                    response.setStatus(204);
                    response.setHeader("Cache-Control", "no-store");
                }));
        // The SPA receives the XOR token from GET /csrf and sends it back unchanged in the named header.
        http.csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler()));
        http.requestCache(cache -> cache.disable());
        http.httpBasic(basic -> basic.disable());
        http.addFilterBefore(new LoginInputFilter(mapper), UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(new SessionIdentityFilter(accounts, mapper), AuthorizationFilter.class);
        return http.build();
    }

    private void writeIdentity(HttpServletResponse response, ObjectMapper mapper, Authentication authentication)
            throws IOException {
        AccountPrincipal principal = (AccountPrincipal) authentication.getPrincipal();
        JsonSecurityResponses.write(response, mapper, 200,
                new AuthApi.MeResponse(principal.accountId(), principal.loginName(), principal.authorities()));
    }

    private void writeLoginFailure(HttpServletResponse response, ObjectMapper mapper,
            AuthenticationException error) throws IOException {
        if (error instanceof AuthenticationServiceException) {
            JsonSecurityResponses.write(response, mapper, 503,
                    new JsonSecurityResponses.ErrorBody("SERVICE_UNAVAILABLE", "暂时无法核实登录状态"));
        } else {
            JsonSecurityResponses.write(response, mapper, 401,
                    new JsonSecurityResponses.ErrorBody("INVALID_CREDENTIALS", "账号或密码不正确"));
        }
    }

    @Bean
    DefaultCookieSerializer sessionCookieSerializer() {
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName("SESSION");
        cookie.setCookiePath("/");
        cookie.setSameSite("Lax");
        cookie.setUseHttpOnlyCookie(true);
        // Local profile is loopback HTTP; deployment must turn Secure on behind HTTPS.
        cookie.setUseSecureCookie(false);
        return cookie;
    }

    @Bean
    FilterRegistrationBean<SessionDatabaseFailureFilter> sessionDatabaseFailureBoundary(ObjectMapper mapper) {
        var registration = new FilterRegistrationBean<>(new SessionDatabaseFailureFilter(mapper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
