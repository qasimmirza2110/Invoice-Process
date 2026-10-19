package com.actify.invoice.controller;

import com.actify.invoice.dto.InvoiceResponse;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceItem;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.exception.InvoiceExtractionException;
import com.actify.invoice.exception.InvoiceNotFoundException;
import com.actify.invoice.exception.InvoiceValidationException;
import com.actify.invoice.service.InvoiceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
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

import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checks the HTTP side of the failures we can't safely trigger live:
 * provider errors, bad LLM data and unexpected crashes. The service is mocked,
 * so no Gemini call and no database are involved.
 */
@WebMvcTest(InvoiceController.class)
@WithTask8WebSecurity
class InvoiceControllerTest {

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
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .defaultRequest(get("/").cookie(admin).with(csrf())).build();
    }

    @MockBean
    private InvoiceService invoiceService;

    private final MockMultipartFile pdf =
            new MockMultipartFile("file", "invoice.pdf", "application/pdf", "%PDF-1.4".getBytes());

    @Test
    void validationFailureReturns422() throws Exception {
        when(invoiceService.extractAndSave(any()))
                .thenThrow(new InvoiceValidationException("subtotal + taxAmount (100) does not match totalAmount (120)"));

        mockMvc.perform(multipart("/api/invoices/extract").file(pdf))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.error").value("Invoice validation failed"))
                .andExpect(jsonPath("$.path").value("/api/invoices/extract"));
    }

    @Test
    void providerFailureReturns502() throws Exception {
        when(invoiceService.extractAndSave(any()))
                .thenThrow(new InvoiceExtractionException("Gemini request failed with HTTP 503"));

        mockMvc.perform(multipart("/api/invoices/extract").file(pdf))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Invoice extraction failed"))
                .andExpect(jsonPath("$.message").value("Gemini request failed with HTTP 503"));
    }

    @Test
    void unexpectedErrorReturns500WithoutInternals() throws Exception {
        // Simulates something like a JDBC failure whose message contains internal details.
        when(invoiceService.extractAndSave(any()))
                .thenThrow(new IllegalStateException("connection to jdbc:postgresql://db-host failed, password=secret"));

        mockMvc.perform(multipart("/api/invoices/extract").file(pdf))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("jdbc"))))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    // ---------------- tokenUsage in the JSON response ----------------

    private InvoiceResponse extractedTwoPageInvoice() {
        Invoice invoice = new Invoice();
        invoice.setId(42L);
        invoice.setStatus(InvoiceStatus.EXTRACTED);
        invoice.setInvoiceNumber("INV-2026-0042");
        invoice.setTotalAmount(new BigDecimal("12390.00"));
        invoice.setRawLlmResponse("{\"invoiceNumber\":\"INV-2026-0042\",\"secretMarker\":\"RAW-LLM-JSON\"}");
        InvoiceItem page1 = new InvoiceItem();
        page1.setDescription("Laptop Stand");
        invoice.addItem(page1);
        InvoiceItem page2 = new InvoiceItem();
        page2.setDescription("USB-C Cable");
        invoice.addItem(page2);
        invoice.setGeminiModelId("gemini-3.5-flash-lite");
        invoice.setGeminiModelVersion("test-version");
        invoice.setPromptTokenCount(1290L);
        invoice.setCandidatesTokenCount(180L);
        invoice.setThoughtsTokenCount(null);
        invoice.setTotalTokenCount(1470L);
        invoice.setExtractionDurationMs(3200L);
        return InvoiceResponse.fromEntity(invoice);
    }

    @Test
    void postReturnsTokenUsageNextToTheExistingFields() throws Exception {
        when(invoiceService.extractAndSave(any())).thenReturn(extractedTwoPageInvoice());

        mockMvc.perform(multipart("/api/invoices/extract").file(pdf))
                .andExpect(status().isCreated())
                // existing contract unchanged
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.status").value("EXTRACTED"))
                .andExpect(jsonPath("$.invoiceNumber").value("INV-2026-0042"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].description").value("USB-C Cable"))
                // new object
                .andExpect(jsonPath("$.tokenUsage.modelId").value("gemini-3.5-flash-lite"))
                .andExpect(jsonPath("$.tokenUsage.modelVersion").value("test-version"))
                .andExpect(jsonPath("$.tokenUsage.promptTokenCount").value(1290))
                .andExpect(jsonPath("$.tokenUsage.candidatesTokenCount").value(180))
                .andExpect(jsonPath("$.tokenUsage.thoughtsTokenCount").value(nullValue()))
                .andExpect(jsonPath("$.tokenUsage.totalTokenCount").value(1470))
                .andExpect(jsonPath("$.tokenUsage.extractionDurationMs").value(3200))
                // nothing internal leaks out
                .andExpect(jsonPath("$.rawLlmResponse").doesNotExist())
                .andExpect(content().string(not(containsString("RAW-LLM-JSON"))))
                .andExpect(content().string(not(containsString("usageMetadata"))))
                .andExpect(content().string(not(containsString("candidates\""))))
                .andExpect(content().string(not(containsString("inlineData"))));
    }

    @Test
    void responseIncludesShippingAndDiscount() throws Exception {
        Invoice invoice = new Invoice();
        invoice.setId(30L);
        invoice.setStatus(InvoiceStatus.EXTRACTED);
        invoice.setInvoiceNumber("#9000000001");
        invoice.setSubtotal(new BigDecimal("141.00"));
        invoice.setDiscountAmount(new BigDecimal("14.10"));
        invoice.setShippingAmount(new BigDecimal("25.00"));
        invoice.setTaxAmount(new BigDecimal("10.47"));
        invoice.setTotalAmount(new BigDecimal("162.37"));
        when(invoiceService.getInvoice(30L)).thenReturn(InvoiceResponse.fromEntity(invoice));

        mockMvc.perform(get("/api/invoices/30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(141.00))
                .andExpect(jsonPath("$.discountAmount").value(14.10))
                .andExpect(jsonPath("$.shippingAmount").value(25.00))
                .andExpect(jsonPath("$.taxAmount").value(10.47))
                .andExpect(jsonPath("$.totalAmount").value(162.37));
    }

    @Test
    void historicalInvoiceSerializesNullShippingAndDiscount() throws Exception {
        Invoice old = new Invoice();
        old.setId(2L);
        old.setStatus(InvoiceStatus.EXTRACTED);
        old.setTotalAmount(new BigDecimal("12390.00"));
        when(invoiceService.getInvoice(2L)).thenReturn(InvoiceResponse.fromEntity(old));

        mockMvc.perform(get("/api/invoices/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountAmount").value(nullValue()))
                .andExpect(jsonPath("$.shippingAmount").value(nullValue()))
                .andExpect(jsonPath("$.totalAmount").value(12390.00));
    }

    @Test
    void historicalInvoiceSerializesNullTokenFields() throws Exception {
        Invoice old = new Invoice();
        old.setId(1L);
        old.setStatus(InvoiceStatus.EXTRACTED);
        when(invoiceService.getInvoice(1L)).thenReturn(InvoiceResponse.fromEntity(old));

        mockMvc.perform(get("/api/invoices/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenUsage").exists())
                .andExpect(jsonPath("$.tokenUsage.promptTokenCount").value(nullValue()))
                .andExpect(jsonPath("$.tokenUsage.totalTokenCount").value(nullValue()))
                .andExpect(jsonPath("$.tokenUsage.modelId").value(nullValue()));
    }

    @Test
    void unknownIdReturns404AndMalformedIdReturns400() throws Exception {
        when(invoiceService.getInvoice(999999999L)).thenThrow(new InvoiceNotFoundException(999999999L));

        mockMvc.perform(get("/api/invoices/999999999")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/invoices/abc")).andExpect(status().isBadRequest());
    }

    @Test
    void missingFilePartReturns400() throws Exception {
        mockMvc.perform(multipart("/api/invoices/extract").param("note", "no file"))
                .andExpect(status().isBadRequest());
    }
}
