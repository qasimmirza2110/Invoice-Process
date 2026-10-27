package com.actify.invoice.intake;

import com.actify.invoice.dto.BatchDtos.BatchDetail;
import com.actify.invoice.dto.BatchDtos.BatchInvoice;
import com.actify.invoice.dto.BatchDtos.BatchSummary;
import com.actify.invoice.dto.BatchDtos.FileIntakeResponse;
import com.actify.invoice.dto.BatchDtos.RejectedFile;
import com.actify.invoice.dto.FieldErrorDto;
import com.actify.invoice.cloud.CloudSourceSettings;
import com.actify.invoice.config.FreeDemoUploadPolicy;
import com.actify.invoice.entity.BatchSource;
import com.actify.invoice.entity.BatchStatus;
import com.actify.invoice.entity.ExtractionJob;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceDocument;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.entity.UploadBatch;
import com.actify.invoice.exception.BatchNotFoundException;
import com.actify.invoice.exception.InvalidInvoiceException;
import com.actify.invoice.exception.InvalidRequestException;
import com.actify.invoice.exception.WorkflowConflictException;
import com.actify.invoice.repository.ExtractionJobRepository;
import com.actify.invoice.repository.InvoiceDocumentRepository;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.repository.UploadBatchRepository;
import com.actify.invoice.security.AuthenticatedUser;
import com.actify.invoice.security.CurrentUserService;
import com.actify.invoice.service.PdfFiles;
import com.actify.invoice.storage.InvoiceDocumentStorage;
import com.actify.invoice.storage.StoredDocument;
import com.actify.invoice.user.AppUser;
import com.actify.invoice.workflow.WorkflowService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Batch upload: a vendor opens a batch, then sends its PDFs ONE PER REQUEST (so 100 files never become one
 * 1 GB request), then closes it. Gemini is NOT called here - an accepted file only waits in the queue.
 *
 * Accepting one file:
 *   1. checks: batch is mine and OPEN, fewer than 100 accepted, top-level file only, real PDF, not my duplicate;
 *   2. the PDF is written to private storage FIRST (a file write cannot join a database transaction);
 *   3. ONE short transaction: document row + invoice (QUEUED) + extraction job + first workflow event;
 *   4. if that transaction fails, the stored file is deleted again (compensation). If the process dies between
 *      2 and 3, an orphan file can remain. SQL cannot see the disk: finding one means comparing the files in the
 *      storage folder with the invoice_documents ids (files are named {id}.pdf; a manual check, no tool for it yet).
 * A rejected file never becomes an invoice; its name and reason are recorded on the batch.
 * Free demo only (FreeDemoUploadPolicy): before step 1, a file that is not one of the published synthetic fixtures
 * is rejected with NOT_A_DEMO_FIXTURE - never stored, and recorded under a neutral name instead of its own.
 */
@Service
public class BatchIntakeService {

    private static final Logger log = LoggerFactory.getLogger(BatchIntakeService.class);
    private static final int MAX_RECORDED_REJECTIONS = 500;

    private final UploadBatchRepository batchRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceDocumentRepository documentRepository;
    private final ExtractionJobRepository jobRepository;
    private final InvoiceDocumentStorage documentStorage;
    private final WorkflowService workflow;
    private final CurrentUserService currentUser;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final CategoryCodes categoryCodes;
    private final CloudSourceSettings cloudSources;
    private final FreeDemoUploadPolicy demoUploads;
    private final int maxAttempts;

    @PersistenceContext
    private EntityManager entityManager;

    public BatchIntakeService(UploadBatchRepository batchRepository, InvoiceRepository invoiceRepository,
                              InvoiceDocumentRepository documentRepository, ExtractionJobRepository jobRepository,
                              InvoiceDocumentStorage documentStorage, WorkflowService workflow,
                              CurrentUserService currentUser, TransactionTemplate transactionTemplate,
                              ObjectMapper objectMapper, CategoryCodes categoryCodes, CloudSourceSettings cloudSources,
                              FreeDemoUploadPolicy demoUploads,
                              @Value("${invoice.worker.max-attempts:3}") int maxAttempts) {
        this.batchRepository = batchRepository;
        this.invoiceRepository = invoiceRepository;
        this.documentRepository = documentRepository;
        this.jobRepository = jobRepository;
        this.documentStorage = documentStorage;
        this.workflow = workflow;
        this.currentUser = currentUser;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.categoryCodes = categoryCodes;
        this.cloudSources = cloudSources;
        this.demoUploads = demoUploads;
        this.maxAttempts = maxAttempts;
    }

