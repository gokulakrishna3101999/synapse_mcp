package com.synapsemcp.ingestion.index;

import com.synapsemcp.rag.chunk.Chunk;
import com.synapsemcp.rag.retrieve.ScoredChunkId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * rag_plan.md Stage 5d/6a: BM25 keyword index, one Lucene directory per knowledge_base ({@code
 * {base-dir}/{knowledgeBaseId}}). Only the write/reconciliation-read side is built here (Stage 5);
 * Stage 6a's actual search query path is a separate concern.
 *
 * <p>Base directory is configurable ({@code synapsemcp.lucene.base-dir}), defaulting to a system
 * temp/data directory rather than a path relative to the working directory (user-confirmed,
 * `plan.md` §9 2026-07-17 - rag_plan.md never specified a location).
 *
 * <p>A per-knowledge_base {@link ReentrantLock} (not Lucene's own OS-level directory lock and its
 * exception/retry handling) serializes concurrent writers to the same KB's index within this JVM -
 * multiple documents can be ingested concurrently for one knowledge_base (the {@code
 * ingestionExecutor} pool has up to 8 threads), and Lucene's {@code IndexWriter} does not allow two
 * writers open on the same directory at once. Sufficient for this project's current
 * single-instance, native-services architecture (no clustering/multi-node concern exists anywhere
 * in this plan).
 */
@Component
public class LuceneIndexManager {

    private static final String CHUNK_ID_FIELD = "chunkId";
    private static final String CONTENT_FIELD = "content";

    private final Path baseDir;
    private final Map<UUID, ReentrantLock> locksByKnowledgeBase = new ConcurrentHashMap<>();

    public LuceneIndexManager(
            @Value("${synapsemcp.lucene.base-dir:${java.io.tmpdir}/synapsemcp/lucene-indexes}")
                    String baseDir) {
        this.baseDir = Path.of(baseDir);
    }

    public void indexChunks(UUID knowledgeBaseId, List<Chunk> chunks) throws IOException {
        if (chunks.isEmpty()) {
            return;
        }
        withWriter(
                knowledgeBaseId,
                writer -> {
                    for (Chunk chunk : chunks) {
                        writer.updateDocument(
                                new Term(CHUNK_ID_FIELD, chunk.getId().toString()),
                                toLuceneDocument(chunk));
                    }
                    writer.commit();
                });
    }

    /** rag_plan.md Stage 5d reconciliation cron: chunk ids the Lucene index currently has. */
    public Set<String> listIndexedChunkIds(UUID knowledgeBaseId) throws IOException {
        Path indexDir = resolveIndexDir(knowledgeBaseId);
        if (!Files.isDirectory(indexDir)) {
            return Set.of();
        }
        try (Directory directory = FSDirectory.open(indexDir)) {
            if (!DirectoryReader.indexExists(directory)) {
                return Set.of();
            }
            try (IndexReader reader = DirectoryReader.open(directory)) {
                StoredFields storedFields = reader.storedFields();
                Set<String> chunkIds = new HashSet<>();
                for (int docId = 0; docId < reader.maxDoc(); docId++) {
                    String chunkId = storedFields.document(docId).get(CHUNK_ID_FIELD);
                    if (chunkId != null) {
                        chunkIds.add(chunkId);
                    }
                }
                return chunkIds;
            }
        }
    }

    /** rag_plan.md Stage 5d reconciliation cron: removes orphaned Lucene entries. */
    public void deleteChunks(UUID knowledgeBaseId, Collection<String> chunkIds) throws IOException {
        if (chunkIds.isEmpty()) {
            return;
        }
        withWriter(
                knowledgeBaseId,
                writer -> {
                    for (String chunkId : chunkIds) {
                        writer.deleteDocuments(new Term(CHUNK_ID_FIELD, chunkId));
                    }
                    writer.commit();
                });
    }

