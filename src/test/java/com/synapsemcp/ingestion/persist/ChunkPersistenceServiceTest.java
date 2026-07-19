package com.synapsemcp.ingestion.persist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.IngestionStatus;
import com.synapsemcp.document.Document;
import com.synapsemcp.document.DocumentRepository;
import com.synapsemcp.ingestion.chunk.ChunkingStrategy.ChunkData;
import com.synapsemcp.rag.chunk.Chunk;
import com.synapsemcp.rag.chunk.ChunkRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChunkPersistenceServiceTest {

    private final ChunkRepository chunkRepository = mock(ChunkRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final ChunkPersistenceService service =
            new ChunkPersistenceService(chunkRepository, documentRepository);

    private Document document;
    private final UUID knowledgeBaseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        document =
                Document.create(
                        UUID.randomUUID(),
                        null,
                        "notes.txt",
                        "text/plain",
                        IngestionStatus.INDEXING,
                        "hash");
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(chunkRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(chunkRepository.findByDocument_Id(any())).thenReturn(List.of());
    }

    @Test
    void routesTheVectorToTheSparseColumnMatchingTheKnowledgeBaseDimension() {
        List<ChunkData> chunkData =
                List.of(new ChunkData("hello", 0, "Title > Sub", "structure-aware"));
        List<float[]> embeddings = List.of(new float[1536]);

        List<Chunk> chunks =
                service.persist(
                                document,
                                knowledgeBaseId,
                                "PlainTextExtractor",
                                chunkData,
                                embeddings,
                                1536)
                        .chunks();

        assertThat(chunks).hasSize(1);
        Chunk chunk = chunks.get(0);
        assertThat(chunk.getEmbedding1536()).isNotNull().hasSize(1536);
        assertThat(chunk.getEmbedding384()).isNull();
        assertThat(chunk.getEmbedding512()).isNull();
        assertThat(chunk.getEmbedding768()).isNull();
        assertThat(chunk.getEmbedding1024()).isNull();
        assertThat(chunk.getEmbedding3072()).isNull();
        assertThat(chunk.getContent()).isEqualTo("hello");
        assertThat(chunk.getMetadata()).containsEntry("strategy", "structure-aware");
        assertThat(chunk.getMetadata()).containsEntry("headingPath", "Title > Sub");
    }

    @Test
    void routesEachOfTheSixSupportedDimensionsToItsOwnColumn() {
        int[] dims = {384, 512, 768, 1024, 1536, 3072};
        for (int dim : dims) {
            List<ChunkData> chunkData = List.of(new ChunkData("x", 0, null, "fixed-size"));
            List<float[]> embeddings = List.of(new float[dim]);

            Chunk chunk =
                    service.persist(
                                    document,
                                    knowledgeBaseId,
                                    "extractor",
                                    chunkData,
                                    embeddings,
                                    dim)
                            .chunks()
                            .get(0);

            assertThat(sparseColumnFor(chunk, dim)).as("dim " + dim).isNotNull().hasSize(dim);
        }
    }

    @Test
    void omitsHeadingPathFromMetadataWhenNull() {
        List<ChunkData> chunkData = List.of(new ChunkData("hello", 0, null, "fixed-size"));
        List<float[]> embeddings = List.of(new float[384]);

        Chunk chunk =
                service.persist(document, knowledgeBaseId, "extractor", chunkData, embeddings, 384)
                        .chunks()
                        .get(0);

        assertThat(chunk.getMetadata()).doesNotContainKey("headingPath");
    }

    @Test
    void setsTheExtractorNameOnTheDocumentAndSavesIt() {
        List<ChunkData> chunkData = List.of(new ChunkData("hello", 0, null, "fixed-size"));
        List<float[]> embeddings = List.of(new float[384]);

        service.persist(document, knowledgeBaseId, "HtmlExtractor", chunkData, embeddings, 384);

        assertThat(document.getExtractorName()).isEqualTo("HtmlExtractor");
        verify(documentRepository).save(document);
    }

    @Test
    void throwsAndSavesNothingOnADimensionMismatch() {
        List<ChunkData> chunkData =
                List.of(
                        new ChunkData("ok", 0, null, "fixed-size"),
                        new ChunkData("mismatched", 1, null, "fixed-size"));
        List<float[]> embeddings = List.of(new float[1536], new float[768]);

        assertThatThrownBy(
                        () ->
                                service.persist(
                                        document,
                                        knowledgeBaseId,
                                        "extractor",
                                        chunkData,
                                        embeddings,
                                        1536))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimension mismatch");
        verify(chunkRepository, never()).saveAll(any());
    }

    /**
     * Grooming #67: a document can reach {@code FAILED} with its chunks already durable (persist
     * succeeded, the later Lucene write then threw) and get retried (Grooming #26) - this must
     * replace the prior attempt's chunks, not append a duplicate second set alongside them. Found
     * live: before this test existed, a real retry doubled the chunk count.
     */
    @Test
    void replacesAnyPreExistingChunksForTheSameDocumentRatherThanAppending() {
        Chunk staleChunk = mock(Chunk.class);
        UUID staleChunkId = UUID.randomUUID();
        when(staleChunk.getId()).thenReturn(staleChunkId);
        when(chunkRepository.findByDocument_Id(any())).thenReturn(List.of(staleChunk));
        List<ChunkData> chunkData = List.of(new ChunkData("fresh content", 0, null, "fixed-size"));
        List<float[]> embeddings = List.of(new float[384]);

        ChunkPersistenceService.PersistResult result =
                service.persist(document, knowledgeBaseId, "extractor", chunkData, embeddings, 384);

        verify(chunkRepository).deleteAll(List.of(staleChunk));
        assertThat(result.replacedChunkIds()).containsExactly(staleChunkId.toString());
        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).getContent()).isEqualTo("fresh content");
    }

    @Test
    void doesNotDeleteAnythingWhenTheDocumentHasNoPreExistingChunks() {
        List<ChunkData> chunkData = List.of(new ChunkData("hello", 0, null, "fixed-size"));
        List<float[]> embeddings = List.of(new float[384]);

        ChunkPersistenceService.PersistResult result =
                service.persist(document, knowledgeBaseId, "extractor", chunkData, embeddings, 384);

        verify(chunkRepository, never()).deleteAll(any());
        assertThat(result.replacedChunkIds()).isEmpty();
    }

    @Test
    void throwsForAnEmbeddingDimensionThatMatchesNoSparseColumn() {
        List<ChunkData> chunkData = List.of(new ChunkData("hello", 0, null, "fixed-size"));
        List<float[]> embeddings = List.of(new float[999]);

        assertThatThrownBy(
                        () ->
                                service.persist(
                                        document,
                                        knowledgeBaseId,
                                        "extractor",
                                        chunkData,
                                        embeddings,
                                        999))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported embedding dimension");
    }

    private static float[] sparseColumnFor(Chunk chunk, int dim) {
        return switch (dim) {
            case 384 -> chunk.getEmbedding384();
            case 512 -> chunk.getEmbedding512();
            case 768 -> chunk.getEmbedding768();
            case 1024 -> chunk.getEmbedding1024();
            case 1536 -> chunk.getEmbedding1536();
            case 3072 -> chunk.getEmbedding3072();
            default -> throw new IllegalArgumentException("unexpected dim " + dim);
        };
    }
}
