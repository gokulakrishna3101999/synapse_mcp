package com.synapsemcp.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * App and test share one Redis database (index 0); isolation is by key prefix instead of by
 * database index - {@code synapsemcp} for the app (`local`/`dev`/`prod`), {@code synapsemcp_test}
 * for {@code *IntegrationTest}s (`synapsemcp.redis.key-prefix`, per profile). Every Redis key any
 * component builds (rate limiting now; the embedding/OCR caches in later stages) should go through
 * this rather than building a raw key, so isolation can never be accidentally bypassed.
 */
@Component
public class RedisKeyPrefix {

    private final String prefix;

    public RedisKeyPrefix(@Value("${synapsemcp.redis.key-prefix}") String prefix) {
        this.prefix = prefix;
    }

    public String key(String suffix) {
        return prefix + ":" + suffix;
    }
}
