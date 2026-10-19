package com.actify.invoice.controller;

import com.actify.invoice.dto.BatchDtos.BatchDetail;
import com.actify.invoice.dto.BatchDtos.BatchSummary;
import com.actify.invoice.dto.BatchDtos.CreateBatchRequest;
import com.actify.invoice.dto.BatchDtos.FileIntakeResponse;
import com.actify.invoice.intake.BatchIntakeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Batch upload API (VENDOR and ADMIN, see SecurityConfig). Every batch is owned by the user who created it;
 * another user's batch answers 404. Nothing here calls Gemini: accepted files wait in the queue.
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchIntakeService intakeService;

    public BatchController(BatchIntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BatchSummary> create(@RequestBody CreateBatchRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(intakeService.createBatch(body.getLabel(), body.getDefaultCategoryCode()));
    }

    @GetMapping
    public List<BatchSummary> list() {
        return intakeService.listBatches();
    }

    @GetMapping("/{id}")
    public BatchDetail get(@PathVariable Long id) {
        return intakeService.getBatch(id);
    }

    /**
     * One PDF per request. 202 = accepted and queued; 422 = rejected (reason in the body; recorded on the batch);
     * 409 = the batch is closed or already has 100 files.
     */
    @PostMapping(value = "/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileIntakeResponse> addFile(@PathVariable Long id, @RequestParam("file") MultipartFile file,
                                                      @RequestParam(value = "relativePath", required = false) String relativePath) {
        FileIntakeResponse result = intakeService.addFile(id, file, relativePath);
        return ResponseEntity.status(result.isAccepted() ? HttpStatus.ACCEPTED : HttpStatus.UNPROCESSABLE_ENTITY).body(result);
    }

    @PostMapping("/{id}/close")
    public BatchSummary close(@PathVariable Long id) {
        return intakeService.closeBatch(id);
    }
}
