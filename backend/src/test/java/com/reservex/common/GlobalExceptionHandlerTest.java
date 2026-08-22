package com.reservex.common;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void infrastructureFailuresExposeNonSuccessHttpStatuses() {
        assertThat(handler.onDuplicateKey(new DuplicateKeyException("duplicate")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.onDuplicateKey(new DuplicateKeyException("duplicate")).getBody().getCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT.getCode());
        assertThat(handler.onDataStoreDown(new IllegalStateException("redis down")).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(handler.onDataStoreDown(new QueryTimeoutException("redis timeout")).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void optimisticConflictReturns409() {
        assertThat(handler.onBiz(BizException.of(ErrorCode.STATE_CONFLICT)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.onBiz(BizException.of(ErrorCode.PASSWORD_CHANGE_REQUIRED)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.onBiz(BizException.of(ErrorCode.SLOT_NOT_RELEASED)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.onBiz(BizException.of(ErrorCode.RESERVATION_NOT_STARTED)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void conditionalRequestFailuresUse428And412() {
        assertThat(handler.onBiz(BizException.of(ErrorCode.PRECONDITION_REQUIRED)).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(handler.onBiz(BizException.of(ErrorCode.PRECONDITION_FAILED)).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_FAILED);
    }

    @Test
    void unauthorizedResponsesDeclareBearerAuthentication() {
        var response = handler.onBiz(BizException.of(ErrorCode.UNAUTHORIZED));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer");
    }

    @Test
    void unsupportedContentTypeReturns415() {
        var response = handler.onMediaTypeNotSupported(
                new HttpMediaTypeNotSupportedException("unsupported"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void missingControllerRouteReturns404() {
        var response = handler.onNotFound(
                new NoHandlerFoundException("GET", "/api/missing", HttpHeaders.EMPTY));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }
    @Test
    void methodNotAllowedAdvertisesImplicitMethodsAndPatchMediaType() {
        var get = handler.onMethodNotSupported(
                new HttpRequestMethodNotSupportedException("PUT", List.of("GET")));
        var patch = handler.onMethodNotSupported(
                new HttpRequestMethodNotSupportedException("PUT", List.of("PATCH")));

        assertThat(get.getHeaders().getAllow()).containsExactlyInAnyOrder(
                HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);
        assertThat(patch.getHeaders().getAllow()).containsExactlyInAnyOrder(
                HttpMethod.PATCH, HttpMethod.OPTIONS);
        assertThat(patch.getHeaders().getFirst(HttpHeaders.ACCEPT_PATCH))
                .isEqualTo("application/json");
    }
}
