package com.synapsemcp.rag.answer;

import java.util.UUID;

/**
 * rag_plan.md Stage 6b: "Sources numbered [Source N — filename]; response echoes the same chunks."
 * {@code sourceNumber} is 1-based and matches the {@code [Source N]} references the system prompt
 * instructs the model to cite inline - every citation here corresponds to a chunk actually shown to
 * the model (post context-window budgeting), never a candidate that was retrieved but excluded.
 */
public record Citation(int sourceNumber, UUID chunkId, UUID documentId, String filename) {}
