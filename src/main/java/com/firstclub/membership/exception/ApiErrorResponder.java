package com.firstclub.membership.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes error responses straight to the servlet output (used by the security entry point /
 * access-denied handler and the rate-limit filter) in the SAME JSON shape that
 * {@link GlobalExceptionHandler.ErrorResponse} produces for controller-layer errors, so clients
 * parse one consistent contract everywhere.
 */
@Component
@RequiredArgsConstructor
public class ApiErrorResponder {

    private final ObjectMapper objectMapper;

    public void write(HttpServletResponse response, int status, String errorCode, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("errorCode", errorCode);
        body.put("httpStatus", status);
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("validationErrors", null);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
