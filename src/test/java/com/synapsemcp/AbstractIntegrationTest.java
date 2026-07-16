package com.synapsemcp;

import com.synapsemcp.common.RedisKeyPrefix;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for {@code *IntegrationTest}s: a real, full Spring context against the isolated {@code
 * synapsemcp_test} Postgres database (`test` profile, {@code ddl-auto: create-drop} - every run
 * gets a genuinely fresh schema) - no ephemeral containers, no Docker (plan.md §9, rag_plan.md
 * Stage 0). Requires native Postgres already running with {@code DB_URL}/{@code DB_USERNAME}/{@code
 * DB_PASSWORD} exported and the {@code synapsemcp_test} database + {@code vector} extension already
 * created (bootstrap runners are {@code local}/{@code dev}-only, rag_plan.md Stage 0.5 Grooming
 * #18).
 *
 * <p>Also deletes every {@code synapsemcp_test:*}-prefixed Redis key before every test method: the
 * app and the test suite share one Redis database (index 0), isolated by key prefix rather than by
 * database index (plan.md §9, 2026-07-16, {@link RedisKeyPrefix}) - so this must delete only its
 * own prefix, never {@code FLUSHDB}, which would also wipe the app's real {@code synapsemcp:*}
 * data. Unlike Postgres's {@code create-drop}, Redis has no automatic per-context reset, and {@link
 * TestRestTemplate} calls all originate from the same loopback address, so anything keyed by remote
 * address (e.g. {@code TenantCreationRateLimitFilter}) would otherwise leak state between tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @Autowired private StringRedisTemplate redisTemplate;

    @Autowired private RedisKeyPrefix redisKeyPrefix;

    @BeforeEach
    void deleteTestPrefixedRedisKeys() {
        Set<String> keys = redisTemplate.keys(redisKeyPrefix.key("*"));
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
