package com.synapsemcp.ingestion.persist;

import com.synapsemcp.document.Document;
import com.synapsemcp.document.DocumentRepository;
import com.synapsemcp.ingestion.chunk.ChunkingStrategy.ChunkData;
import com.synapsemcp.rag.chunk.Chunk;
import com.synapsemcp.rag.chunk.ChunkRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * rag_plan.md Stage 5c/5d: sparse column mapping (Grooming #14/#24) and the atomic Postgres commit
 * of a document's chunks. {@code document.extractorName} is set here too, in the same transaction
 * as the chunk insert - both are "persist" concerns, and this is the first point in the pipeline
 * where the document row is written again since Stage 4 created it.
 *
 * <p>A mid-batch dimension mismatch (or any other failure) rolls back the whole transaction - no
 * partial set of chunks is ever committed, per the plan's explicit "a mid-batch failure leaves no
 * partial chunks" requirement.
 *
 * <p>Any chunks already committed for this document from a previous attempt are deleted in the same
 * transaction before the new set is inserted (Grooming #67, found live, `plan.md` §9 2026-07-17) -
 * reachable through Grooming #26's own retry path: a document can reach {@code FAILED} with its
 * chunks already durable (persist succeeded, the later Lucene write then threw), and re-uploading
 * it re-runs this method a second time. Without this, a retry silently appended a second, duplicate
 * set of chunks instead of replacing the first.
 */
@Service
public class ChunkPersistenceService {

    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;

    public ChunkPersistenceService(
            ChunkRepository chunkRepository, DocumentRepository documentRepository) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
    }

    @Transactional
    public PersistResult persist(
            Document document,
            UUID knowledgeBaseId,
            String extractorName,
            List<ChunkData> chunkDataList,
            List<float[]> embeddings,
            int embeddingDim) {
        document.setExtractorName(extractorName);
        Document managedDocument = documentRepository.save(document);

        List<Chunk> existingChunks = chunkRepository.findByDocument_Id(managedDocument.getId());
        List<String> replacedChunkIds =
                existingChunks.stream().map(c -> c.getId().toString()).toList();
        if (!existingChunks.isEmpty()) {
            chunkRepository.deleteAll(existingChunks);
        }

        List<Chunk> chunks = new ArrayList<>(chunkDataList.size());
        for (int i = 0; i < chunkDataList.size(); i++) {
            ChunkData data = chunkDataList.get(i);
            float[] vector = embeddings.get(i);
            if (vector.length != embeddingDim) {
                throw new IllegalStateException(
                        "embedding dimension mismatch: knowledge_base expects "
                                + embeddingDim
                                + " but the embedding model returned "
                                + vector.length);
            }
            Chunk chunk =
                    Chunk.create(
                            managedDocument.getTenantId(),
                            knowledgeBaseId,
                            managedDocument,
                            data.position(),
                            data.content());
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("strategy", data.strategy());
            if (data.headingPath() != null) {
                metadata.put("headingPath", data.headingPath());
            }
            chunk.setMetadata(metadata);
            assignEmbedding(chunk, embeddingDim, vector);
            chunks.add(chunk);
        }
        return new PersistResult(chunkRepository.saveAll(chunks), replacedChunkIds);
    }

    /**
     * @param replacedChunkIds ids of chunks deleted from Postgres because they belonged to a
     *     previous attempt for this same document - the caller must also remove these from the
     *     Lucene index, since they carry different ids than {@code chunks} and won't be overwritten
     *     by re-indexing the new set.
     */
    public record PersistResult(List<Chunk> chunks, List<String> replacedChunkIds) {
        public PersistResult {
            chunks = List.copyOf(chunks);
            replacedChunkIds = List.copyOf(replacedChunkIds);
        }
    }

    private static void assignEmbedding(Chunk chunk, int embeddingDim, float[] vector) {
        switch (embeddingDim) {
            case 384 -> chunk.setEmbedding384(vector);
            case 512 -> chunk.setEmbedding512(vector);
            case 768 -> chunk.setEmbedding768(vector);
            case 1024 -> chunk.setEmbedding1024(vector);
            case 1536 -> chunk.setEmbedding1536(vector);
            case 3072 -> chunk.setEmbedding3072(vector);
            default ->
                    throw new IllegalStateException(
                            "unsupported embedding dimension: " + embeddingDim);
        }
    }
}
