package com.synapsemcp.knowledgebase;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.embedding.EmbeddingModelFactory;
import com.synapsemcp.ingestion.index.LuceneIndexManager;
import com.synapsemcp.tenant.ModelConfig;
import com.synapsemcp.tenant.ModelConfigRepository;
import com.synapsemcp.tenant.Tenant;
import com.synapsemcp.tenant.TenantRepository;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** rag_plan.md Stage 3: create/list/update/delete knowledge_bases. */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final Set<Integer> SUPPORTED_DIMENSIONS =
            Set.of(384, 512, 768, 1024, 1536, 3072);
    private static final int MAX_KNOWLEDGE_BASES_PER_TENANT = 10;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final TenantRepository tenantRepository;
    private final EmbeddingModelFactory embeddingModelFactory;
    private final LuceneIndexManager luceneIndexManager;
    private final TransactionTemplate transactionTemplate;

    KnowledgeBaseService(
            KnowledgeBaseRepository knowledgeBaseRepository,
            KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository,
            ModelConfigRepository modelConfigRepository,
            TenantRepository tenantRepository,
            EmbeddingModelFactory embeddingModelFactory,
            LuceneIndexManager luceneIndexManager,
            PlatformTransactionManager transactionManager) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeBaseModelConfigRepository = knowledgeBaseModelConfigRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.tenantRepository = tenantRepository;
        this.embeddingModelFactory = embeddingModelFactory;
        this.luceneIndexManager = luceneIndexManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * The embedding-dimension probe is a live network call to the tenant's configured provider
     * (Grooming #5b/#29) - deliberately run <b>outside</b> any transaction, before the short
     * pessimistic-lock section below, so a slow/hanging provider call never holds a pooled DB
     * connection or the per-tenant row lock idle (`plan.md` §9 2026-07-17).
     */
    public KnowledgeBaseResponse createKnowledgeBase(
            UUID tenantId, CreateKnowledgeBaseRequest request) {
        ModelConfig modelConfig =
                modelConfigRepository
                        .findByTenantId(tenantId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.UNPROCESSABLE_ENTITY,
                                                "Unprocessable Entity",
                                                "model config not set for tenant"));

        int embeddingDim = probeEmbeddingDimension(tenantId);
        if (!SUPPORTED_DIMENSIONS.contains(embeddingDim)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unprocessable Entity",
                    "embedding model dimension " + embeddingDim + " is not supported");
        }

        return transactionTemplate.execute(
                status ->
                        createWithinLock(
                                tenantId, request.name().trim(), embeddingDim, modelConfig));
    }

    /**
     * Both {@code getEmbeddingModel} (client construction) and {@code dimensions} (the actual
     * network call) are wrapped - found live (audit session, 2026-07-17) that a missing-credential
     * failure for a provider requiring one throws at <i>construction</i> time (e.g. OpenAI's SDK:
     * {@code IllegalStateException: At least one credential source must be specified}), not inside
     * {@code dimensions()} - an earlier version of this method only wrapped the latter, letting a
     * construction-time failure leak as an unhandled {@code 500} instead of this method's intended
     * {@code 422}.
     */
    private int probeEmbeddingDimension(UUID tenantId) {
        try {
            EmbeddingModel embeddingModel = embeddingModelFactory.getEmbeddingModel(tenantId);
            return embeddingModel.dimensions();
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unprocessable Entity",
                    "embedding model failed validation: " + e.getMessage());
        }
    }

    private KnowledgeBaseResponse createWithinLock(
            UUID tenantId, String name, int embeddingDim, ModelConfig modelConfig) {
        Tenant tenant = tenantRepository.lockById(tenantId).orElseThrow();

        long existingCount = knowledgeBaseRepository.countByTenant_Id(tenantId);
        if (existingCount >= MAX_KNOWLEDGE_BASES_PER_TENANT) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unprocessable Entity",
                    "maximum of " + MAX_KNOWLEDGE_BASES_PER_TENANT + " knowledge bases per tenant");
        }

        KnowledgeBase knowledgeBase =
                knowledgeBaseRepository.save(KnowledgeBase.create(tenant, name, embeddingDim));
        KnowledgeBaseModelConfig snapshot =
                KnowledgeBaseModelConfig.create(
                        knowledgeBase,
                        modelConfig.getChatProvider(),
                        modelConfig.getChatModel(),
                        modelConfig.getEmbeddingProvider(),
                        modelConfig.getEmbeddingModel(),
                        modelConfig.getProviderCredentials());
        knowledgeBaseModelConfigRepository.save(snapshot);

        return toResponse(knowledgeBase);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeBaseResponse> listKnowledgeBases(UUID tenantId) {
        return knowledgeBaseRepository.findAllByTenant_IdOrderByNameAsc(tenantId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public KnowledgeBaseResponse updateKnowledgeBase(
            UUID tenantId, UUID knowledgeBaseId, UpdateKnowledgeBaseRequest request) {
        KnowledgeBase knowledgeBase = requireOwnedKnowledgeBase(tenantId, knowledgeBaseId);
        knowledgeBase.setName(request.name().trim());
        return toResponse(knowledgeBase);
    }

    @Transactional
    /**
     * Postgres cascade removes every {@code documents}/{@code chunks}/{@code ingestion_jobs} row
     * for this knowledge_base, but has no way to reach {@link LuceneIndexManager}'s on-disk index
     * directory - found live (Grooming #71, `plan.md` §9 2026-07-17) leaving every deleted
     * knowledge_base's Lucene directory orphaned on disk forever, invisible to the reconciliation
     * cron (which only iterates knowledge bases that still exist). The Postgres delete is the
     * authoritative, user-visible outcome - a filesystem cleanup failure is logged, not propagated,
     * since failing this request over a already-gone-from-Postgres knowledge_base would be
     * misleading.
     */
    public void deleteKnowledgeBase(UUID tenantId, UUID knowledgeBaseId) {
        KnowledgeBase knowledgeBase = requireOwnedKnowledgeBase(tenantId, knowledgeBaseId);
        knowledgeBaseRepository.delete(knowledgeBase);
        try {
            luceneIndexManager.deleteIndex(knowledgeBaseId);
        } catch (IOException e) {
            log.error(
                    "failed to delete Lucene index directory for deleted knowledge_base {} - it is"
                            + " now orphaned on disk",
                    knowledgeBaseId,
                    e);
        }
    }

    /**
     * A knowledge_base belonging to a different tenant is treated identically to a nonexistent id -
     * both return {@code 404} - so a caller can never distinguish "not yours" from "doesn't exist"
     * (rag_plan.md Stage 3, `plan.md` §9 2026-07-17).
     */
    private KnowledgeBase requireOwnedKnowledgeBase(UUID tenantId, UUID knowledgeBaseId) {
        return knowledgeBaseRepository
                .findByIdAndTenant_Id(knowledgeBaseId, tenantId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        HttpStatus.NOT_FOUND,
                                        "Not Found",
                                        "knowledge base not found"));
    }

    private KnowledgeBaseResponse toResponse(KnowledgeBase knowledgeBase) {
        return new KnowledgeBaseResponse(
                knowledgeBase.getId(), knowledgeBase.getName(), knowledgeBase.getEmbeddingDim());
    }
}
