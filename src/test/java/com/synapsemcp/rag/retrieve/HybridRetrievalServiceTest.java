package com.synapsemcp.rag.retrieve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.IngestionStatus;
import com.synapsemcp.document.Document;
import com.synapsemcp.ingestion.embed.ChunkEmbeddingService;
import com.synapsemcp.ingestion.index.LuceneIndexManager;
import com.synapsemcp.knowledgebase.KnowledgeBase;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfigRepository;
import com.synapsemcp.knowledgebase.KnowledgeBaseRepository;
import com.synapsemcp.rag.chunk.Chunk;
import com.synapsemcp.rag.chunk.ChunkRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HybridRetrievalServiceTest {

    private final KnowledgeBaseRepository knowledgeBaseRepository =
            mock(KnowledgeBaseRepository.class);
    private final KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository =
            mock(KnowledgeBaseModelConfigRepository.class);
    private final ChunkRepository chunkRepository = mock(ChunkRepository.class);
    private final ChunkEmbeddingService chunkEmbeddingService = mock(ChunkEmbeddingService.class);
    private final VectorSearchService vectorSearchService = mock(VectorSearchService.class);
    private final LuceneIndexManager luceneIndexManager = mock(LuceneIndexManager.class);
    private final LlmRerankerService llmRerankerService = mock(LlmRerankerService.class);

    private HybridRetrievalService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID knowledgeBaseId = UUID.randomUUID();
    private KnowledgeBase knowledgeBase;
    private KnowledgeBaseModelConfig kbConfig;

    @BeforeEach
    void setUp() throws Exception {
        service =
                new HybridRetrievalService(
                        knowledgeBaseRepository,
                        knowledgeBaseModelConfigRepository,
                        chunkRepository,
                        chunkEmbeddingService,
                        vectorSearchService,
                        luceneIndexManager,
                        llmRerankerService);

        knowledgeBase = KnowledgeBase.create(null, "kb", 1536);
        setId(knowledgeBase, knowledgeBaseId);
        kbConfig =
                KnowledgeBaseModelConfig.create(
                        knowledgeBase,
                        "openai",
                        "gpt-4o",
                        "openai",
                        "text-embedding-3-small",
                        "creds");

        when(knowledgeBaseRepository.findByIdAndTenant_Id(knowledgeBaseId, tenantId))
                .thenReturn(Optional.of(knowledgeBase));
        when(knowledgeBaseModelConfigRepository.findByKnowledgeBase_Id(knowledgeBaseId))
                .thenReturn(Optional.of(kbConfig));
    }

    @Test
    void unknownOrCrossTenantKnowledgeBaseReturns404() {
        when(knowledgeBaseRepository.findByIdAndTenant_Id(knowledgeBaseId, tenantId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.search(
                                        tenantId,
                                        knowledgeBaseId,
                                        new SearchRequest("q", null, null, null, false)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus().value())
                .isEqualTo(404);
    }

    @Test
    void keywordModeNeverEmbedsTheQuery() throws Exception {
        Chunk chunk = newChunk("matched content");
        when(luceneIndexManager.search(eq(knowledgeBaseId), eq("hello"), anyInt()))
                .thenReturn(List.of(new ScoredChunkId(chunk.getId(), 5.0)));
        when(chunkRepository.findByIdInFetchDocument(List.of(chunk.getId())))
                .thenReturn(List.of(chunk));

        List<SearchResultChunk> results =
                service.search(
                        tenantId,
                        knowledgeBaseId,
                        new SearchRequest("hello", null, null, SearchMode.KEYWORD, false));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("matched content");
        verify(chunkEmbeddingService, never()).embed(any(), any());
        verify(vectorSearchService, never()).search(any(), any(), anyInt(), any(), anyInt());
    }

    @Test
    void vectorModeNeverQueriesLucene() throws Exception {
        Chunk chunk = newChunk("vector matched content");
        when(chunkEmbeddingService.embed(eq(kbConfig), eq(List.of("hello"))))
                .thenReturn(List.of(new float[] {1f, 2f}));
        when(vectorSearchService.search(
                        eq(tenantId), eq(knowledgeBaseId), eq(1536), any(), anyInt()))
                .thenReturn(List.of(new ScoredChunkId(chunk.getId(), 0.9)));
        when(chunkRepository.findByIdInFetchDocument(List.of(chunk.getId())))
                .thenReturn(List.of(chunk));

        List<SearchResultChunk> results =
                service.search(
                        tenantId,
                        knowledgeBaseId,
                        new SearchRequest("hello", null, null, SearchMode.VECTOR, false));

        assertThat(results).hasSize(1);
        verify(luceneIndexManager, never()).search(any(), any(), anyInt());
    }

    @Test
    void hybridModeMergesBothRankedListsViaRrf() throws Exception {
        Chunk inBoth = newChunk("in both lists");
        Chunk vectorOnly = newChunk("vector only");
        when(chunkEmbeddingService.embed(eq(kbConfig), eq(List.of("hello"))))
                .thenReturn(List.of(new float[] {1f, 2f}));
        when(vectorSearchService.search(
                        eq(tenantId), eq(knowledgeBaseId), eq(1536), any(), anyInt()))
                .thenReturn(
                        List.of(
                                new ScoredChunkId(inBoth.getId(), 0.9),
                                new ScoredChunkId(vectorOnly.getId(), 0.5)));
        when(luceneIndexManager.search(eq(knowledgeBaseId), eq("hello"), anyInt()))
                .thenReturn(List.of(new ScoredChunkId(inBoth.getId(), 8.0)));
        when(chunkRepository.findByIdInFetchDocument(any()))
                .thenReturn(List.of(inBoth, vectorOnly));

        List<SearchResultChunk> results =
                service.search(
                        tenantId,
                        knowledgeBaseId,
                        new SearchRequest("hello", null, null, SearchMode.HYBRID, false));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).chunkId()).isEqualTo(inBoth.getId());
    }

    @Test
    void truncatesToTopK() {
        Chunk chunk1 = newChunk("one");
        Chunk chunk2 = newChunk("two");
        when(chunkEmbeddingService.embed(eq(kbConfig), eq(List.of("q"))))
                .thenReturn(List.of(new float[] {1f}));
        when(vectorSearchService.search(
                        eq(tenantId), eq(knowledgeBaseId), eq(1536), any(), anyInt()))
                .thenReturn(
                        List.of(
                                new ScoredChunkId(chunk1.getId(), 0.9),
                                new ScoredChunkId(chunk2.getId(), 0.5)));
        when(chunkRepository.findByIdInFetchDocument(any())).thenReturn(List.of(chunk1, chunk2));

        List<SearchResultChunk> results =
                service.search(
                        tenantId,
                        knowledgeBaseId,
                        new SearchRequest("q", 1, null, SearchMode.VECTOR, false));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).chunkId()).isEqualTo(chunk1.getId());
    }

    @Test
    void staleLuceneEntryWithNoMatchingChunkRowIsSkipped() throws Exception {
        UUID staleChunkId = UUID.randomUUID();
        when(luceneIndexManager.search(eq(knowledgeBaseId), eq("q"), anyInt()))
                .thenReturn(List.of(new ScoredChunkId(staleChunkId, 1.0)));
        when(chunkRepository.findByIdInFetchDocument(List.of(staleChunkId))).thenReturn(List.of());

        List<SearchResultChunk> results =
                service.search(
                        tenantId,
                        knowledgeBaseId,
                        new SearchRequest("q", null, null, SearchMode.KEYWORD, false));

        assertThat(results).isEmpty();
    }

    @Test
    void rerankTrueInvokesTheRerankerOnTheHydratedResults() throws Exception {
        Chunk chunk1 = newChunk("first");
        Chunk chunk2 = newChunk("second");
        when(luceneIndexManager.search(eq(knowledgeBaseId), eq("hello"), anyInt()))
                .thenReturn(
                        List.of(
                                new ScoredChunkId(chunk1.getId(), 5.0),
                                new ScoredChunkId(chunk2.getId(), 3.0)));
        when(chunkRepository.findByIdInFetchDocument(any())).thenReturn(List.of(chunk1, chunk2));
        List<SearchResultChunk> reranked =
                List.of(
                        new SearchResultChunk(
                                chunk2.getId(),
                                chunk2.getDocumentId(),
                                "file.txt",
                                "second",
                                99.0,
                                java.util.Map.of()));
        when(llmRerankerService.rerank(eq(kbConfig), eq("hello"), any())).thenReturn(reranked);

        List<SearchResultChunk> results =
                service.search(
                        tenantId,
                        knowledgeBaseId,
                        new SearchRequest("hello", null, null, SearchMode.KEYWORD, true));

        assertThat(results).isEqualTo(reranked);
    }

    @Test
    void rerankIsSkippedWhenThereIsAtMostOneResult() throws Exception {
        Chunk chunk = newChunk("only match");
        when(luceneIndexManager.search(eq(knowledgeBaseId), eq("q"), anyInt()))
                .thenReturn(List.of(new ScoredChunkId(chunk.getId(), 5.0)));
        when(chunkRepository.findByIdInFetchDocument(List.of(chunk.getId())))
                .thenReturn(List.of(chunk));

        service.search(
                tenantId,
                knowledgeBaseId,
                new SearchRequest("q", null, null, SearchMode.KEYWORD, true));

        verify(llmRerankerService, never()).rerank(any(), any(), any());
    }

    private static Chunk newChunk(String content) {
        Document document =
                Document.create(
                        UUID.randomUUID(),
                        null,
                        "file.txt",
                        "text/plain",
                        IngestionStatus.READY,
                        "hash");
        setId(document, UUID.randomUUID());
        Chunk chunk = Chunk.create(UUID.randomUUID(), UUID.randomUUID(), document, 0, content);
        setId(chunk, UUID.randomUUID());
        return chunk;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Field idField = entity.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