    // ---------------------------------------------------------------- batches

    @Transactional
    public BatchSummary createBatch(String label, String defaultCategoryCode) {
        return createBatch(label, defaultCategoryCode, null);
    }

    /** @param source LOCAL (default), GOOGLE_DRIVE or ONEDRIVE; one source per batch, cloud ones only when configured. */
    @Transactional
    public BatchSummary createBatch(String label, String defaultCategoryCode, String source) {
        AuthenticatedUser user = currentUser.require();
        BatchSource batchSource = parseSource(source);
        if (batchSource == null) {
            throw new InvalidRequestException("source must be LOCAL, GOOGLE_DRIVE or ONEDRIVE",
                    Collections.singletonList(new FieldErrorDto("source", "source must be LOCAL, GOOGLE_DRIVE or ONEDRIVE")));
        }
        if (!cloudSources.isUsable(batchSource)) {
            throw new InvalidRequestException(batchSource + " is not configured on this server",
                    Collections.singletonList(new FieldErrorDto("source", batchSource + " is not configured on this server")));
        }
        String cleanLabel = label == null || label.trim().isEmpty() ? null : label.trim();
        if (cleanLabel != null && cleanLabel.length() > 255) {
            cleanLabel = cleanLabel.substring(0, 255);
        }
        String category = categoryCodes.requireActiveOrNull(defaultCategoryCode);
        AppUser owner = entityManager.find(AppUser.class, user.getId());
        UploadBatch batch = batchRepository.save(new UploadBatch(owner, batchSource, cleanLabel, category));
        log.info("Batch {} ({}) created by user {}", batch.getId(), batchSource, user.getId());
        return new BatchSummary(batch);
    }

    @Transactional
    public BatchSummary closeBatch(Long batchId) {
        UploadBatch batch = batchRepository.findByIdForUpdate(batchId).orElseThrow(() -> new BatchNotFoundException(batchId));
        requireOwner(batch, currentUser.require());
        if (batch.getStatus() == BatchStatus.OPEN) {
            batch.close();
        }
        return new BatchSummary(batch);
    }

