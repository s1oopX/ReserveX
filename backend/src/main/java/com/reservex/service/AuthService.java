package com.reservex.service;

import cn.dev33.satoken.stp.SaLoginModel;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpLogic;
import cn.hutool.crypto.digest.BCrypt;
import com.google.common.util.concurrent.RateLimiter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.HttpPreconditions;
import com.reservex.common.RequestIdFilter;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.crypto.IdCardCipher;
import com.reservex.crypto.IdCardHasher;
import com.reservex.entity.EmailRoute;
import com.reservex.entity.AuditLog;
import com.reservex.entity.User;
import com.reservex.entity.RegistrationOutbox;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.sharding.UserMapper;
import com.reservex.mapper.single.EmailRouteMapper;
import com.reservex.mapper.single.AuditLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** 注册跨库两写 + 登录两跳 + access/refresh 双 token。 */
@Slf4j
@Service
public class AuthService {

    private static final Duration REFRESH_TTL = Duration.ofDays(7);
    private static final Duration REFRESH_RECEIPT_TTL = Duration.ofSeconds(30);
    private static final Duration PASSWORD_ONCE_TTL = Duration.ofMinutes(10);
    private static final String REFRESH_PREFIX = "satoken:refresh:";
    private static final String REFRESH_VERSION_PREFIX = "satoken:refresh-version:";
    private static final String REFRESH_USED_PREFIX = "satoken:refresh-used:";
    private static final String REFRESH_RECEIPT_PREFIX = "satoken:refresh-receipt:";
    private static final String PASSWORD_ONCE_PREFIX = "satoken:password-once:";
    private static final String LOGIN_RATE_PREFIX = "ratelimit:login:";
    private static final String REGISTER_RATE_PREFIX = "ratelimit:register:";
    private static final String REGISTRATION_REPLAY_RATE_PREFIX = "ratelimit:registration-replay:";
    private static final String REFRESH_RATE_PREFIX = "ratelimit:refresh:";
    private static final String REFRESH_REUSED = "~REUSED~";
    private static final String REFRESH_STALE = "~STALE~";
    private static final String REFRESH_PENDING = "~PENDING~";
    private static final String REFRESH_MISMATCH = "~MISMATCH~";
    private static final String REFRESH_BROKEN = "~BROKEN~";
    private static final DefaultRedisScript<String> RESERVE_REFRESH = new DefaultRedisScript<>("""
            local function credential(value)
                if not value then return nil, nil end
                local scheme, version, binding = string.match(value,
                        '^([^|]+)|([^|]+)|([^|]+)$')
                if scheme ~= '2' then return nil, nil end
                return version, binding
            end
            local current = redis.call('GET', KEYS[4]) or '0'
            local receipt = redis.call('GET', KEYS[3])
            if receipt then
                local state, binding, version = string.match(receipt, '^([PR])|([^|]+)|([^|]+)')
                if not state then
                    return '~BROKEN~'
                elseif version ~= current then
                    redis.call('DEL', KEYS[3])
                    return '~STALE~'
                elseif state == 'P' then
                    if binding ~= ARGV[1] then return '~MISMATCH~' end
                    if redis.call('GET', KEYS[2]) ~= '2|'..version..'|'..binding then
                        return '~STALE~'
                    end
                    return '~PENDING~'
                elseif binding ~= ARGV[1] then
                    return '~MISMATCH~'
                else
                    local newRefresh, newBinding = string.match(receipt,
                            '^R|[^|]+|[^|]+|[^|]+|([^|]+)|([^|]+)$')
                    if not newRefresh or not newBinding then return '~BROKEN~' end
                    local uid, jti = string.match(newRefresh, '^(%d+)%.([0-9a-f]+)$')
                    local newVersion, storedBinding = credential(uid and redis.call('GET',
                            'satoken:refresh:'..uid..':'..jti) or nil)
                    if not uid or newVersion ~= version or storedBinding ~= newBinding then
                        return '~STALE~'
                    end
                    return receipt
                end
            end
            local value = redis.call('GET', KEYS[1])
            if value then
                local version, expectedBinding = credential(value)
                if not version or version ~= current then
                    redis.call('DEL', KEYS[1])
                    return '~STALE~'
                end
                if expectedBinding ~= ARGV[1] then return '~MISMATCH~' end
                local ttl = redis.call('PTTL', KEYS[1])
                redis.call('DEL', KEYS[1])
                if ttl <= 0 then ttl = tonumber(ARGV[2]) end
                redis.call('SET', KEYS[2], value, 'PX', ttl)
                redis.call('SET', KEYS[3], 'P|'..ARGV[1]..'|'..version,
                        'PX', ARGV[3])
                return 'N|'..version
            end
            local used = redis.call('GET', KEYS[2])
            if not used then return nil end
            local version, expectedBinding = credential(used)
            if not version or version ~= current then return '~STALE~' end
            if expectedBinding ~= ARGV[1] then return '~MISMATCH~' end
            redis.call('INCR', KEYS[4])
            return '~REUSED~'
            """, String.class);
    private static final DefaultRedisScript<Long> COMMIT_REFRESH = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[3]) or '0'
            if current ~= ARGV[2] then return 0 end
            if redis.call('GET', KEYS[1]) ~= 'P|'..ARGV[1]..'|'..ARGV[2] then return 0 end
            if redis.call('GET', KEYS[2]) ~= '2|'..ARGV[2]..'|'..ARGV[1] then return 0 end
            redis.call('SET', KEYS[4], '2|'..ARGV[2]..'|'..ARGV[6], 'PX', ARGV[3])
            redis.call('SET', KEYS[1],
                    'R|'..ARGV[1]..'|'..ARGV[2]..'|'..ARGV[4]..'|'..ARGV[5]
                            ..'|'..ARGV[6],
                    'PX', ARGV[7])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<String> REVOKE_REFRESH = new DefaultRedisScript<>("""
            local receipt = redis.call('GET', KEYS[3])
            local newAccess = ''
            if receipt then
                local access, refresh = string.match(receipt,
                        '^R|[^|]+|[^|]+|([^|]+)|([^|]+)|[^|]+$')
                if access and refresh then
                    local uid, jti = string.match(refresh, '^(%d+)%.([0-9a-f]+)$')
                    if uid == ARGV[1] and jti then
                        redis.call('DEL', 'satoken:refresh:'..uid..':'..jti)
                        newAccess = access
                    end
                end
            end
            redis.call('DEL', KEYS[1], KEYS[2], KEYS[3])
            return newAccess
            """, String.class);
    private static final DefaultRedisScript<Long> CHECK_RATE_LIMIT = new DefaultRedisScript<>("""
            local limited = 0
            for i, key in ipairs(KEYS) do
                local count = redis.call('INCR', key)
                if count == 1 then redis.call('EXPIRE', key, ARGV[1]) end
                if count > tonumber(ARGV[i + 1]) then
                    limited = limited + 2 ^ (i - 1)
                end
            end
            return limited == 0 and 1 or limited + 1
            """, Long.class);
    private static final String DUMMY_PASSWORD_HASH = BCrypt.hashpw("reservex-dummy-password");
    private static final int[] ID_CARD_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6,
            3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] ID_CARD_CHECKSUM = {'1', '0', 'X', '9', '8', '7',
            '6', '5', '4', '3', '2'};

    private final EmailRouteMapper emailRouteMapper;
    private final AuditLogMapper auditLogMapper;
    private final UserMapper userMapper;
    private final IdGenerator idGenerator;
    private final TimeSupport time;
    private final IdCardCipher idCardCipher;
    private final IdCardHasher idCardHasher;
    private final StringRedisTemplate redis;
    private final StpLogic stpLogic;
    private final ReserveXProperties props;
    private final TransactionTemplate singleTx;
    private final RateLimiter registerLimiter;
    private final RateLimiter loginLimiter;
    private final RateLimiter refreshLimiter;
    private final RegistrationOutboxService registrationOutboxService;

    public AuthService(EmailRouteMapper emailRouteMapper,
                       AuditLogMapper auditLogMapper,
                       UserMapper userMapper,
                       IdGenerator idGenerator,
                       TimeSupport time,
                       IdCardCipher idCardCipher,
                       IdCardHasher idCardHasher,
                       StringRedisTemplate redis,
                       StpLogic stpLogic,
                       ReserveXProperties props,
                       @Qualifier("singleTxManager") PlatformTransactionManager singleTxManager,
                       RegistrationOutboxService registrationOutboxService) {
        this.emailRouteMapper = emailRouteMapper;
        this.auditLogMapper = auditLogMapper;
        this.userMapper = userMapper;
        this.idGenerator = idGenerator;
        this.time = time;
        this.idCardCipher = idCardCipher;
        this.idCardHasher = idCardHasher;
        this.redis = redis;
        this.stpLogic = stpLogic;
        this.props = props;
        this.singleTx = new TransactionTemplate(singleTxManager);
        double localRps = props.getRatelimit().getApiLocalRps();
        this.registerLimiter = RateLimiter.create(localRps);
        this.loginLimiter = RateLimiter.create(localRps);
        this.refreshLimiter = RateLimiter.create(localRps);
        this.registrationOutboxService = registrationOutboxService;
    }

    public long registerUser(String rawEmail, String rawPhone, String password, String rawIdCard,
                             String clientIp) {
        return registerUserOutcome(rawEmail, rawPhone, password, rawIdCard, clientIp).userId();
    }

    public RegistrationOutcome registerUserOutcome(String rawEmail, String rawPhone, String password,
                                                   String rawIdCard, String clientIp) {
        return registerUserOutcome(rawEmail, rawPhone, password, rawIdCard, clientIp, null);
    }

    public RegistrationOutcome registerUserOutcome(String rawEmail, String rawPhone, String password,
                                                   String rawIdCard, String clientIp, String registrationKey) {
        return registerUserOutcome(rawEmail, rawPhone, password, rawIdCard, clientIp,
                registrationKey, true);
    }

    public RegistrationOutcome registerUserOutcome(String rawEmail, String rawPhone, String password,
                                                   String rawIdCard, String clientIp,
                                                   String registrationKey, boolean newlyVerified) {
        String email = normalizeEmail(rawEmail);
        String phone = rawPhone.trim();
        validatePassword(password);
        validateIdCard(rawIdCard.trim().toUpperCase(Locale.ROOT));
        if (newlyVerified) {
            requireRegisterRate(email, phone, clientIp);
        } else {
            requireRegistrationReplayRate(registrationKey, clientIp);
        }
        requireLocalRate(registerLimiter);
        return registerOutcome(rawEmail, rawPhone, password, rawIdCard, "USER", registrationKey);
    }

    public RegistrationOutcome registrationByKey(String registrationKey, String rawEmail,
                                                 String rawPhone, String password, String rawIdCard,
                                                 String clientIp) {
        RegistrationOutbox existing = registrationOutboxService.findByRegistrationKey(registrationKey);
        if (existing == null) {
            return null;
        }
        requireRegistrationReplayRate(registrationKey, clientIp);
        String idCard = rawIdCard.trim().toUpperCase(Locale.ROOT);
        String email = normalizeEmail(rawEmail);
        String phone = rawPhone.trim();
        String requestDigest = registrationRequestDigest(email, phone, password, idCard, "USER");
        boolean matches = existing.getRequestFingerprint() != null
                ? BCrypt.checkpw(requestDigest, existing.getRequestFingerprint())
                : Objects.equals(existing.getEmail(), email)
                    && Objects.equals(existing.getPhone(), phone)
                    && Objects.equals(existing.getIdCardHash(), idCardHasher.hash(idCard))
                    && existing.getPassword() != null
                    && BCrypt.checkpw(password, existing.getPassword());
        if (!matches) {
            throw BizException.of(ErrorCode.REGISTRATION_CONFLICT);
        }
        return new RegistrationOutcome(existing.getUserId(), Integer.valueOf(3).equals(existing.getStatus()));
    }

    public String registrationRequestFingerprint(String rawEmail, String rawPhone,
                                                 String password, String rawIdCard) {
        String email = normalizeEmail(rawEmail);
        String phone = rawPhone.trim();
        String idCard = rawIdCard.trim().toUpperCase(Locale.ROOT);
        validatePassword(password);
        validateIdCard(idCard);
        return registrationRequestDigest(email, phone, password, idCard, "USER");
    }

    public String registrationStatus(String registrationKey) {
        return switch (registrationOutboxService.statusByRegistrationKey(registrationKey)) {
            case 0, 1 -> "PENDING";
            case 2 -> "STUCK";
            case 3 -> "READY";
            default -> null;
        };
    }

    /**
     * 注册跨库两写。{@code role} 由调用方传入(注册端点恒传 USER,管理端建 STAFF 传 STAFF),
     * **不暴露给 HTTP 请求体** —— role 能被 HTTP 注入即提权漏洞(User 红线:ADMIN 只能 seed/bootstrap 产生)。
     */
    public long register(String rawEmail, String rawPhone, String password, String rawIdCard, String role) {
        return registerOutcome(rawEmail, rawPhone, password, rawIdCard, role, null).userId();
    }

    private RegistrationOutcome registerOutcome(String rawEmail, String rawPhone, String password,
                                                 String rawIdCard, String role, String registrationKey) {
        return register(rawEmail, rawPhone, password, rawIdCard, role, idGenerator.nextId(), 0L,
                registrationKey);
    }

    private RegistrationOutcome register(String rawEmail, String rawPhone, String password, String rawIdCard,
                                         String role, long userId, long operatorId, String registrationKey) {
        String email = normalizeEmail(rawEmail);
        String phone = rawPhone.trim();
        String idCard = rawIdCard.trim().toUpperCase(Locale.ROOT);
        validatePassword(password);
        validateIdCard(idCard);

        IdCardCipher.Encrypted encrypted = idCardCipher.encrypt(idCard);
        String idCardHash = idCardHasher.hash(idCard);
        String passwordHash = BCrypt.hashpw(password);
        LocalDateTime now = time.now();

        User user = new User();
        user.setUserId(userId);
        user.setEmail(email);
        user.setPhone(phone);
        user.setPassword(passwordHash);
        user.setIdCardCiphertext(encrypted.ciphertext());
        user.setIdCardKeyId(encrypted.keyId());
        user.setIdCardHash(idCardHash);
        user.setIdCardMasked(idCardCipher.mask(idCard));
        user.setRole(role);
        user.setStatus(0);
        user.setVersion(0);
        user.setMustChangePassword("STAFF".equals(role) ? 1 : 0);
        user.setCreateAt(now);
        user.setUpdateAt(now);

        String requestDigest = registrationKey == null ? null
                : registrationRequestDigest(email, phone, password, idCard, role);
        RegistrationOutboxService.StartOutcome started = registrationOutboxService.start(
                user, "STAFF".equals(role) ? operatorId : 0L, registrationKey, requestDigest);
        return new RegistrationOutcome(started.userId(), started.ready());
    }

    public LoginOutcome login(String rawEmail, String password, String clientIp) {
        String email = normalizeEmail(rawEmail);
        LoginRate rate = requireLoginRate(email, clientIp);
        requireLocalRate(loginLimiter);
        EmailRoute route = emailRouteMapper.selectById(email);
        if (route == null) {
            passwordMatchesHash(password, DUMMY_PASSWORD_HASH);
            log.info("登录失败 reason=EMAIL_NOT_FOUND");
            throw loginFailure(rate);
        }

        User user = userMapper.selectById(route.getUserId());
        if (user == null) {
            log.error("登录失败 reason=ORPHAN_ROUTE userId={}", route.getUserId());
            throw loginFailure(rate);
        }
        if (!passwordMatches(password, user)) {
            log.info("登录失败 reason=PASSWORD_MISMATCH userId={}", user.getUserId());
            throw loginFailure(rate);
        }
        if (Objects.equals(user.getStatus(), 1)) {
            throw BizException.of(ErrorCode.ACCOUNT_BANNED);
        }
        // 密码已验证后先清失败计数；若后续签发失败，不能留下未返回给客户端的活跃凭证。
        redis.delete(rate.accountKey());
        return requiresPasswordChange(user)
                ? LoginOutcome.passwordChangeRequired(issuePasswordOnceToken(user.getUserId()))
                : LoginOutcome.authenticated(issueTokens(user));
    }

    public TokenPair refresh(String refreshToken, String oldAccessToken, String clientIp) {
        if (oldAccessToken == null || oldAccessToken.isBlank() || oldAccessToken.length() > 2048) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        RefreshRef ref = parseRefreshToken(refreshToken);
        requireRefreshRate(clientIp);
        requireLocalRate(refreshLimiter);
        String binding = sha256(oldAccessToken);
        User user = userMapper.selectById(ref.userId());
        if (user == null) {
            redis.delete(refreshKey(ref));
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        if (Objects.equals(user.getStatus(), 1)) {
            redis.delete(refreshKey(ref));
            throw BizException.of(ErrorCode.ACCOUNT_BANNED);
        }
        if (Objects.equals(user.getMustChangePassword(), 1)) {
            redis.delete(refreshKey(ref));
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        String reservation = redis.execute(RESERVE_REFRESH,
                List.of(refreshKey(ref), refreshUsedKey(ref), refreshReceiptKey(ref),
                        refreshVersionKey(ref.userId())),
                binding, Long.toString(REFRESH_TTL.toMillis()),
                Long.toString(REFRESH_RECEIPT_TTL.toMillis()));
        if (REFRESH_REUSED.equals(reservation)) {
            stpLogic.logout(ref.userId());
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        if (REFRESH_PENDING.equals(reservation)) {
            throw BizException.of(ErrorCode.REFRESH_IN_PROGRESS);
        }
        if (REFRESH_BROKEN.equals(reservation)) {
            throw BizException.of(ErrorCode.SERVICE_DEGRADED);
        }
        if (reservation == null || REFRESH_STALE.equals(reservation)
                || REFRESH_MISMATCH.equals(reservation)) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        if (reservation.startsWith("R|")) {
            return cachedRefresh(reservation, binding, user);
        }
        if (!reservation.startsWith("N|")) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        String expectedVersion = reservation.substring(2);
        try {
            if (Long.parseLong(expectedVersion) < 0) {
                throw BizException.of(ErrorCode.UNAUTHORIZED);
            }
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        String accessToken = issueAccess(user);
        RefreshRef newRef = new RefreshRef(user.getUserId(), randomToken());
        String newRefreshToken = user.getUserId() + "." + newRef.jti();
        String newBinding = sha256(accessToken);
        Long committed = redis.execute(COMMIT_REFRESH,
                List.of(refreshReceiptKey(ref), refreshUsedKey(ref),
                        refreshVersionKey(ref.userId()), refreshKey(newRef)),
                binding, expectedVersion, Long.toString(REFRESH_TTL.toMillis()),
                accessToken, newRefreshToken, newBinding,
                Long.toString(REFRESH_RECEIPT_TTL.toMillis()));
        if (!Long.valueOf(1L).equals(committed)) {
            revokeIssuedPair(accessToken, newRefreshToken);
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return new TokenPair(accessToken, newRefreshToken, user.getUserId(), user.getRole());
    }

    private TokenPair cachedRefresh(String receipt, String binding, User user) {
        String[] parts = receipt.split("\\|", 6);
        if (parts.length != 6 || !binding.equals(parts[1])
                || !sha256(parts[3]).equals(parts[5])) {
            throw BizException.of(ErrorCode.SERVICE_DEGRADED);
        }
        try {
            if (Long.parseLong(parts[2]) < 0
                    || parseRefreshToken(parts[4]).userId() != user.getUserId()
                    || parts[3].isBlank() || parts[3].length() > 2048) {
                throw BizException.of(ErrorCode.SERVICE_DEGRADED);
            }
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.SERVICE_DEGRADED);
        }
        return new TokenPair(parts[3], parts[4], user.getUserId(), user.getRole());
    }

    public void logout(Long accessUserId, String refreshToken) {
        if (accessUserId != null) {
            stpLogic.logout();
        }
        if (refreshToken == null || refreshToken.isBlank()) {
            if (accessUserId == null) {
                throw BizException.of(ErrorCode.UNAUTHORIZED);
            }
            return;
        }
        RefreshRef ref = parseRefreshToken(refreshToken);
        // Refresh possession is sufficient to revoke that one opaque token. This keeps
        // logout effective after the short-lived access token has already expired.
        if (accessUserId == null || ref.userId() == accessUserId) {
            String rotatedAccess = redis.execute(REVOKE_REFRESH,
                    List.of(refreshKey(ref), refreshUsedKey(ref), refreshReceiptKey(ref)),
                    Long.toString(ref.userId()));
            if (rotatedAccess != null && !rotatedAccess.isBlank()) {
                stpLogic.logoutByTokenValue(rotatedAccess);
            }
        }
    }

    public void changePassword(Long accessUserId, String onceToken,
                               String oldPassword, String newPassword) {
        long userId = onceToken == null || onceToken.isBlank()
                ? requireAccessUser(accessUserId)
                : requirePasswordOnceUser(onceToken);

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        if (Objects.equals(user.getStatus(), 1)) {
            throw BizException.of(ErrorCode.ACCOUNT_BANNED);
        }
        if (!passwordMatches(oldPassword, user)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "原密码错误");
        }
        validatePassword(newPassword);
        if (passwordMatches(newPassword, user)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码不能与原密码相同");
        }

        String bcrypt = BCrypt.hashpw(newPassword);
        // 先使旧 refresh 失效再改 DB。DB 若失败只会要求重登,不会留下可继续刷新的旧凭证。
        redis.opsForValue().increment(refreshVersionKey(userId));
        if (userMapper.updatePassword(userId, user.getPassword(), bcrypt, time.now()) != 1) {
            stpLogic.logout(userId);
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        stpLogic.logout(userId);
    }

    private TokenPair issueTokens(User user) {
        long version = refreshVersion(user.getUserId());
        String accessToken = issueAccess(user);
        String refreshToken = issueRefresh(user.getUserId(), version, accessToken);
        if (refreshVersion(user.getUserId()) != version) {
            revokeIssuedPair(accessToken, refreshToken);
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        User latest = userMapper.selectById(user.getUserId());
        if (latest == null
                || !Objects.equals(latest.getPassword(), user.getPassword())
                || !Objects.equals(latest.getRole(), user.getRole())
                || Objects.equals(latest.getStatus(), 1)
                || Objects.equals(latest.getMustChangePassword(), 1)) {
            revokeIssuedPair(accessToken, refreshToken);
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return new TokenPair(accessToken, refreshToken, user.getUserId(), user.getRole());
    }

    private String issueAccess(User user) {
        SaLoginModel model = new SaLoginModel();
        model.setIsWriteHeader(false);
        String token = stpLogic.createLoginSession(user.getUserId(), model);
        SaSession session = stpLogic.getTokenSessionByToken(token);
        session.set("idCardHash", user.getIdCardHash());
        session.set("idCardMasked", user.getIdCardMasked());
        return token;
    }

    private String issueRefresh(long userId, long version, String accessToken) {
        String jti = randomToken();
        RefreshRef ref = new RefreshRef(userId, jti);
        redis.opsForValue().set(refreshKey(ref),
                "2|" + version + "|" + sha256(accessToken), REFRESH_TTL);
        return userId + "." + jti;
    }

    private void revokeIssuedPair(String accessToken, String refreshToken) {
        stpLogic.logoutByTokenValue(accessToken);
        redis.delete(refreshKey(parseRefreshToken(refreshToken)));
    }

    private long refreshVersion(long userId) {
        String value = redis.opsForValue().get(refreshVersionKey(userId));
        return value == null ? 0L : Long.parseLong(value);
    }

    private String issuePasswordOnceToken(long userId) {
        String token = randomToken();
        redis.opsForValue().set(passwordOnceKey(token), Long.toString(userId), PASSWORD_ONCE_TTL);
        return token;
    }

    private long requirePasswordOnceUser(String token) {
        if (token.length() != 32) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        String value = redis.opsForValue().getAndDelete(passwordOnceKey(token));
        if (value == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
    }

    private RefreshRef parseRefreshToken(String token) {
        if (token == null || token.length() > 96) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        String jti = token.substring(dot + 1);
        if (!jti.matches("[0-9a-f]{32}")) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        try {
            long userId = Long.parseLong(token.substring(0, dot));
            if (userId <= 0) {
                throw BizException.of(ErrorCode.UNAUTHORIZED);
            }
            return new RefreshRef(userId, jti);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
    }

    private boolean requiresPasswordChange(User user) {
        return Objects.equals(user.getMustChangePassword(), 1);
    }

    private boolean passwordMatches(String plain, User user) {
        return passwordMatchesHash(plain, user.getPassword());
    }

    private boolean passwordMatchesHash(String plain, String hash) {
        if (plain == null || plain.getBytes(StandardCharsets.UTF_8).length > 72) {
            return false;
        }
        try {
            return BCrypt.checkpw(plain, hash);
        } catch (RuntimeException e) {
            log.error("用户密码 hash 不合法", e);
            return false;
        }
    }

    private void requireLocalRate(RateLimiter limiter) {
        if (!limiter.tryAcquire()) {
            throw BizException.of(ErrorCode.RATE_LIMITED);
        }
    }

    private LoginRate requireLoginRate(String email, String clientIp) {
        String accountKey = LOGIN_RATE_PREFIX + "account:" + sha256(email);
        String ipKey = LOGIN_RATE_PREFIX + "ip:" + sha256(clientIp);
        Long result = redis.execute(CHECK_RATE_LIMIT, List.of(accountKey, ipKey),
                Integer.toString(props.getRatelimit().getLoginWindowSec()),
                Integer.toString(props.getRatelimit().getLoginMaxAttempts()),
                Integer.toString(props.getRatelimit().getLoginIpMaxAttempts()));
        if (result == null) {
            throw BizException.of(ErrorCode.SERVICE_DEGRADED);
        }
        // 1=未超限,2=仅账号超限,3=仅 IP 超限,4=两者都超限。
        // 账号超限仍校验正确密码,避免匿名者把任意账号锁死；IP 超限继续早拒绝。
        if (result == 0 || result >= 3) {
            throw BizException.of(ErrorCode.RATE_LIMITED);
        }
        return new LoginRate(accountKey, result == 2);
    }

    private BizException loginFailure(LoginRate rate) {
        return BizException.of(rate.accountLimited() ? ErrorCode.RATE_LIMITED : ErrorCode.LOGIN_FAILED);
    }

    private void requireRegisterRate(String email, String phone, String clientIp) {
        Long result = redis.execute(CHECK_RATE_LIMIT, List.of(
                        REGISTER_RATE_PREFIX + "email:" + sha256(email),
                        REGISTER_RATE_PREFIX + "phone:" + sha256(phone),
                        REGISTER_RATE_PREFIX + "ip:" + sha256(clientIp)),
                Integer.toString(props.getRatelimit().getRegisterWindowSec()),
                Integer.toString(props.getRatelimit().getRegisterIdentityMaxAttempts()),
                Integer.toString(props.getRatelimit().getRegisterIdentityMaxAttempts()),
                Integer.toString(props.getRatelimit().getRegisterIpMaxAttempts()));
        if (result == null) {
            throw BizException.of(ErrorCode.SERVICE_DEGRADED);
        }
        if (result != 1) {
            throw BizException.of(ErrorCode.RATE_LIMITED);
        }
    }

    private void requireRegistrationReplayRate(String registrationKey, String clientIp) {
        if (registrationKey == null || registrationKey.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        int maxAttempts = props.getRatelimit().getRegisterIpMaxAttempts();
        Long result = redis.execute(CHECK_RATE_LIMIT, List.of(
                        REGISTRATION_REPLAY_RATE_PREFIX + "key:" + sha256(registrationKey),
                        REGISTRATION_REPLAY_RATE_PREFIX + "ip:" + sha256(clientIp)),
                Integer.toString(props.getRatelimit().getRegisterWindowSec()),
                Integer.toString(maxAttempts), Integer.toString(maxAttempts));
        if (result == null) {
            throw BizException.of(ErrorCode.SERVICE_DEGRADED);
        }
        if (result != 1) {
            throw BizException.of(ErrorCode.RATE_LIMITED);
        }
    }

    private void requireRefreshRate(String clientIp) {
        Long result = redis.execute(CHECK_RATE_LIMIT,
                List.of(REFRESH_RATE_PREFIX + "ip:" + sha256(clientIp)),
                Integer.toString(props.getRatelimit().getRefreshWindowSec()),
                Integer.toString(props.getRatelimit().getRefreshIpMaxAttempts()));
        if (result == null) {
            throw BizException.of(ErrorCode.SERVICE_DEGRADED);
        }
        if (result != 1) {
            throw BizException.of(ErrorCode.RATE_LIMITED);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    static String registrationRequestDigest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码至少 8 位且 UTF-8 编码不超过 72 字节");
        }
    }

    static void validateIdCard(String idCard) {
        if (idCard == null || !idCard.matches("[1-9]\\d{16}[0-9X]")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "身份证号不合法");
        }
        try {
            LocalDate.parse(idCard.substring(6, 14), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "身份证出生日期不合法");
        }
        int sum = 0;
        for (int i = 0; i < ID_CARD_WEIGHTS.length; i++) {
            sum += (idCard.charAt(i) - '0') * ID_CARD_WEIGHTS[i];
        }
        if (ID_CARD_CHECKSUM[sum % 11] != idCard.charAt(17)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "身份证校验位不合法");
        }
    }

    private long requireAccessUser(Long userId) {
        if (userId == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String refreshKey(RefreshRef ref) {
        return REFRESH_PREFIX + ref.userId() + ":" + ref.jti();
    }

    private String refreshVersionKey(long userId) {
        return REFRESH_VERSION_PREFIX + userId;
    }

    private String refreshUsedKey(RefreshRef ref) {
        return REFRESH_USED_PREFIX + ref.userId() + ":" + ref.jti();
    }

    private String refreshReceiptKey(RefreshRef ref) {
        return REFRESH_RECEIPT_PREFIX + ref.userId() + ":" + ref.jti();
    }

    private String passwordOnceKey(String token) {
        return PASSWORD_ONCE_PREFIX + token;
    }

    private String randomToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record RefreshRef(long userId, String jti) {
    }

    /**
     * 列出 STAFF 账号(广播两库归并)。role=ADMIN 只能 seed/bootstrap 产生,
     * 此处只查 STAFF,管理端不得列出超管凭据。
     */
    public List<StaffView> listStaff() {
        return userMapper.selectList(new LambdaQueryWrapper<User>()
                        .eq(User::getRole, "STAFF")
                        .orderByDesc(User::getCreateAt))
                .stream()
                .map(AuthService::staffView)
                .toList();
    }

    public UserView getUser(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return new UserView(user.getUserId(), user.getEmail(), user.getPhone(),
                user.getIdCardMasked(), user.getRole(), user.getStatus(), user.getCreateAt());
    }

    public StaffView getStaff(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || !"STAFF".equals(user.getRole())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return staffView(user);
    }

    private static StaffView staffView(User user) {
        return new StaffView(user.getUserId(), user.getEmail(), user.getPhone(),
                user.getIdCardMasked(), user.getStatus(), user.getVersion(), user.getCreateAt());
    }

    /**
     * 管理员创建 STAFF 账号。复用注册跨库两写逻辑,role 硬编码 STAFF(不暴露给 HTTP)。
     */
    public RegistrationOutcome createStaff(String email, String phone, String password,
                                           String idCard, long operatorId, String idempotencyKey) {
        long userId = idGenerator.nextId();
        RegistrationOutcome outcome = register(email, phone, password, idCard, "STAFF", userId,
                operatorId, idempotencyKey);
        log.info("管理员 {} 创建 STAFF 账号 userId={}", operatorId, outcome.userId());
        return outcome;
    }

    public StaffView setStaffBanned(long userId, boolean banned, long operatorId,
                                    HttpPreconditions.VersionCondition condition) {
        User staff = userMapper.selectById(userId);
        if (staff == null || !"STAFF".equals(staff.getRole())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (!condition.matches(staff.getVersion())) {
            throw BizException.of(ErrorCode.PRECONDITION_FAILED);
        }
        int before = staff.getStatus();
        int after = banned ? 1 : 0;
        String action = banned ? "BAN_USER" : "UNBAN_USER";
        recordStaffStatusAudit(action + "_REQUESTED", operatorId, userId, before, after);

        if (userMapper.updateStatus(userId, staff.getVersion(), after, time.now()) != 1) {
            throw BizException.of(ErrorCode.PRECONDITION_FAILED);
        }
        revokeAllCredentials(userId);
        recordStaffStatusAudit(action, operatorId, userId, before, after);
        return new StaffView(userId, staff.getEmail(), staff.getPhone(), staff.getIdCardMasked(),
                after, staff.getVersion() + 1, staff.getCreateAt());
    }

    private void revokeAllCredentials(long userId) {
        redis.opsForValue().increment(refreshVersionKey(userId));
        stpLogic.logout(userId);
    }

    private void recordStaffStatusAudit(String action, long operatorId, long userId,
                                        int before, int after) {
        AuditLog audit = new AuditLog();
        audit.setId(idGenerator.nextId());
        audit.setOperatorType("ADMIN");
        audit.setOperatorId(operatorId);
        audit.setAction(action);
        audit.setTargetType("USER");
        audit.setTargetId(userId);
        audit.setBefore("{\"status\":" + before + "}");
        audit.setAfter("{\"status\":" + after + "}");
        audit.setRequestId(requestId("admin-staff-status-" + userId));
        audit.setCreateAt(time.now());
        singleTx.executeWithoutResult(status -> {
            if (auditLogMapper.insert(audit) != 1) {
                throw new IllegalStateException("写账号状态审计失败 userId=" + userId);
            }
        });
    }

    private static String requestId(String fallback) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return requestId == null || requestId.isBlank() ? fallback : requestId;
    }

    public record TokenPair(String accessToken, String refreshToken, Long userId, String role) {
    }

    public record LoginOutcome(TokenPair tokens, String onceToken) {
        public static LoginOutcome authenticated(TokenPair tokens) {
            return new LoginOutcome(tokens, null);
        }

        public static LoginOutcome passwordChangeRequired(String onceToken) {
            return new LoginOutcome(null, onceToken);
        }
    }

    public record RegistrationOutcome(long userId, boolean ready) {
    }

    private record LoginRate(String accountKey, boolean accountLimited) {
    }

    public record StaffView(Long userId, String email, String phone,
                            String idCardMasked, Integer status, Integer version,
                            LocalDateTime createAt) {
    }

    public record UserView(Long userId, String email, String phone, String idCardMasked,
                           String role, Integer status, LocalDateTime createAt) {
    }
}
