package com.reservex.service;

import cn.dev33.satoken.stp.SaLoginModel;
import cn.dev33.satoken.stp.StpLogic;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.crypto.IdCardCipher;
import com.reservex.crypto.IdCardHasher;
import com.reservex.entity.EmailRoute;
import com.reservex.entity.PhoneRoute;
import com.reservex.entity.User;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.sharding.UserMapper;
import com.reservex.mapper.single.EmailRouteMapper;
import com.reservex.mapper.single.PhoneRouteMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** 注册跨库两写 + 登录两跳 + access/refresh 双 token。 */
@Slf4j
@Service
public class AuthService {

    private static final Duration REFRESH_TTL = Duration.ofDays(7);
    private static final Duration PASSWORD_ONCE_TTL = Duration.ofMinutes(10);
    private static final String REFRESH_PREFIX = "satoken:refresh:";
    private static final String REFRESH_VERSION_PREFIX = "satoken:refresh-version:";
    private static final String PASSWORD_ONCE_PREFIX = "satoken:password-once:";

    private final EmailRouteMapper emailRouteMapper;
    private final PhoneRouteMapper phoneRouteMapper;
    private final UserMapper userMapper;
    private final IdGenerator idGenerator;
    private final TimeSupport time;
    private final IdCardCipher idCardCipher;
    private final IdCardHasher idCardHasher;
    private final StringRedisTemplate redis;
    private final StpLogic stpLogic;
    private final ReserveXProperties props;
    private final TransactionTemplate singleTx;

    public AuthService(EmailRouteMapper emailRouteMapper,
                       PhoneRouteMapper phoneRouteMapper,
                       UserMapper userMapper,
                       IdGenerator idGenerator,
                       TimeSupport time,
                       IdCardCipher idCardCipher,
                       IdCardHasher idCardHasher,
                       StringRedisTemplate redis,
                       StpLogic stpLogic,
                       ReserveXProperties props,
                       @Qualifier("singleTxManager") PlatformTransactionManager singleTxManager) {
        this.emailRouteMapper = emailRouteMapper;
        this.phoneRouteMapper = phoneRouteMapper;
        this.userMapper = userMapper;
        this.idGenerator = idGenerator;
        this.time = time;
        this.idCardCipher = idCardCipher;
        this.idCardHasher = idCardHasher;
        this.redis = redis;
        this.stpLogic = stpLogic;
        this.props = props;
        this.singleTx = new TransactionTemplate(singleTxManager);
    }

    public void register(String rawEmail, String rawPhone, String password, String rawIdCard) {
        register(rawEmail, rawPhone, password, rawIdCard, "USER");
    }

    /**
     * 注册跨库两写。{@code role} 由调用方传入(注册端点恒传 USER,管理端建 STAFF 传 STAFF),
     * **不暴露给 HTTP 请求体** —— role 能被 HTTP 注入即提权漏洞(User 红线:ADMIN 只能 seed/bootstrap 产生)。
     */
    public void register(String rawEmail, String rawPhone, String password, String rawIdCard, String role) {
        String email = normalizeEmail(rawEmail);
        String phone = rawPhone.trim();
        String idCard = rawIdCard.trim().toUpperCase(Locale.ROOT);
        validatePassword(password);

        IdCardCipher.Encrypted encrypted = idCardCipher.encrypt(idCard);
        String idCardHash = idCardHasher.hash(idCard);
        String passwordHash = BCrypt.hashpw(password);
        LocalDateTime now = time.now();

        long userId = idGenerator.nextId();
        try {
            reserveRoutes(email, phone, userId, now);
        } catch (BizException conflict) {
            if (conflict.getErrorCode() != ErrorCode.EMAIL_TAKEN
                    && conflict.getErrorCode() != ErrorCode.PHONE_TAKEN) {
                throw conflict;
            }
            Long orphanUserId = reusableOrphan(email, phone);
            if (orphanUserId == null) {
                throw conflict;
            }
            userId = orphanUserId;
            log.warn("注册复用孤儿 route userId={}", userId);
        }

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
        user.setCreateAt(now);
        user.setUpdateAt(now);

        insertUserOrCompensate(user);
    }

