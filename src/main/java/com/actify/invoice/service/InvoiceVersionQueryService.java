package com.actify.invoice.service;

import com.actify.invoice.dto.InvoiceVersionResponse;
import com.actify.invoice.dto.SourceDocumentDto;
import com.actify.invoice.dto.VersionComparisonResponse;
import com.actify.invoice.entity.InvoiceVersion;
import com.actify.invoice.exception.InvoiceNotFoundException;
import com.actify.invoice.exception.VersionNotFoundException;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.repository.InvoiceVersionRepository;
import com.actify.invoice.user.UserNames;
import com.actify.invoice.versioning.InvoiceSnapshotMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Read-only access to the stored version history. Always reads invoice_versions, never the current invoice row. */
@Service
@Transactional(readOnly = true)
public class InvoiceVersionQueryService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceVersionRepository versionRepository;
    private final InvoiceSnapshotMapper snapshotMapper;
    private final UserNames userNames;

    public InvoiceVersionQueryService(InvoiceRepository invoiceRepository, InvoiceVersionRepository versionRepository,
                                      InvoiceSnapshotMapper snapshotMapper, UserNames userNames) {
        this.invoiceRepository = invoiceRepository;
        this.versionRepository = versionRepository;
        this.snapshotMapper = snapshotMapper;
        this.userNames = userNames;
    }

    /** Newest first, without the full snapshots. An invoice with no history gives an empty list. */
    public List<InvoiceVersionResponse> listVersions(Long invoiceId) {
        requireInvoice(invoiceId);
        List<InvoiceVersion> versions = versionRepository.findByInvoice_IdOrderByVersionNumberDesc(invoiceId);
        List<Long> editorIds = new ArrayList<>();
        for (InvoiceVersion version : versions) {
            editorIds.add(UserNames.idFromActorLabel(version.getChangedBy()));
        }
        Map<Long, String> names = userNames.namesById(editorIds);
        List<InvoiceVersionResponse> result = new ArrayList<>();
        for (InvoiceVersion version : versions) {
            result.add(toResponse(invoiceId, version, false, names));
        }
        return result;
    }

    public InvoiceVersionResponse getVersion(Long invoiceId, int versionNumber) {
        InvoiceVersion version = find(invoiceId, versionNumber);
        Long editorId = UserNames.idFromActorLabel(version.getChangedBy());
        return toResponse(invoiceId, version, true, userNames.namesById(Collections.singletonList(editorId)));
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

    private InvoiceVersionResponse toResponse(Long invoiceId, InvoiceVersion v, boolean withSnapshot,
                                              Map<Long, String> editorNames) {
        return new InvoiceVersionResponse(invoiceId, v.getVersionNumber(), v.getChangeSource(), v.getChangeReason(),
                v.getChangedBy(), UserNames.nameOrUnknown(editorNames, UserNames.idFromActorLabel(v.getChangedBy())), v.getCreatedAt(), SourceDocumentDto.fromEntity(v.getSourceDocument()),
                snapshotMapper.changesFromJson(v.getChangedFields()),
                withSnapshot ? snapshotMapper.snapshotFromJson(v.getSnapshotData()) : null);
    }
}
