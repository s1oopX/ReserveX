package com.reservex.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** API 只提供 JSON representation；不兼容的 Accept 在认证前返回 406。 */
@Component
@Order(Integer.MIN_VALUE + 1)
public class JsonApiFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    public JsonApiFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean apiRequest = isApi(request.getRequestURI());
        boolean optionsRequest = apiRequest && HttpMethod.OPTIONS.matches(request.getMethod());
        if (apiRequest) {
            if (CorsUtils.isCorsRequest(request)) {
                reject(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN,
                        "不允许的跨域请求");
                return;
            }
            if (!acceptsJson(request.getHeader("Accept"))) {
                reject(response, HttpServletResponse.SC_NOT_ACCEPTABLE, ErrorCode.BAD_REQUEST,
                        "仅支持 application/json 响应");
                return;
            }
            if (hasBody(request) && !isJson(request.getContentType())) {
                reject(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, ErrorCode.BAD_REQUEST,
                        "请求体必须是 JSON");
                return;
            }
        }
        HttpServletResponse downstreamResponse = optionsRequest
                ? new HttpServletResponseWrapper(response) {
                    @Override
                    public void setHeader(String name, String value) {
                        if (!isEmptyAcceptPatch(name, value)) {
                            super.setHeader(name, value);
                        }
                    }

                    @Override
                    public void addHeader(String name, String value) {
                        if (!isEmptyAcceptPatch(name, value)) {
                            super.addHeader(name, value);
                        }
                    }
                }
                : response;
        chain.doFilter(request, downstreamResponse);
        if (optionsRequest) {
            String allow = downstreamResponse.getHeader(HttpHeaders.ALLOW);
            if (allow != null && Arrays.stream(allow.split(","))
                    .map(String::trim).anyMatch(HttpMethod.PATCH.name()::equals)) {
                response.setHeader(HttpHeaders.ACCEPT_PATCH, MediaType.APPLICATION_JSON_VALUE);
            }
        }
    }

    private static boolean isEmptyAcceptPatch(String name, String value) {
        return HttpHeaders.ACCEPT_PATCH.equalsIgnoreCase(name)
                && (value == null || value.isBlank());
    }

    private void reject(HttpServletResponse response, int status, ErrorCode code,
                        String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Result.fail(code, message));
    }

    private static boolean isApi(String uri) {
        return "/api".equals(uri) || uri.startsWith("/api/");
    }

    private static boolean acceptsJson(String header) {
        if (header == null || header.isBlank()) {
            return true;
        }
        try {
            int specificity = -1;
            double quality = 0;
            for (MediaType type : MediaType.parseMediaTypes(header)) {
                if (!type.isCompatibleWith(MediaType.APPLICATION_JSON)
                        || type.getParameters().entrySet().stream().anyMatch(entry ->
                        !"q".equals(entry.getKey())
                                && !("charset".equals(entry.getKey())
                                && StandardCharsets.UTF_8.name().equalsIgnoreCase(entry.getValue())))) {
                    continue;
                }
                int candidate = type.isWildcardType() ? 0
                        : type.isWildcardSubtype() ? 1 : 2;
                if (candidate > specificity) {
                    specificity = candidate;
                    quality = type.getQualityValue();
                } else if (candidate == specificity) {
                    quality = Math.max(quality, type.getQualityValue());
                }
            }
            return specificity >= 0 && quality > 0;
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    private static boolean hasBody(HttpServletRequest request) {
        return request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
    }

    private static boolean isJson(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        try {
            return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(
                    MediaType.parseMediaType(contentType));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }
}
