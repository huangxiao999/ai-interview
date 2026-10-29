package dev.interviewcoach.identity;

import java.io.IOException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

final class JsonSecurityResponses {

    private JsonSecurityResponses() {
    }

    static void write(HttpServletResponse response, ObjectMapper mapper, int status, Object body)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getWriter(), body);
    }

    record ErrorBody(String code, String message) {
    }
}
