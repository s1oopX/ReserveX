package com.reservex.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpPreconditionsTest {

    @Test
    void parsesStrongVersionTagsAndWildcard() {
        var versions = HttpPreconditions.requireVersion("\"2\", \"4\"");
        assertTrue(versions.matches(2));
        assertTrue(versions.matches(4));
        assertFalse(versions.matches(3));
        assertTrue(HttpPreconditions.requireVersion("*").matches(99));
        assertEquals("\"3\"", HttpPreconditions.etag(3));
    }

    @Test
    void missingOrWeakConditionsAreRejected() {
        assertEquals(ErrorCode.PRECONDITION_REQUIRED,
                assertThrows(BizException.class,
                        () -> HttpPreconditions.requireVersion(null)).getErrorCode());
        assertEquals(ErrorCode.BAD_REQUEST,
                assertThrows(BizException.class,
                        () -> HttpPreconditions.requireVersion("W/\"3\"")).getErrorCode());
    }

    @Test
    void validatesIdempotencyKeysAtTheHttpBoundary() {
        assertEquals("01234567-89ab-cdef-0123-456789abcdef",
                HttpPreconditions.requireIdempotencyKey(
                        "01234567-89ab-cdef-0123-456789abcdef"));
        assertThrows(BizException.class, () -> HttpPreconditions.requireIdempotencyKey(null));
        assertThrows(BizException.class, () -> HttpPreconditions.requireIdempotencyKey("too-short"));
        assertThrows(BizException.class, () -> HttpPreconditions.requireIdempotencyKey(
                "01234567-89ab-cdef-0123-456789abcde!"));
    }
}
