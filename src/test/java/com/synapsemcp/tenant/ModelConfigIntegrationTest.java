package com.synapsemcp.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.AbstractIntegrationTest;
import com.synapsemcp.chat.ChatModelFactory;
import com.synapsemcp.common.ProviderCredentials;
import com.synapsemcp.common.ProviderCredentialsCodec;
import com.synapsemcp.embedding.EmbeddingModelFactory;
import com.synapsemcp.knowledgebase.KnowledgeBase;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfigRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ModelConfigIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantRepository tenantRepository;

    @Autowired private KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository;

    @Autowired private EntityManager entityManager;

    @Autowired private PlatformTransactionManager transactionManager;

    @Autowired private ChatModelFactory chatModelFactory;

    @Autowired private EmbeddingModelFactory embeddingModelFactory;

    @Autowired private ModelConfigRepository modelConfigRepository;

    @Autowired private ApplicationEventPublisher eventPublisher;

    private record TenantFixture(UUID tenantId, String apiKey) {}

    private TenantFixture createTenant(String name) {
        CreateTenantResponse response =
                restTemplate.postForObject(
                        "/api/v1/tenants",
                        new CreateTenantRequest(name),
                        CreateTenantResponse.class);
        return new TenantFixture(response.tenantId(), response.apiKey());
    }

    private HttpHeaders bearerHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        return headers;
    }

    private ResponseEntity<ModelConfigResponse> put(
            UUID tenantId, String apiKey, ConfigureModelRequest request) {
        return restTemplate.exchange(
                "/api/v1/tenants/{tenantId}/model-config",
                HttpMethod.PUT,
                new HttpEntity<>(request, bearerHeaders(apiKey)),
                ModelConfigResponse.class,
                tenantId);
    }

    /**
     * Raw {@code String} response, not deserialized into {@link ModelConfigResponse} - needed for
     * the concurrency test below, whose losing requests come back as an RFC 7807 problem-detail
     * body with a different shape.
     */
    private ResponseEntity<String> putRaw(
            UUID tenantId, String apiKey, ConfigureModelRequest request) {
        return restTemplate.exchange(
                "/api/v1/tenants/{tenantId}/model-config",
                HttpMethod.PUT,
                new HttpEntity<>(request, bearerHeaders(apiKey)),
                String.class,
                tenantId);
    }

    private ResponseEntity<ModelConfigResponse> get(UUID tenantId, String apiKey) {
        return restTemplate.exchange(
                "/api/v1/tenants/{tenantId}/model-config",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(apiKey)),
                ModelConfigResponse.class,
                tenantId);
    }

    /**
     * Commits immediately (unlike a {@code @Transactional} test method, which would roll back and
     * be invisible to the separate HTTP-request thread/connection the REST calls below run under).
     */
    private KnowledgeBaseModelConfig persistKnowledgeBaseModelConfig(
            UUID tenantId,
            String kbName,
            String chatProvider,
            String chatModel,
            String embeddingProvider,
            String embeddingModel,
            ProviderCredentials credentials) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return transactionTemplate.execute(
                status -> {
                    Tenant tenant = tenantRepository.getReferenceById(tenantId);
                    KnowledgeBase kb = new KnowledgeBase(tenant, kbName, 1536);
                    entityManager.persist(kb);
                    KnowledgeBaseModelConfig kbConfig =
                            new KnowledgeBaseModelConfig(
                                    kb,
                                    chatProvider,
                                    chatModel,
                                    embeddingProvider,
                                    embeddingModel,
                                    ProviderCredentialsCodec.encode(credentials));
                    entityManager.persist(kbConfig);
                    entityManager.flush();
                    return kbConfig;
                });
    }

    @Test
    void putCreatesAModelConfigAndGetReturnsTheSameData() {
        TenantFixture tenant = createTenant("PUT-GET tenant");
        ConfigureModelRequest request =
                new ConfigureModelRequest(
                        "openai",
                        "gpt-4o",
                        "openai",
                        "text-embedding-3-small",
                        "sk-chat",
                        "sk-embed");

        ResponseEntity<ModelConfigResponse> putResponse =
                put(tenant.tenantId(), tenant.apiKey(), request);

        assertThat(putResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        ModelConfigResponse putBody = putResponse.getBody();
        assertThat(putBody).isNotNull();
        assertThat(putBody.tenantId()).isEqualTo(tenant.tenantId());
        assertThat(putBody.chatProvider()).isEqualTo("openai");
        assertThat(putBody.createdAt()).isNotNull();

        ResponseEntity<ModelConfigResponse> getResponse = get(tenant.tenantId(), tenant.apiKey());

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isEqualTo(putBody);
    }

    @Test
    void credentialsAreNeverEchoedBackInEitherResponse() {
        TenantFixture tenant = createTenant("No-echo tenant");
        ConfigureModelRequest request =
                new ConfigureModelRequest(
                        "openai",
                        "gpt-4o",
                        "openai",
                        "text-embedding-3-small",
                        "sk-super-secret-chat",
                        "sk-super-secret-embed");

        ResponseEntity<String> putResponse =
                restTemplate.exchange(
                        "/api/v1/tenants/{tenantId}/model-config",
                        HttpMethod.PUT,
                        new HttpEntity<>(request, bearerHeaders(tenant.apiKey())),
                        String.class,
                        tenant.tenantId());
        ResponseEntity<String> getResponse =
                restTemplate.exchange(
                        "/api/v1/tenants/{tenantId}/model-config",
                        HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders(tenant.apiKey())),
                        String.class,
                        tenant.tenantId());

        assertThat(putResponse.getBody())
                .doesNotContain("sk-super-secret-chat", "sk-super-secret-embed");
        assertThat(getResponse.getBody())
                .doesNotContain("sk-super-secret-chat", "sk-super-secret-embed");
    }

    /**
     * Found live (audit session, 2026-07-17): a cross-tenant caller sending an *invalid* body used
     * to get {@code 400} (revealing the required-field shape) instead of {@code 403} -
     * {@code @Valid} request-body validation ran during argument resolution, before {@code
     * ModelConfigController}'s own-tenant check in the method body ever got a chance to run. Fixed
     * via {@link TenantOwnershipInterceptor}, which runs in {@code preHandle} - before argument
     * resolution, so authorization now always wins regardless of body validity.
     */
    @Test
    void crossTenantRequestWithAnInvalidBodyStillReturns403NotAValidationError() {
        TenantFixture tenantA = createTenant("Ordering tenant A");
        TenantFixture tenantB = createTenant("Ordering tenant B");
        ConfigureModelRequest invalidBody = new ConfigureModelRequest("", "", "", "", null, null);

        ResponseEntity<String> response = putRaw(tenantA.tenantId(), tenantB.apiKey(), invalidBody);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aTenantCannotReadOrWriteAnotherTenantsModelConfig() {
        TenantFixture tenantA = createTenant("Tenant A");
        TenantFixture tenantB = createTenant("Tenant B");
        ConfigureModelRequest request =
                new ConfigureModelRequest(
                        "openai", "gpt-4o", "openai", "text-embedding-3-small", null, null);

        ResponseEntity<ModelConfigResponse> crossPut =
                put(tenantA.tenantId(), tenantB.apiKey(), request);
        ResponseEntity<ModelConfigResponse> crossGet = get(tenantA.tenantId(), tenantB.apiKey());

        assertThat(crossPut.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(crossGet.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsAnUnsupportedProviderWith422() {
        TenantFixture tenant = createTenant("Bad provider tenant");
        ConfigureModelRequest request =
                new ConfigureModelRequest(
                        "not-a-real-provider", "x", "openai", "text-embedding-3-small", null, null);

        ResponseEntity<ModelConfigResponse> response =
                put(tenant.tenantId(), tenant.apiKey(), request);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
    }

    /**
     * Found live (audit session, 2026-07-17): with a valid API key, a malformed {@code tenantId}
     * path segment (typo, bad copy-paste) threw {@code MethodArgumentTypeMismatchException} during
     * Spring MVC's path-variable binding - before {@code ModelConfigController} even ran - and
     * leaked as an unhandled {@code 500}.
     */
    @Test
    void malformedTenantIdInPathReturns400NotAServerError() {
        TenantFixture tenant = createTenant("Malformed path tenant");

        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/api/v1/tenants/not-a-uuid/model-config",
                        HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders(tenant.apiKey())),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getReturns404BeforeAnyModelConfigHasEverBeenSet() {
        TenantFixture tenant = createTenant("Never configured tenant");

        ResponseEntity<ModelConfigResponse> response = get(tenant.tenantId(), tenant.apiKey());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void groomingTwentyTwo_updatingTheTenantConfigSyncsOnlyTheMatchingProviderCredentialHalf() {
        TenantFixture tenant = createTenant("Auto-sync tenant");
        put(
                tenant.tenantId(),
                tenant.apiKey(),
                new ConfigureModelRequest(
                        "openai",
                        "gpt-4o-mini",
                        "openai",
                        "text-embedding-3-small",
                        "sk-chat-1",
                        "sk-embed-1"));

        KnowledgeBaseModelConfig kbConfig =
                persistKnowledgeBaseModelConfig(
                        tenant.tenantId(),
                        "kb-1",
                        "openai",
                        "gpt-4o-mini",
                        "ollama",
                        "nomic-embed-text",
                        new ProviderCredentials("kb-old-chat", "kb-old-embed"));

        put(
                tenant.tenantId(),
                tenant.apiKey(),
                new ConfigureModelRequest(
                        "openai",
                        "gpt-4o-mini",
                        "openai",
                        "text-embedding-3-small",
                        "sk-chat-2",
                        "sk-embed-2"));

        KnowledgeBaseModelConfig reloaded =
                knowledgeBaseModelConfigRepository.findById(kbConfig.getId()).orElseThrow();
        ProviderCredentials reloadedCredentials =
                ProviderCredentialsCodec.decode(reloaded.getProviderCredentials());

        assertThat(reloadedCredentials.chatApiKey()).isEqualTo("sk-chat-2");
        assertThat(reloadedCredentials.embeddingApiKey()).isEqualTo("kb-old-embed");
    }

    /**
     * Every other test of {@code ModelConfigUpdatedEvent} eviction (both factories' own unit tests)
     * calls the {@code @EventListener} method directly as a plain Java call - none of them prove
     * the annotation is actually wired up to Spring's real {@code ApplicationEventPublisher} inside
     * a live {@code ApplicationContext}. This test never touches the listener method directly: it
     * drives the cache purely by going through the real {@code PUT} endpoint (which is what
     * actually calls {@code eventPublisher.publishEvent(...)} in {@code ModelConfigService}),
     * proving Stage 2's REST layer, service layer, and the two factory beans are genuinely wired
     * together end-to-end, not just each individually correct in isolation.
     */
    @Test
    void updatingModelConfigThroughTheRealEndpointEvictsBothFactoriesCachesViaTheRealEventBus() {
        TenantFixture tenant = createTenant("Event-bus tenant");
        put(
                tenant.tenantId(),
                tenant.apiKey(),
                new ConfigureModelRequest(
                        "ollama", "llama3", "ollama", "nomic-embed-text", null, null));

        ChatModel firstChatModel = chatModelFactory.getChatModel(tenant.tenantId());
        EmbeddingModel firstEmbeddingModel =
                embeddingModelFactory.getEmbeddingModel(tenant.tenantId());

        put(
                tenant.tenantId(),
                tenant.apiKey(),
                new ConfigureModelRequest(
                        "ollama", "llama3.1", "ollama", "mxbai-embed-large", null, null));

        ChatModel secondChatModel = chatModelFactory.getChatModel(tenant.tenantId());
        EmbeddingModel secondEmbeddingModel =
                embeddingModelFactory.getEmbeddingModel(tenant.tenantId());

        assertThat(secondChatModel).isNotSameAs(firstChatModel);
        assertThat(secondEmbeddingModel).isNotSameAs(firstEmbeddingModel);
    }

    /**
     * Found live (audit session, 2026-07-17): firing this exact scenario for real against a running
     * app produced 9 raw {@code 500}s out of 10 concurrent requests - {@code
     * ModelConfigService.configureModel()}'s find-or-create has no locking, so every loser hits
     * {@code model_configs.tenant_id}'s unique constraint. After the fix, losers get a proper
     * {@code 409 Conflict} instead, and exactly one request wins with {@code 200}.
     */
    @Test
    void concurrentFirstTimeModelConfigWritesForTheSameTenantNeverCrashWith500() throws Exception {
        TenantFixture tenant = createTenant("Concurrent config tenant");
        ConfigureModelRequest request =
                new ConfigureModelRequest(
                        "ollama", "llama3", "ollama", "nomic-embed-text", null, null);

        int concurrency = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(
                        executor.submit(() -> putRaw(tenant.tenantId(), tenant.apiKey(), request)));
            }
            List<Integer> statusCodes = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                statusCodes.add(future.get().getStatusCode().value());
            }

            // Not "exactly one 200": configureModel() is find-or-create, so a request whose SELECT
            // runs *after* an earlier request's INSERT has already committed legitimately sees the
            // row and UPDATEs it (also a 200) - only requests that overlap in the no-row-yet window
            // collide on the INSERT and lose with a 409. The only real invariant is "never a 500".
            assertThat(statusCodes)
                    .as("every response must be 200 or 409, never a 500: %s", statusCodes)
                    .allMatch(code -> code == 200 || code == 409);
            assertThat(statusCodes)
                    .as("at least one request must succeed: %s", statusCodes)
                    .contains(200);
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Proves the mechanism behind the cache-staleness race found by code inspection (audit session,
     * 2026-07-17): {@code ModelConfigService.configureModel()} publishes {@code
     * ModelConfigUpdatedEvent} synchronously from inside its own {@code @Transactional} method,
     * before the transaction commits. With a plain {@code @EventListener}, eviction would happen
     * mid-transaction, leaving a window where a concurrent {@code getChatModel} call rebuilds the
     * cache from the still-uncommitted (old) row - and that stale entry would never be evicted
     * again. This test drives the same shape directly: publish the event from inside an still-open
     * transaction and assert the cache is untouched, then let the transaction commit and assert
     * eviction happens immediately after.
     */
    @Test
    void cacheIsNotEvictedUntilTheUpdatingTransactionActuallyCommits() {
        TenantFixture tenant = createTenant("Eviction timing tenant");
        put(
                tenant.tenantId(),
                tenant.apiKey(),
                new ConfigureModelRequest(
                        "ollama", "llama3", "ollama", "nomic-embed-text", null, null));
        ChatModel beforeUpdate = chatModelFactory.getChatModel(tenant.tenantId());

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ChatModel[] duringTransaction = new ChatModel[1];
        transactionTemplate.execute(
                status -> {
                    ModelConfig modelConfig =
                            modelConfigRepository.findByTenantId(tenant.tenantId()).orElseThrow();
                    modelConfig.setChatModel("llama3.1");
                    modelConfigRepository.saveAndFlush(modelConfig);
                    eventPublisher.publishEvent(
                            new ModelConfigUpdatedEvent(this, tenant.tenantId()));

                    duringTransaction[0] = chatModelFactory.getChatModel(tenant.tenantId());
                    return null;
                });

        assertThat(duringTransaction[0])
                .as("cache must still serve the old instance while the update is still uncommitted")
                .isSameAs(beforeUpdate);

        ChatModel afterCommit = chatModelFactory.getChatModel(tenant.tenantId());
        assertThat(afterCommit)
                .as(
                        "cache must be evicted and rebuilt immediately after the update transaction commits")
                .isNotSameAs(beforeUpdate);
    }

    @Test
    void updatingOneTenantsModelConfigDoesNotEvictAnotherTenantsCachedFactoryEntries() {
        TenantFixture tenantA = createTenant("Cache isolation tenant A");
        TenantFixture tenantB = createTenant("Cache isolation tenant B");
        put(
                tenantA.tenantId(),
                tenantA.apiKey(),
                new ConfigureModelRequest(
                        "ollama", "llama3", "ollama", "nomic-embed-text", null, null));
        put(
                tenantB.tenantId(),
                tenantB.apiKey(),
                new ConfigureModelRequest(
                        "ollama", "llama3", "ollama", "nomic-embed-text", null, null));

        ChatModel tenantAFirst = chatModelFactory.getChatModel(tenantA.tenantId());

        put(
                tenantB.tenantId(),
                tenantB.apiKey(),
                new ConfigureModelRequest(
                        "ollama", "llama3.1", "ollama", "nomic-embed-text", null, null));

        ChatModel tenantASecond = chatModelFactory.getChatModel(tenantA.tenantId());

        assertThat(tenantASecond).isSameAs(tenantAFirst);
    }
}
