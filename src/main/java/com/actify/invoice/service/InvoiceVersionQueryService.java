package com.actify.invoice.service;

import com.actify.invoice.dto.InvoiceVersionResponse;
import com.actify.invoice.dto.SourceDocumentDto;
import com.actify.invoice.dto.VersionComparisonResponse;
import com.actify.invoice.entity.InvoiceVersion;
import com.actify.invoice.exception.InvoiceNotFoundException;
import com.actify.invoice.exception.VersionNotFoundException;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.repository.InvoiceVersionRepository;
import com.actify.invoice.versioning.InvoiceSnapshotMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** Read-only access to the stored version history. Always reads invoice_versions, never the current invoice row. */
@Service
@Transactional(readOnly = true)
public class InvoiceVersionQueryService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceVersionRepository versionRepository;
    private final InvoiceSnapshotMapper snapshotMapper;

    public InvoiceVersionQueryService(InvoiceRepository invoiceRepository, InvoiceVersionRepository versionRepository,
                                      InvoiceSnapshotMapper snapshotMapper) {
        this.invoiceRepository = invoiceRepository;
        this.versionRepository = versionRepository;
        this.snapshotMapper = snapshotMapper;
    }

    /** Newest first, without the full snapshots. An invoice with no history gives an empty list. */
    public List<InvoiceVersionResponse> listVersions(Long invoiceId) {
        requireInvoice(invoiceId);
        List<InvoiceVersionResponse> result = new ArrayList<>();
        for (InvoiceVersion version : versionRepository.findByInvoice_IdOrderByVersionNumberDesc(invoiceId)) {
            result.add(toResponse(invoiceId, version, false));
        }
        return result;
    }

    public InvoiceVersionResponse getVersion(Long invoiceId, int versionNumber) {
        return toResponse(invoiceId, find(invoiceId, versionNumber), true);
    }

    public VersionComparisonResponse compare(Long invoiceId, int fromVersion, int toVersion) {
        InvoiceVersion from = find(invoiceId, fromVersion);
        InvoiceVersion to = find(invoiceId, toVersion);
        return new VersionComparisonResponse(invoiceId, fromVersion, toVersion,
                snapshotMapper.diff(snapshotMapper.snapshotFromJson(from.getSnapshotData()),
                        snapshotMapper.snapshotFromJson(to.getSnapshotData())));
    }

    private InvoiceVersion find(Long invoiceId, int versionNumber) {
        requireInvoice(invoiceId);
        return versionRepository.findByInvoice_IdAndVersionNumber(invoiceId, versionNumber)
                .orElseThrow(() -> new VersionNotFoundException(invoiceId, versionNumber));
    }

    private void requireInvoice(Long invoiceId) {
        if (!invoiceRepository.existsById(invoiceId)) {
            throw new InvoiceNotFoundException(invoiceId);
        }
    }

    private InvoiceVersionResponse toResponse(Long invoiceId, InvoiceVersion v, boolean withSnapshot) {
        return new InvoiceVersionResponse(invoiceId, v.getVersionNumber(), v.getChangeSource(), v.getChangeReason(),
                v.getChangedBy(), v.getCreatedAt(), SourceDocumentDto.fromEntity(v.getSourceDocument()),
                snapshotMapper.changesFromJson(v.getChangedFields()),
                withSnapshot ? snapshotMapper.snapshotFromJson(v.getSnapshotData()) : null);
    }
}
