package com.geek.codeguard.auth.service;

import com.geek.codeguard.auth.model.User;
import com.geek.codeguard.common.enums.ErrorCodeEnum;
import com.geek.codeguard.common.exception.BusinessException;
import com.geek.codeguard.config.CodeGuardProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * 无状态签名 token：base64url(payload).base64url(hmac)
 * payload = {"uid": userId, "exp": epochSeconds}
 */
@Service
public class TokenService {

    private final CodeGuardProperties props;
    private final UserStore userStore;
    private final ObjectMapper mapper = new ObjectMapper();

    public TokenService(CodeGuardProperties props, UserStore userStore) {
        this.props = props;
        this.userStore = userStore;
    }

    public String issue(User user) {
        return issue(user, props.getTokenTtlHours());
    }

    /** remember=true 时使用更长的有效期 */
    public String issue(User user, boolean remember) {
        return issue(user, remember ? props.getTokenRememberHours() : props.getTokenTtlHours());
    }

    public String issue(User user, long ttlHours) {
        long exp = Instant.now().getEpochSecond() + ttlHours * 3600;
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"uid\":\"" + user.getId() + "\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return payload + "." + sign(payload);
    }

    public User verify(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCodeEnum.TOKEN_INVALID);
        }
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            throw new BusinessException(ErrorCodeEnum.TOKEN_INVALID);
        }
        String expected = sign(parts[0]);
        if (!java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCodeEnum.TOKEN_INVALID);
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(parts[0]);
            JsonNode node = mapper.readTree(raw);
            long exp = node.get("exp").asLong();
            if (Instant.now().getEpochSecond() > exp) {
                throw new BusinessException(ErrorCodeEnum.TOKEN_INVALID, "登录已过期");
            }
            String uid = node.get("uid").asText();
            return userStore.findById(uid)
                    .filter(User::isEnabled)
                    .orElseThrow(() -> new BusinessException(ErrorCodeEnum.TOKEN_INVALID));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCodeEnum.TOKEN_INVALID);
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.getTokenSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("token 签名失败", e);
        }
    }
}
