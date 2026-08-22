package com.reservex.common;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Strong integer ETags used by versioned REST resources. */
public final class HttpPreconditions {

    private HttpPreconditions() {
    }

    public static VersionCondition requireVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw BizException.of(ErrorCode.PRECONDITION_REQUIRED);
        }
        String value = ifMatch.trim();
        if ("*".equals(value)) {
            return new VersionCondition(true, Set.of());
        }
        try {
            Set<Integer> versions = Arrays.stream(value.split(",", -1))
                    .map(String::trim)
                    .map(HttpPreconditions::parseStrongTag)
                    .collect(Collectors.toUnmodifiableSet());
            if (versions.isEmpty()) {
                throw new IllegalArgumentException();
            }
            return new VersionCondition(false, versions);
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "If-Match 必须是强版本 ETag");
        }
    }

    public static String etag(int version) {
        return "\"" + version + "\"";
    }

    public static String requireIdempotencyKey(String value) {
        if (value == null || value.length() > 128
                || !value.matches("[A-Za-z0-9._~-]{16,128}")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Idempotency-Key 格式不合法");
        }
        return value;
    }

    private static int parseStrongTag(String tag) {
        if (tag.length() < 3 || tag.charAt(0) != '"' || tag.charAt(tag.length() - 1) != '"') {
            throw new IllegalArgumentException();
        }
        int version = Integer.parseInt(tag.substring(1, tag.length() - 1));
        if (version < 0) {
            throw new IllegalArgumentException();
        }
        return version;
    }

    public record VersionCondition(boolean any, Set<Integer> versions) {
        public boolean matches(int version) {
            return any || versions.contains(version);
        }
    }
}