    @Transactional(readOnly = true)
    public List<BatchSummary> listBatches() {
        AuthenticatedUser user = currentUser.require();
        List<UploadBatch> batches = user.isAdmin()
                ? batchRepository.findAllByOrderByIdDesc()
                : batchRepository.findAllByOwnerUserIdOrderByIdDesc(user.getId());
        List<BatchSummary> result = new ArrayList<>();
        for (UploadBatch batch : batches) {
            result.add(new BatchSummary(batch));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public BatchDetail getBatch(Long batchId) {
        AuthenticatedUser user = currentUser.require();
        UploadBatch batch = batchRepository.findById(batchId).orElseThrow(() -> new BatchNotFoundException(batchId));
        if (!user.isAdmin() && !user.getId().equals(batch.getOwnerUserId())) {
            throw new BatchNotFoundException(batchId);
        }
        Map<InvoiceStatus, Long> counts = new EnumMap<>(InvoiceStatus.class);
        List<BatchInvoice> invoices = new ArrayList<>();
        for (Invoice invoice : invoiceRepository.findByBatchIdOrderByIdAsc(batchId)) {
            counts.merge(invoice.getStatus(), 1L, Long::sum);
            Long position = null;
            if (invoice.getStatus() == InvoiceStatus.QUEUED) {
                ExtractionJob job = jobRepository.findById(invoice.getId()).orElse(null);
                if (job != null) {
                    position = jobRepository.countQueuedAhead(job.getQueuedAt(), invoice.getId()) + 1;
                }
            }
            invoices.add(new BatchInvoice(invoice.getId(), invoice.getSourceFileName(), invoice.getStatus(), position,
                    invoice.getStatus() == InvoiceStatus.FAILED ? invoice.getFailureReason() : null, invoice.getCreatedAt()));
        }
        return new BatchDetail(new BatchSummary(batch), counts, invoices, readRejections(batch));
    }

    // ---------------------------------------------------------------- files

    /** SELECT ... FOR UPDATE on the vendor's app_users row; released when the surrounding transaction ends. */
    void lockVendor(Long userId) {
        entityManager.find(AppUser.class, userId, LockModeType.PESSIMISTIC_WRITE);
    }

    /**
     * @param relativePath the browser's webkitRelativePath for folder uploads ("Folder/file.pdf"), or null.
     *                     Policy: top-level files only - anything inside a sub-folder is rejected.
     */
    public FileIntakeResponse addFile(Long batchId, MultipartFile file, String relativePath) {
        return addFile(batchId, file, relativePath, null);
    }

    /**
     * @param declaredSource what the browser says the file came from (optional; missing = the batch's own source).
     *                       It is a label, not proof: a cloud file is checked exactly like a local one.
     */
    public FileIntakeResponse addFile(Long batchId, MultipartFile file, String relativePath, String declaredSource) {
        AuthenticatedUser user = currentUser.require();
        String fileName = PdfFiles.safeFileName(file == null ? null : file.getOriginalFilename());

        // Cheap checks first (repeated under the lock below, which is the one that counts).
        UploadBatch batch = batchRepository.findById(batchId).orElseThrow(() -> new BatchNotFoundException(batchId));
        requireOwner(batch, user);
        requireAcceptingFiles(batch);

        // Free demo: FIRST, so a non-fixture file is never stored and its (possibly real) name never recorded.
        if (demoUploads.isEnforced() && !demoUploads.allows(sha256OrNull(file))) {
            return reject(batchId, FreeDemoUploadPolicy.NEUTRAL_FILE_NAME, FreeDemoUploadPolicy.REJECTION_CODE,
                    FreeDemoUploadPolicy.REJECTION_MESSAGE);
        }
        if (!sourceMatches(batch.getSource(), declaredSource)) {
            return reject(batchId, fileName, "SOURCE_MISMATCH", "This batch only takes files from "
                    + batch.getSource() + "; start a new batch for another source.");
        }
        if (isInSubFolder(relativePath)) {
            return reject(batchId, fileName, "NESTED_FOLDER", "Files inside sub-folders are skipped (top-level PDFs only).");
        }
        byte[] bytes;
        try {
            bytes = PdfFiles.validateAndRead(file);
        } catch (InvalidInvoiceException e) {
            return reject(batchId, fileName, "INVALID_FILE", e.getMessage());
        }
        if (invoiceRepository.countByOwnerAndSha256(user.getId(), InvoiceDocumentStorage.sha256Hex(bytes)) > 0) {
            return reject(batchId, fileName, "DUPLICATE_FILE", "You already uploaded this exact file.");
        }

        StoredDocument stored = documentStorage.store(bytes);
        try {
            Long invoiceId = transactionTemplate.execute(status -> {
                // Lock order: vendor row first, then batch row. The vendor lock (SELECT ... FOR UPDATE on app_users)
                // serialises ALL of this vendor's uploads, so the same file sent into two different batches at the
                // same moment cannot both pass the duplicate check below. Other vendors are never blocked.
                lockVendor(user.getId());
                UploadBatch locked = batchRepository.findByIdForUpdate(batchId)
                        .orElseThrow(() -> new BatchNotFoundException(batchId));
                requireAcceptingFiles(locked);
                // Re-checked under the vendor lock: two uploads of the same file (same or different batch) cannot both pass.
                if (invoiceRepository.countByOwnerAndSha256(user.getId(), stored.getSha256()) > 0) {
                    return null;
                }
                InvoiceDocument document = documentRepository.save(new InvoiceDocument(stored.getId(), stored.getSha256(),
                        stored.getSizeBytes(), PdfFiles.PDF_CONTENT_TYPE, fileName));

                Invoice invoice = new Invoice();
                invoice.setSourceFileName(fileName);
                invoice.setSourceFileSize(file.getSize());
                invoice.setSourceDocument(document);
                invoice.setOwner(locked.getOwner());
                invoice.setBatch(locked);
                invoice.setCategoryCode(locked.getDefaultCategoryCode());
                invoice.setStatus(InvoiceStatus.QUEUED);
                invoice = invoiceRepository.save(invoice);

                jobRepository.save(new ExtractionJob(invoice, LocalDateTime.now(), maxAttempts));
                workflow.recordCreated(invoice, user.getId());
                locked.countAccepted();
                return invoice.getId();
            });
            if (invoiceId == null) {
                documentStorage.deleteQuietly(stored.getId());
                return reject(batchId, fileName, "DUPLICATE_FILE", "You already uploaded this exact file.");
            }
            log.info("Batch {}: file accepted as invoice {} (QUEUED)", batchId, invoiceId);
            return FileIntakeResponse.accepted(fileName, invoiceId);
        } catch (RuntimeException e) {
            // Nothing was committed, so the stored file would be an orphan.
            documentStorage.deleteQuietly(stored.getId());
            throw e;
        }
    }

    /** "Folder/file.pdf" is top-level; "Folder/Sub/file.pdf" is inside a sub-folder. */
    /** null/blank -> LOCAL; unknown -> null. */
    static BatchSource parseSource(String source) {
        if (source == null || source.trim().isEmpty()) {
            return BatchSource.LOCAL;
        }
        try {
            return BatchSource.valueOf(source.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A file may declare no source (it then counts as the batch's own) or exactly the batch's source. */
    static boolean sourceMatches(BatchSource batchSource, String declaredSource) {
        if (declaredSource == null || declaredSource.trim().isEmpty()) {
            return true;
        }
        return batchSource == parseSource(declaredSource);
    }

    static boolean isInSubFolder(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            return false;
        }
        String normalized = relativePath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.split("/").length > 2;
    }

    private FileIntakeResponse reject(Long batchId, String fileName, String code, String message) {
        transactionTemplate.execute(status -> {
            UploadBatch locked = batchRepository.findByIdForUpdate(batchId).orElseThrow(() -> new BatchNotFoundException(batchId));
            List<RejectedFile> rejections = readRejections(locked);
            if (rejections.size() < MAX_RECORDED_REJECTIONS) {
                rejections.add(new RejectedFile(fileName, code, message));
            }
            locked.recordRejection(writeRejections(rejections));
            return null;
        });
        log.info("Batch {}: file rejected ({})", batchId, code);
        return FileIntakeResponse.rejected(fileName, code, message);
    }

    /** SHA-256 of the uploaded bytes, or null when there is no readable file (null is never on the allowlist). */
    private static String sha256OrNull(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > PdfFiles.MAX_FILE_SIZE_BYTES) {
            return null;
        }
        try {
            return InvoiceDocumentStorage.sha256Hex(file.getBytes());
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private void requireOwner(UploadBatch batch, AuthenticatedUser user) {
        // Only the owner adds files or closes a batch; for anyone else the batch "does not exist".
        if (!user.getId().equals(batch.getOwnerUserId())) {
            throw new BatchNotFoundException(batch.getId());
        }
    }

    private void requireAcceptingFiles(UploadBatch batch) {
        if (batch.getStatus() != BatchStatus.OPEN) {
            throw new WorkflowConflictException("Batch " + batch.getId() + " is closed; start a new batch.");
        }
        if (batch.getAcceptedCount() >= UploadBatch.MAX_FILES) {
            throw new WorkflowConflictException("Batch " + batch.getId() + " already has " + UploadBatch.MAX_FILES
                    + " files; start a new batch.");
        }
    }

    private List<RejectedFile> readRejections(UploadBatch batch) {
        try {
            return objectMapper.readValue(batch.getRejections(), new TypeReference<List<RejectedFile>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable rejections of batch " + batch.getId(), e);
        }
    }

    private String writeRejections(List<RejectedFile> rejections) {
        try {
            return objectMapper.writeValueAsString(rejections);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write batch rejections", e);
        }
    }
}
