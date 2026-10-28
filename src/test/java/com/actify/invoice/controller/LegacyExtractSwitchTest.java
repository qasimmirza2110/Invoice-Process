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
 * GlobalExceptionHandler) before the service - and therefore Gemini - is ever called. The free-tier test refuses it
 * too, even if the switch were on: it would send any uploaded file, outside the one allowlisted invoice.
 */
class LegacyExtractSwitchTest {

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "x.pdf", "application/pdf", "%PDF-1.4".getBytes());
    }

    @Test
    void aSwitchedOffLegacyEndpointNeverReachesTheServiceOrGemini() {
        InvoiceService service = mock(InvoiceService.class);
        InvoiceController controller = new InvoiceController(service, mock(InvoiceAccessPolicy.class), false, false);

        assertThatThrownBy(() -> controller.extract(pdf()))
                .isInstanceOf(WorkflowConflictException.class)
                .hasMessageContaining("switched off");
        verify(service, never()).extractAndSave(any());
    }

    @Test
    void theFreeTierTestBlocksTheLegacyEndpointEvenWhenItsSwitchIsOn() {
        InvoiceService service = mock(InvoiceService.class);
        InvoiceController controller = new InvoiceController(service, mock(InvoiceAccessPolicy.class), true, true);

        assertThatThrownBy(() -> controller.extract(pdf()))
                .isInstanceOf(WorkflowConflictException.class)
                .hasMessageContaining("switched off");
        verify(service, never()).extractAndSave(any());
    }
}
