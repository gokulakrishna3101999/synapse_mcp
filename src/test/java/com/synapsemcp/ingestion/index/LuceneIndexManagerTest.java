package com.synapsemcp.ingestion.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.rag.chunk.Chunk;
import com.synapsemcp.rag.retrieve.ScoredChunkId;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * No dedicated test existed for this class before (rag_plan.md Stage 5 was only verified via
 * scratch probes and indirectly via *IntegrationTests) - covers the core read/write/delete methods
 * against a real Lucene index on disk, plus {@link LuceneIndexManager#deleteIndex} (Grooming #71,
 * found live during a post-Stage-5 "final thorough validation" pass, `plan.md` §9 2026-07-17).
 */
class LuceneIndexManagerTest {

    @TempDir private Path tempDir;

    private LuceneIndexManager manager;

    @BeforeEach
    void setUp() {
        manager = new LuceneIndexManager(tempDir.toString());
    }

    @Test
    void returnsEmptyForAKnowledgeBaseWithNoIndexYet() throws Exception {
        assertThat(manager.listIndexedChunkIds(UUID.randomUUID())).isEmpty();
    }

    @Test
    void indexesAndListsChunks() throws Exception {
        UUID kbId = UUID.randomUUID();
        Chunk chunk1 = newChunk("first chunk content");
        Chunk chunk2 = newChunk("second chunk content");

        manager.indexChunks(kbId, List.of(chunk1, chunk2));

        Set<String> indexed = manager.listIndexedChunkIds(kbId);
        assertThat(indexed)
                .containsExactlyInAnyOrder(chunk1.getId().toString(), chunk2.getId().toString());
    }

    @Test
    void deleteChunksRemovesOnlyTheSpecifiedChunks() throws Exception {
        UUID kbId = UUID.randomUUID();
        Chunk chunk1 = newChunk("keep me");
        Chunk chunk2 = newChunk("remove me");
        manager.indexChunks(kbId, List.of(chunk1, chunk2));

        manager.deleteChunks(kbId, List.of(chunk2.getId().toString()));

        assertThat(manager.listIndexedChunkIds(kbId)).containsExactly(chunk1.getId().toString());
    }

    @Test
    void indexingIsIdempotentByChunkId() throws Exception {
        UUID kbId = UUID.randomUUID();
        Chunk chunk = newChunk("version one");

        manager.indexChunks(kbId, List.of(chunk));
        manager.indexChunks(kbId, List.of(chunk));

        assertThat(manager.listIndexedChunkIds(kbId)).hasSize(1);
    }

    @Test
    void keepsDifferentKnowledgeBasesIsolated() throws Exception {
        UUID kbId1 = UUID.randomUUID();
        UUID kbId2 = UUID.randomUUID();
        Chunk chunk1 = newChunk("kb one content");
        Chunk chunk2 = newChunk("kb two content");

        manager.indexChunks(kbId1, List.of(chunk1));
        manager.indexChunks(kbId2, List.of(chunk2));

        assertThat(manager.listIndexedChunkIds(kbId1)).containsExactly(chunk1.getId().toString());
        assertThat(manager.listIndexedChunkIds(kbId2)).containsExactly(chunk2.getId().toString());
    }

    @Test
    void deleteIndexRemovesTheWholeDirectoryFromDisk() throws Exception {
        UUID kbId = UUID.randomUUID();
        manager.indexChunks(kbId, List.of(newChunk("content")));
        Path indexDir = tempDir.resolve(kbId.toString());
        assertThat(Files.isDirectory(indexDir)).isTrue();

        manager.deleteIndex(kbId);

        assertThat(Files.exists(indexDir)).isFalse();
    }

    @Test
    void deleteIndexIsANoOpWhenTheDirectoryWasNeverCreated() throws Exception {
        // A knowledge_base that was created but never had a document ingested has no Lucene
        // directory at all - deleting it must not throw.
        manager.deleteIndex(UUID.randomUUID());
    }

    @Test
    void deleteIndexAllowsReindexingAfterwards() throws Exception {
        UUID kbId = UUID.randomUUID();
        manager.indexChunks(kbId, List.of(newChunk("first generation")));
        manager.deleteIndex(kbId);

        Chunk freshChunk = newChunk("second generation, same kb id");
        manager.indexChunks(kbId, List.of(freshChunk));

        assertThat(manager.listIndexedChunkIds(kbId))
                .containsExactly(freshChunk.getId().toString());
    }

    @Test
    void searchReturnsEmptyForAKnowledgeBaseWithNoIndexYet() throws Exception {
        assertThat(manager.search(UUID.randomUUID(), "anything", 10)).isEmpty();
    }

    @Test
    void searchRanksTheMoreMatchingDocumentFirst() throws Exception {
        UUID kbId = UUID.randomUUID();
        Chunk onTopic = newChunk("the quick brown fox jumps over the lazy dog");
        Chunk offTopic = newChunk("completely unrelated content about something else entirely");
        manager.indexChunks(kbId, List.of(onTopic, offTopic));

        List<ScoredChunkId> results = manager.search(kbId, "quick fox", 10);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).chunkId()).isEqualTo(onTopic.getId());
    }

    @Test
    void searchRespectsTopK() throws Exception {
        UUID kbId = UUID.randomUUID();
        manager.indexChunks(
                kbId,
                List.of(newChunk("apple apple apple"), newChunk("apple apple"), newChunk("apple")));

        List<ScoredChunkId> results = manager.search(kbId, "apple", 2);

        assertThat(results).hasSize(2);
    }

    @Test
    void searchTreatsQuerySyntaxCharactersAsLiteralText() throws Exception {
        // QueryParser.escape must run first - a raw query containing Lucene operator syntax
        // (unbalanced parens/colons/wildcards) must never throw a ParseException.
        UUID kbId = UUID.randomUUID();
        manager.indexChunks(kbId, List.of(newChunk("some normal content")));

        List<ScoredChunkId> results =
                manager.search(kbId, "field: (unterminated AND * OR [ malformed", 10);

        assertThat(results).isEmpty();
    }

    @Test
    void searchKeepsDifferentKnowledgeBasesIsolated() throws Exception {
        UUID kbId1 = UUID.randomUUID();
        UUID kbId2 = UUID.randomUUID();
        Chunk chunk1 = newChunk("shared search term");
        Chunk chunk2 = newChunk("shared search term");
        manager.indexChunks(kbId1, List.of(chunk1));
        manager.indexChunks(kbId2, List.of(chunk2));

        assertThat(manager.search(kbId1, "shared search term", 10))
                .extracting(ScoredChunkId::chunkId)
                .containsExactly(chunk1.getId());
    }

    private static Chunk newChunk(String content) throws Exception {
        Chunk chunk = Chunk.create(UUID.randomUUID(), UUID.randomUUID(), null, 0, content);
        Field idField = Chunk.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(chunk, UUID.randomUUID());
        return chunk;
    }
}
