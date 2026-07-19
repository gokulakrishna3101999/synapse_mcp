package com.synapsemcp.document;

import com.synapsemcp.common.IngestionStatus;
import java.util.UUID;

/**
 * rag_plan.md Stage 4. {@code status} lets a caller see the outcome without a follow-up poll.
 *
 * <p>{@code dispatched} distinguishes "this upload just triggered new pipeline work" (a brand-new
 * document, or a {@code FAILED} document reset and re-dispatched) from "this is a pure idempotency
 * no-op" (an existing {@code PENDING}/{@code INDEXING}/{@code READY} document, unchanged) - {@code
 * status} alone can't make that distinction, since both cases can currently read {@code PENDING}
 * (Stage 5's pipeline doesn't exist yet to ever advance a job past it). The controller maps this to
 * {@code 202}/{@code 200} respectively (user-confirmed, `plan.md` §9 2026-07-17).
 */
public record UploadDocumentResponse(
        UUID documentId, UUID jobId, IngestionStatus status, boolean dispatched) {}
