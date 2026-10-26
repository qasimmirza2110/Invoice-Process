package com.actify.invoice.controller;

import com.actify.invoice.exception.WorkflowConflictException;
import com.actify.invoice.security.InvoiceAccessPolicy;
import com.actify.invoice.service.InvoiceService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * invoice.legacy-extract.enabled=false (the local demo): POST /api/invoices/extract is refused (409 via
 * GlobalExceptionHandler) before the service - and therefore Gemini - is ever called.
 */
class LegacyExtractSwitchTest {

    @Test
    void aSwitchedOffLegacyEndpointNeverReachesTheServiceOrGemini() {
        InvoiceService service = mock(InvoiceService.class);
        InvoiceController controller = new InvoiceController(service, mock(InvoiceAccessPolicy.class), false);

        assertThatThrownBy(() -> controller.extract(new MockMultipartFile("file", "x.pdf", "application/pdf", "%PDF-1.4".getBytes())))
                .isInstanceOf(WorkflowConflictException.class)
                .hasMessageContaining("switched off");
        verify(service, never()).extractAndSave(any());
    }
}
