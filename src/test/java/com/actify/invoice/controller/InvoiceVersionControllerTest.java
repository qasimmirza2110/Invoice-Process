package com.actify.invoice.controller;

import com.actify.invoice.dto.FieldErrorDto;
import com.actify.invoice.dto.InvoiceSaveResponse;
import com.actify.invoice.dto.InvoiceUpdateRequest;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.exception.InvoiceValidationException;
import com.actify.invoice.exception.VersionConflictException;
import com.actify.invoice.exception.VersionNotFoundException;
import com.actify.invoice.service.InvoiceEditService;
import com.actify.invoice.service.InvoiceVersionQueryService;
import com.actify.invoice.dto.InvoiceResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.security.JwtTokenService;
import com.actify.invoice.security.TestLogins;
import com.actify.invoice.security.WithTask8WebSecurity;
import com.actify.invoice.user.AppUserRepository;
import com.actify.invoice.user.Role;
import org.junit.jupiter.api.BeforeEach;

import javax.servlet.http.Cookie;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP layer of corrections and history with Spring Boot's real Jackson configuration
 * (which ignores unknown properties by default - these tests prove they still reach the service).
 */
@WebMvcTest(InvoiceVersionController.class)
@WithTask8WebSecurity
class InvoiceVersionControllerTest {

    @Autowired
    private WebApplicationContext context;

    // Real security filter chain: every request below is sent as a logged-in ADMIN with a CSRF token.
    private MockMvc mockMvc;

    @MockBean
    private AppUserRepository userRepository;

    @MockBean
    private InvoiceRepository invoiceRepository;

    @Autowired
    private JwtTokenService tokenService;

    @BeforeEach
    void loginAsAdmin() {
        Cookie admin = TestLogins.cookieFor(TestLogins.user(1, Role.ADMIN), userRepository, tokenService);
        // Corrections by an admin are allowed on LEGACY invoices (no owner) - which every invoice in this test is.
        org.mockito.Mockito.lenient().when(invoiceRepository.findById(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(i -> { com.actify.invoice.entity.Invoice legacy = new com.actify.invoice.entity.Invoice();
                    legacy.setId(i.getArgument(0)); return java.util.Optional.of(legacy); });
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .defaultRequest(get("/").cookie(admin).with(csrf())).build();
    }

    @MockBean
    private InvoiceEditService editService;

    @MockBean
    private InvoiceVersionQueryService queryService;

    private static final String BODY = "{\"baseVersion\":1,\"invoiceNumber\":\"000000005\",\"sellerTaxId\":\"6000000001\","
            + "\"subtotal\":\"1250.50\",\"taxAmount\":0.1,\"totalAmount\":\"1250.60\","
            + "\"items\":[{\"description\":\"A\",\"quantity\":\"2.500\",\"unitPrice\":\"500.20\",\"lineTotal\":\"1250.50\"}]}";

    @Test
    void bodyBindsExactlyStringsStayStringsAndDecimalsStayExact() throws Exception {
        Invoice saved = new Invoice();
        saved.setId(5L);
        when(editService.saveCorrection(eq(5L), any())).thenReturn(new InvoiceSaveResponse(true, InvoiceResponse.fromEntity(saved, 2)));

        mockMvc.perform(put("/api/invoices/5").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.invoice.currentVersion").value(2));

        ArgumentCaptor<InvoiceUpdateRequest> captor = ArgumentCaptor.forClass(InvoiceUpdateRequest.class);
        verify(editService).saveCorrection(eq(5L), captor.capture());
        InvoiceUpdateRequest r = captor.getValue();
        assertThat(r.getInvoiceNumber()).isEqualTo("000000005");
        assertThat(r.getSellerTaxId()).isEqualTo("6000000001");
        assertThat(r.getSubtotal().toPlainString()).isEqualTo("1250.50");      // trailing zero kept
        assertThat(r.getTaxAmount().toPlainString()).isEqualTo("0.1");         // no float artefact (0.1000000000000000055...)
        assertThat(r.getItems().get(0).getQuantity().toPlainString()).isEqualTo("2.500");
        assertThat(r.getUnknownFields()).isEmpty();
    }

    @Test
    void unknownFieldsReachTheServiceInsteadOfBeingSilentlyDropped() throws Exception {
        when(editService.saveCorrection(eq(5L), any())).thenReturn(new InvoiceSaveResponse(false, null));
        String body = "{\"baseVersion\":1,\"id\":42,\"status\":\"FAILED\",\"items\":[{\"description\":\"A\",\"invoiceId\":9}]}";

        mockMvc.perform(put("/api/invoices/5").contentType(MediaType.APPLICATION_JSON).content(body));

        ArgumentCaptor<InvoiceUpdateRequest> captor = ArgumentCaptor.forClass(InvoiceUpdateRequest.class);
        verify(editService).saveCorrection(eq(5L), captor.capture());
        assertThat(captor.getValue().getUnknownFields()).containsOnlyKeys("id", "status");
        assertThat(captor.getValue().getItems().get(0).getUnknownFields()).containsOnlyKeys("invoiceId");
    }

    @Test
    void malformedJsonAndWrongTypesAre400WithTheFieldNamed() throws Exception {
        mockMvc.perform(put("/api/invoices/5").contentType(MediaType.APPLICATION_JSON).content("{\"baseVersion\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed JSON request body"));

        mockMvc.perform(put("/api/invoices/5").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseVersion\":1,\"items\":[{\"quantity\":\"abc\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("items[0].quantity"))
                .andExpect(content().string(not(containsString("com.actify"))))
                .andExpect(content().string(not(containsString("Exception"))));
        verifyNoInteractions(editService);
    }

    @Test
    void nonJsonContentTypeIs415() throws Exception {
        mockMvc.perform(put("/api/invoices/5").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
        verifyNoInteractions(editService);
    }

    @Test
    void staleVersionIs409WithAClearMessage() throws Exception {
        when(editService.saveCorrection(eq(5L), any())).thenThrow(new VersionConflictException(3, 2));

        mockMvc.perform(put("/api/invoices/5").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Version conflict"))
                .andExpect(jsonPath("$.message").value(containsString("Reload the latest version")));
    }

    @Test
    void validationErrorsCarryFieldErrors() throws Exception {
        when(editService.saveCorrection(eq(5L), any())).thenThrow(new InvoiceValidationException("totalAmount wrong",
                Collections.singletonList(new FieldErrorDto("totalAmount", "totalAmount wrong"))));

        mockMvc.perform(put("/api/invoices/5").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("Invoice validation failed"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("totalAmount"));
    }

    @Test
    void historyErrorsKeepTheExistingErrorShape() throws Exception {
        when(queryService.getVersion(5L, 9)).thenThrow(new VersionNotFoundException(5L, 9));

        mockMvc.perform(get("/api/invoices/5/versions/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Invoice version not found"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());

        mockMvc.perform(get("/api/invoices/5/versions/compare").param("from", "1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/invoices/abc/versions"))
                .andExpect(status().isBadRequest());
    }
}