    public LoginOutcome login(String rawEmail, String password) {
        String email = normalizeEmail(rawEmail);
        EmailRoute route = emailRouteMapper.selectById(email);
        if (route == null) {
            log.info("登录失败 reason=EMAIL_NOT_FOUND");
            throw BizException.of(ErrorCode.LOGIN_FAILED);
        }

        User user = userMapper.selectById(route.getUserId());
        if (user == null) {
            log.error("登录失败 reason=ORPHAN_ROUTE userId={}", route.getUserId());
            throw BizException.of(ErrorCode.LOGIN_FAILED);
        }
        if (!passwordMatches(password, user)) {
            log.info("登录失败 reason=PASSWORD_MISMATCH userId={}", user.getUserId());
            throw BizException.of(ErrorCode.LOGIN_FAILED);
        }
        if (Objects.equals(user.getStatus(), 1)) {
            throw BizException.of(ErrorCode.ACCOUNT_BANNED);
        }
        if (requiresPasswordChange(user)) {
            return LoginOutcome.passwordChangeRequired(issuePasswordOnceToken(user.getUserId()));
        }
        return LoginOutcome.authenticated(issueTokens(user));
    }

    public TokenPair refresh(String refreshToken) {
        RefreshRef ref = parseRefreshToken(refreshToken);
        String storedVersion = redis.opsForValue().get(refreshKey(ref));
        if (storedVersion == null || !storedVersion.equals(Long.toString(refreshVersion(ref.userId())))) {
            redis.delete(refreshKey(ref));
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }

        User user = userMapper.selectById(ref.userId());
        if (user == null) {
            redis.delete(refreshKey(ref));
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        if (Objects.equals(user.getStatus(), 1)) {
            redis.delete(refreshKey(ref));
            throw BizException.of(ErrorCode.ACCOUNT_BANNED);
        }
        return new TokenPair(issueAccess(user), refreshToken, user.getUserId(), user.getRole());
    }

    public void logout(long accessUserId, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        RefreshRef ref = parseRefreshToken(refreshToken);
        if (ref.userId() == accessUserId) {
            redis.delete(refreshKey(ref));
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
        if (userMapper.updatePassword(userId, bcrypt, time.now()) != 1) {
            throw new IllegalStateException("更新密码失败 userId=" + userId);
        }
        if (onceToken != null && !onceToken.isBlank()) {
            redis.delete(passwordOnceKey(onceToken));
        }
    }

    private void reserveRoutes(String email, String phone, long userId, LocalDateTime now) {
        singleTx.executeWithoutResult(status -> {
            if (emailRouteMapper.insertIgnore(email, userId, now) == 0) {
                throw BizException.of(ErrorCode.EMAIL_TAKEN);
            }
            if (phoneRouteMapper.insertIgnore(phone, userId, now) == 0) {
                throw BizException.of(ErrorCode.PHONE_TAKEN);
            }
        });
    }

    private Long reusableOrphan(String email, String phone) {
        EmailRoute emailRoute = emailRouteMapper.selectById(email);
        PhoneRoute phoneRoute = phoneRouteMapper.selectById(phone);
        if (emailRoute == null || phoneRoute == null
                || !Objects.equals(emailRoute.getUserId(), phoneRoute.getUserId())) {
            return null;
        }
        return userMapper.selectById(emailRoute.getUserId()) == null ? emailRoute.getUserId() : null;
    }

    private void insertUserOrCompensate(User user) {
        RuntimeException failure;
        try {
            if (userMapper.insert(user) == 1) {
                return;
            }
            failure = new IllegalStateException("插入用户返回 0 行 userId=" + user.getUserId());
        } catch (RuntimeException e) {
            failure = e;
        }

        User existing;
        try {
            existing = userMapper.selectById(user.getUserId());
        } catch (RuntimeException verifyFailure) {
            failure.addSuppressed(verifyFailure);
            log.error("用户写入结果未知,保留 route 等待自愈 userId={}", user.getUserId(), failure);
            throw failure;
        }
        if (existing != null) {
            if (Objects.equals(existing.getEmail(), user.getEmail())
                    && Objects.equals(existing.getPhone(), user.getPhone())) {
                log.warn("用户写入抛异常但行已存在,按成功收敛 userId={}", user.getUserId());
                return;
            }
            throw new IllegalStateException("Snowflake userId 冲突 userId=" + user.getUserId(), failure);
        }

        try {
            singleTx.executeWithoutResult(status -> {
                emailRouteMapper.deleteByEmailAndUser(user.getEmail(), user.getUserId());
                phoneRouteMapper.deleteByPhoneAndUser(user.getPhone(), user.getUserId());
            });
        } catch (RuntimeException compensationFailure) {
            failure.addSuppressed(compensationFailure);
            log.error("注册补偿失败,已留下可重试自愈的孤儿 route userId={}", user.getUserId(), compensationFailure);
        }
        throw failure;
    }

    private TokenPair issueTokens(User user) {
        String accessToken = issueAccess(user);
        String refreshToken = issueRefresh(user.getUserId());
        return new TokenPair(accessToken, refreshToken, user.getUserId(), user.getRole());
    }

    private String issueAccess(User user) {
        SaLoginModel model = new SaLoginModel()
                .setExtra("role", user.getRole())
                .setExtra("idCardHash", user.getIdCardHash())
                .setExtra("idCardMasked", user.getIdCardMasked())
                .setIsWriteHeader(false);
        return stpLogic.createLoginSession(user.getUserId(), model);
    }

    private String issueRefresh(long userId) {
        String jti = randomToken();
        RefreshRef ref = new RefreshRef(userId, jti);
        redis.opsForValue().set(refreshKey(ref), Long.toString(refreshVersion(userId)), REFRESH_TTL);
        return userId + "." + jti;
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
        String value = redis.opsForValue().get(passwordOnceKey(token));
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
        ReserveXProperties.AdminBootstrap cfg = props.getAdminBootstrap();
        if (!cfg.isForceChangeOnFirstLogin() || user.getUserId() != cfg.getUserId()
                || cfg.getInitPassword() == null) {
            return false;
        }
        try {
            return BCrypt.checkpw(cfg.getInitPassword(), user.getPassword());
        } catch (RuntimeException e) {
            log.error("超管密码 hash 不合法 userId={}", user.getUserId(), e);
            return false;
        }
    }

    private boolean passwordMatches(String plain, User user) {
        if (plain == null || plain.getBytes(StandardCharsets.UTF_8).length > 72) {
            return false;
        }
        try {
            return BCrypt.checkpw(plain, user.getPassword());
        } catch (RuntimeException e) {
            log.error("用户密码 hash 不合法 userId={}", user.getUserId(), e);
            return false;
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码至少 8 位且 UTF-8 编码不超过 72 字节");
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
                .map(u -> new StaffView(u.getUserId(), u.getEmail(), u.getPhone(),
                        u.getIdCardMasked(), u.getStatus(), u.getCreateAt()))
                .toList();
    }

    /**
     * 管理员创建 STAFF 账号。复用注册跨库两写逻辑,role 硬编码 STAFF(不暴露给 HTTP)。
     */
    public long createStaff(String email, String phone, String password, String idCard, long operatorId) {
        register(email, phone, password, idCard, "STAFF");
        log.info("管理员 {} 创建 STAFF 账号 email={}", operatorId, email);
        return 0;
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

    public record StaffView(Long userId, String email, String phone,
                            String idCardMasked, Integer status, LocalDateTime createAt) {
    }
}