    /**
     * rag_plan.md Stage 6a: BM25 keyword search against this knowledge_base's index. Free text is
     * always literal terms, never Lucene query syntax - {@link QueryParser#escape} runs first so a
     * caller's query can never inject a Lucene operator ({@code AND}/{@code NOT}/wildcards/etc.),
     * matching the plan's own "QueryParser.escape(queryText)" wording. {@link IndexSearcher}'s
     * similarity defaults to {@code BM25Similarity} (Lucene's own default since version 6,
     * confirmed via {@code javap} against {@code IndexSearcher.getDefaultSimilarity()}) - no
     * explicit {@code setSimilarity} call needed.
     *
     * @return chunk ids ranked by BM25 score (highest first), never more than {@code topK}; empty
     *     if this knowledge_base has no index yet (no document ever ingested).
     */
    public List<ScoredChunkId> search(UUID knowledgeBaseId, String queryText, int topK)
            throws IOException {
        Path indexDir = resolveIndexDir(knowledgeBaseId);
        if (!Files.isDirectory(indexDir)) {
            return List.of();
        }
        try (Directory directory = FSDirectory.open(indexDir)) {
            if (!DirectoryReader.indexExists(directory)) {
                return List.of();
            }
            try (IndexReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                TopDocs topDocs = searcher.search(parseQuery(queryText), topK);
                StoredFields storedFields = reader.storedFields();
                List<ScoredChunkId> results = new ArrayList<>(topDocs.scoreDocs.length);
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    String chunkId = storedFields.document(scoreDoc.doc).get(CHUNK_ID_FIELD);
                    results.add(new ScoredChunkId(UUID.fromString(chunkId), scoreDoc.score));
                }
                return results;
            }
        }
    }

    /**
     * {@link ParseException} should be unreachable after {@link QueryParser#escape} - escaping
     * neutralizes every character the parser would otherwise treat as syntax, so a failure here
     * indicates a bug in this method rather than a condition a caller can trigger; wrapped as
     * unchecked rather than widening this class's public {@code search} signature for a case that
     * can't legitimately occur from caller-supplied free text.
     */
    private static Query parseQuery(String queryText) {
        try {
            return new QueryParser(CONTENT_FIELD, new StandardAnalyzer())
                    .parse(QueryParser.escape(queryText));
        } catch (ParseException e) {
            throw new IllegalStateException(
                    "escaped query text failed to parse - should be unreachable", e);
        }
    }

    /**
     * rag_plan.md Stage 3 knowledge_base deletion (Grooming #71, found live, `plan.md` §9
     * 2026-07-17): {@code knowledgeBaseRepository.delete(...)} cascades every Postgres row, but has
     * no way to reach this class's on-disk index directory - without this method being called, a
     * deleted knowledge_base's Lucene directory was silently orphaned forever, invisible to the
     * reconciliation cron (which only iterates {@code knowledgeBaseRepository.findAll()}, i.e.
     * knowledge bases that still exist). Removes the whole directory tree and this JVM's lock entry
     * for the id; a no-op if the directory was never created (a knowledge_base that never had a
     * document ingested).
     */
    public void deleteIndex(UUID knowledgeBaseId) throws IOException {
        ReentrantLock lock =
                locksByKnowledgeBase.computeIfAbsent(knowledgeBaseId, id -> new ReentrantLock());
        lock.lock();
        try {
            Path indexDir = resolveIndexDir(knowledgeBaseId);
            if (!Files.isDirectory(indexDir)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(indexDir)) {
                try {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(
                                    path -> {
                                        try {
                                            Files.delete(path);
                                        } catch (IOException e) {
                                            throw new UncheckedIOException(e);
                                        }
                                    });
                } catch (UncheckedIOException e) {
                    throw e.getCause();
                }
            }
        } finally {
            lock.unlock();
            locksByKnowledgeBase.remove(knowledgeBaseId);
        }
    }

    private void withWriter(UUID knowledgeBaseId, LuceneWriterAction action) throws IOException {
        ReentrantLock lock =
                locksByKnowledgeBase.computeIfAbsent(knowledgeBaseId, id -> new ReentrantLock());
        lock.lock();
        try {
            Path indexDir = resolveIndexDir(knowledgeBaseId);
            Files.createDirectories(indexDir);
            try (Directory directory = FSDirectory.open(indexDir);
                    IndexWriter writer =
                            new IndexWriter(
                                    directory,
                                    new IndexWriterConfig(new StandardAnalyzer())
                                            .setOpenMode(
                                                    IndexWriterConfig.OpenMode.CREATE_OR_APPEND))) {
                action.run(writer);
            }
        } finally {
            lock.unlock();
        }
    }

    private Document toLuceneDocument(Chunk chunk) {
        Document document = new Document();
        document.add(new StringField(CHUNK_ID_FIELD, chunk.getId().toString(), Field.Store.YES));
        document.add(new TextField(CONTENT_FIELD, chunk.getContent(), Field.Store.YES));
        return document;
    }

    private Path resolveIndexDir(UUID knowledgeBaseId) {
        return baseDir.resolve(knowledgeBaseId.toString());
    }

    @FunctionalInterface
    private interface LuceneWriterAction {
        void run(IndexWriter writer) throws IOException;
    }
}
