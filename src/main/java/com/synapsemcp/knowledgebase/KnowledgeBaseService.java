package com.synapsemcp.knowledgebase;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.RateLimitKind;
import com.synapsemcp.common.RateLimited;
import com.synapsemcp.document.DocumentRepository;
import com.synapsemcp.document.DocumentStatusSummary;
import com.synapsemcp.embedding.EmbeddingModelFactory;
import com.synapsemcp.ingestion.index.LuceneIndexManager;
import com.synapsemcp.tenant.ModelConfig;
import com.synapsemcp.tenant.ModelConfigRepository;
import com.synapsemcp.tenant.Tenant;
import com.synapsemcp.tenant.TenantRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final DocumentRepository documentRepository;
    private final EmbeddingModelFactory embeddingModelFactory;
    private final LuceneIndexManager luceneIndexManager;
    private final TransactionTemplate transactionTemplate;

    KnowledgeBaseService(
            KnowledgeBaseRepository knowledgeBaseRepository,
            KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository,
            ModelConfigRepository modelConfigRepository,
            TenantRepository tenantRepository,
            DocumentRepository documentRepository,
            EmbeddingModelFactory embeddingModelFactory,
            LuceneIndexManager luceneIndexManager,
            PlatformTransactionManager transactionManager) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeBaseModelConfigRepository = knowledgeBaseModelConfigRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.tenantRepository = tenantRepository;
        this.documentRepository = documentRepository;
        this.embeddingModelFactory = embeddingModelFactory;
        this.luceneIndexManager = luceneIndexManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * The embedding-dimension probe is a live network call to the tenant's configured provider
     * (Grooming #5b/#29) - deliberately run <b>outside</b> any transaction, before the short
     * pessimistic-lock section below, so a slow/hanging provider call never holds a pooled DB
     * connection or the per-tenant row lock idle (`plan.md` §9 2026-07-17).
     *
     * <p>{@link RateLimited} is placed here rather than on the private {@link
     * #probeEmbeddingDimension} it calls (mcp_plan.md Stage 3) - that method is invoked via
     * self-invocation from this one, which Spring AOP proxies never intercept regardless of
     * visibility, so this public, externally-invoked entry point is the only place in this call
     * path the aspect can actually apply.
     */
    @RateLimited(RateLimitKind.EMBEDDING)
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
        requireNameNotTaken(tenantId, name, null);

        KnowledgeBase knowledgeBase;
        try {
            knowledgeBase =
                    knowledgeBaseRepository.saveAndFlush(
                            KnowledgeBase.create(tenant, name, embeddingDim));
        } catch (DataIntegrityViolationException e) {
            throw duplicateNameConflict(name);
        }
        KnowledgeBaseModelConfig snapshot =
                KnowledgeBaseModelConfig.create(
                        knowledgeBase,
                        modelConfig.getChatProvider(),
                        modelConfig.getChatModel(),
                        modelConfig.getEmbeddingProvider(),
                        modelConfig.getEmbeddingModel(),
                        modelConfig.getProviderCredentials());
        knowledgeBaseModelConfigRepository.save(snapshot);

        return toResponse(knowledgeBase, DocumentStatusSummary.EMPTY);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeBaseResponse> listKnowledgeBases(UUID tenantId) {
        List<KnowledgeBase> knowledgeBases =
                knowledgeBaseRepository.findAllByTenant_IdOrderByNameAsc(tenantId);
        Map<UUID, List<DocumentRepository.StatusCount>> countsByKnowledgeBase = new HashMap<>();
        for (DocumentRepository.KnowledgeBaseStatusCount count :
                documentRepository.countByTenantIdGroupedByKnowledgeBaseAndStatus(tenantId)) {
            countsByKnowledgeBase
                    .computeIfAbsent(count.getKnowledgeBaseId(), id -> new ArrayList<>())
                    .add(count);
        }
        return knowledgeBases.stream()
                .map(
                        kb ->
                                toResponse(
                                        kb,
                                        DocumentStatusSummary.from(
                                                countsByKnowledgeBase.getOrDefault(
                                                        kb.getId(), List.of()))))
                .toList();
    }

    @Transactional
    public KnowledgeBaseResponse updateKnowledgeBase(
            UUID tenantId, UUID knowledgeBaseId, UpdateKnowledgeBaseRequest request) {
        KnowledgeBase knowledgeBase = requireOwnedKnowledgeBase(tenantId, knowledgeBaseId);
        String newName = request.name().trim();
        requireNameNotTaken(tenantId, newName, knowledgeBaseId);
        knowledgeBase.setName(newName);
        try {
            knowledgeBaseRepository.saveAndFlush(knowledgeBase);
        } catch (DataIntegrityViolationException e) {
            throw duplicateNameConflict(newName);
        }
        DocumentStatusSummary summary =
                DocumentStatusSummary.from(
                        documentRepository.countByKnowledgeBaseIdGroupedByStatus(knowledgeBaseId));
        return toResponse(knowledgeBase, summary);
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

    /**
     * User-requested (2026-07-22): a code-level pre-check for {@code create}/{@code rename}, ahead
     * of the database's own {@code uq_knowledge_bases_tenant_name_ci} constraint - gives a clean,
     * name-specific error on the common path instead of relying solely on a constraint-violation
     * catch. {@code excludingKnowledgeBaseId} lets a rename pass when the only "conflicting" row is
     * the knowledge base being renamed itself (e.g. a case-only change, or no change at all);
     * {@code create} passes {@code null}, which can never equal a real id, so every existing row
     * counts as a conflict.
     *
     * <p>This check alone is not concurrency-proof on its own - two concurrent calls could both
     * pass it before either commits (the same TOCTOU shape as Grooming #29's {@code
     * switch_knowledge_base} finding). {@code createWithinLock} closes that window for
     * create-vs-create by running under the same per-tenant {@code tenantRepository.lockById} lock
     * already used for the knowledge-base-count check; {@code updateKnowledgeBase} has no such lock
     * (a rename can race a concurrent create, or another concurrent rename), so both call sites
     * also catch the constraint violation itself via {@code saveAndFlush} as the ultimate backstop
     * - the database's own unique index is the only thing that can never be raced past.
     */
    private void requireNameNotTaken(UUID tenantId, String name, UUID excludingKnowledgeBaseId) {
        knowledgeBaseRepository
                .findByNameIgnoreCaseAndTenant_Id(name, tenantId)
                .filter(existing -> !Objects.equals(existing.getId(), excludingKnowledgeBaseId))
                .ifPresent(
                        existing -> {
                            throw duplicateNameConflict(name);
                        });
    }

    private ApiException duplicateNameConflict(String name) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "Conflict",
                "a knowledge base named '" + name + "' already exists");
    }

    private KnowledgeBaseResponse toResponse(
            KnowledgeBase knowledgeBase, DocumentStatusSummary documentStatusSummary) {
        return new KnowledgeBaseResponse(
                knowledgeBase.getId(),
                knowledgeBase.getName(),
                knowledgeBase.getEmbeddingDim(),
                documentStatusSummary);
    }
}
