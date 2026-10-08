package com.cardamage.core.miniapp;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InitDataValidatorTest {

    private static final String TOKEN = "123456:TEST-token";
    private static final long NOW = 1_800_000_000L;

    private static Map<String, String> fields(long authDate) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("query_id", "AAE");
        f.put("user", "{\"id\":4242,\"first_name\":\"Матвей\",\"language_code\":\"ru\"}");
        f.put("auth_date", String.valueOf(authDate));
        return f;
    }

    @Test
    void validDataGivesTheUserId() {
        InitDataValidator v = new InitDataValidator(TOKEN, 86400);
        assertEquals(4242L, v.validate(v.sign(fields(NOW - 60)), NOW));
    }

    @Test
    void dataSignedWithAnotherBotTokenIsRejected() {
        String foreign = new InitDataValidator("999:other", 86400).sign(fields(NOW));
        assertThrows(InitDataValidator.InvalidInitDataException.class,
                () -> new InitDataValidator(TOKEN, 86400).validate(foreign, NOW));
    }

    @Test
    void changedFieldIsRejected() {
        InitDataValidator v = new InitDataValidator(TOKEN, 86400);
        String tampered = v.sign(fields(NOW)).replace("4242", "4243");
        assertThrows(InitDataValidator.InvalidInitDataException.class, () -> v.validate(tampered, NOW));
    }

    @Test
    void oldDataIsRejected() {
        InitDataValidator v = new InitDataValidator(TOKEN, 3600);
        assertThrows(InitDataValidator.InvalidInitDataException.class,
                () -> v.validate(v.sign(fields(NOW - 7200)), NOW));
    }

    @Test
    void missingDataIsRejected() {
        InitDataValidator v = new InitDataValidator(TOKEN, 3600);
        assertThrows(InitDataValidator.InvalidInitDataException.class, () -> v.validate("", NOW));
        assertThrows(InitDataValidator.InvalidInitDataException.class, () -> v.validate("auth_date=1", NOW));
    }

    @Test
    void rateLimiterAllowsUpToTheLimitPerWindow() {
        RateLimiter limiter = new RateLimiter(5, 3600);
        assertTrue(limiter.tryAcquire(1, 3, NOW));
        assertFalse(limiter.tryAcquire(1, 3, NOW + 10), "3 + 3 > 5: nothing is taken");
        assertTrue(limiter.tryAcquire(1, 2, NOW + 20));
        assertTrue(limiter.tryAcquire(2, 5, NOW + 20), "other users are independent");
        assertTrue(limiter.tryAcquire(1, 3, NOW + 3601), "the first 3 have left the window");
    }
}
