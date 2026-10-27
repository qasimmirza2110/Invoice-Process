package com.actify.invoice.security;

import com.actify.invoice.controller.DocumentController;
import com.actify.invoice.controller.InvoiceController;
import com.actify.invoice.controller.InvoiceVersionController;
import com.actify.invoice.dto.InvoiceResponse;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.service.InvoiceDocumentService;
import com.actify.invoice.service.InvoiceEditService;
import com.actify.invoice.service.InvoiceService;
import com.actify.invoice.service.InvoiceVersionQueryService;
import com.actify.invoice.user.AppUserRepository;
import com.actify.invoice.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.servlet.http.Cookie;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may call the EXISTING invoice/version/PDF routes (Phase 1: legacy invoices have no owner -> ADMIN only).
 *   no login -> 401 | wrong role on a write route -> 403 | someone else's invoice/PDF -> 404 (same as "not found")
 */
@WebMvcTest(controllers = {InvoiceController.class, InvoiceVersionController.class, DocumentController.class})
@WithInvoiceWebSecurity
class InvoiceRouteProtectionWebTest {

    private static final String DOC_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String UPDATE_BODY = "{\"baseVersion\":1,\"items\":[]}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtTokenService tokenService;

    @MockBean private AppUserRepository users;
    @MockBean private InvoiceRepository invoiceRepository;
    @MockBean private InvoiceService invoiceService;
    @MockBean private InvoiceEditService editService;
    @MockBean private InvoiceVersionQueryService queryService;
    @MockBean private InvoiceDocumentService documentService;

    private final MockMultipartFile pdf = new MockMultipartFile("file", "x.pdf", "application/pdf", "%PDF-1.4".getBytes());

    private MockHttpServletRequestBuilder[] readRoutes() {
        return new MockHttpServletRequestBuilder[]{
                get("/api/invoices/5"),
                get("/api/invoices/5/versions"),
                get("/api/invoices/5/versions/1"),
                get("/api/invoices/5/versions/compare").param("from", "1").param("to", "2"),
                get("/api/documents/" + DOC_ID),
                get("/api/invoices/5/document")};
    }

    @Test
    void everyExistingRouteNeedsALogin() throws Exception {
        for (MockHttpServletRequestBuilder route : readRoutes()) {
            mvc.perform(route).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        }
        mvc.perform(multipart("/api/invoices/extract").file(pdf).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/invoices/5").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(invoiceService, editService, queryService, documentService);
    }

    @Test
    void vendorsAndReviewersCannotExtractOrCorrectInvoicesThatAreNotTheirs() throws Exception {
        Cookie vendor = TestLogins.cookieFor(TestLogins.user(2, Role.VENDOR), users, tokenService);
        Cookie reviewer = TestLogins.cookieFor(TestLogins.user(3, Role.REVIEWER), users, tokenService);
        for (Cookie cookie : new Cookie[]{vendor, reviewer}) {
            mvc.perform(multipart("/api/invoices/extract").file(pdf).cookie(cookie).with(csrf()))
                    .andExpect(status().isForbidden());
        }
        // Vendors never correct data (route rule: 403). A reviewer may correct only an invoice ASSIGNED to them;
        // invoice 5 is not, so it answers like an invoice that does not exist (404).
        mvc.perform(put("/api/invoices/5").cookie(vendor).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_BODY)).andExpect(status().isForbidden());
        mvc.perform(put("/api/invoices/5").cookie(reviewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_BODY)).andExpect(status().isNotFound());
        verifyNoInteractions(invoiceService, editService);
    }

    /** Non-disclosure: a vendor cannot tell "exists but not yours" from "does not exist". */
    @Test
    void vendorsAndReviewersGet404ForLegacyInvoicesVersionsAndPdfs() throws Exception {
        Invoice owner = new Invoice();
        owner.setId(5L);
        when(invoiceRepository.findFirstBySourceDocument_IdOrderByIdAsc(DOC_ID)).thenReturn(Optional.of(owner));

        for (Role role : new Role[]{Role.VENDOR, Role.REVIEWER}) {
            Cookie cookie = TestLogins.cookieFor(TestLogins.user(role == Role.VENDOR ? 2 : 3, role), users, tokenService);
            for (MockHttpServletRequestBuilder route : readRoutes()) {
                mvc.perform(route.cookie(cookie)).andExpect(status().isNotFound());
            }
            mvc.perform(get("/api/invoices/5").cookie(cookie))
                    .andExpect(jsonPath("$.error").value("Invoice not found"))
                    .andExpect(jsonPath("$.message").value("Invoice not found with id 5"));
            mvc.perform(get("/api/documents/" + DOC_ID).cookie(cookie))
                    .andExpect(jsonPath("$.error").value("Document not found"));
        }
        verifyNoInteractions(invoiceService, queryService, documentService);
    }

    @Test
    void adminReadsInvoicesAndPdfsThroughTheirOwningInvoice() throws Exception {
        Cookie admin = TestLogins.cookieFor(TestLogins.user(1, Role.ADMIN), users, tokenService);
        Invoice invoice = new Invoice();
        invoice.setId(5L);
        when(invoiceService.getInvoice(anyLong())).thenReturn(InvoiceResponse.fromEntity(invoice, 1));

        mvc.perform(get("/api/invoices/5").cookie(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(5));

        // A document id that no invoice references is not served - not even to an admin.
        when(invoiceRepository.findFirstBySourceDocument_IdOrderByIdAsc(any())).thenReturn(Optional.empty());
        mvc.perform(get("/api/documents/" + DOC_ID).cookie(admin)).andExpect(status().isNotFound());
        verifyNoInteractions(documentService);
    }

    /** Phase 2: a vendor reads invoices it uploaded; another vendor gets the same 404 as for a missing invoice. */
    @Test
    void vendorsReadTheirOwnInvoicesOnly() throws Exception {
        com.actify.invoice.user.AppUser owner = TestLogins.user(7, Role.VENDOR);
        Cookie ownerCookie = TestLogins.cookieFor(owner, users, tokenService);
        Cookie otherCookie = TestLogins.cookieFor(TestLogins.user(8, Role.VENDOR), users, tokenService);
        Invoice invoice = new Invoice();
        invoice.setId(5L);
        invoice.setOwner(owner);
        when(invoiceRepository.findById(5L)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findFirstBySourceDocument_IdOrderByIdAsc(DOC_ID)).thenReturn(Optional.of(invoice));
        when(invoiceService.getInvoice(5L)).thenReturn(InvoiceResponse.fromEntity(invoice, 1));

        mvc.perform(get("/api/invoices/5").cookie(ownerCookie)).andExpect(status().isOk());
        mvc.perform(get("/api/invoices/5").cookie(otherCookie)).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/" + DOC_ID).cookie(otherCookie)).andExpect(status().isNotFound());
        // Vendors still may not correct data (reviewers do, later phases).
        mvc.perform(put("/api/invoices/5").cookie(ownerCookie).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_BODY)).andExpect(status().isForbidden());
    }

    /** The invoice-addressed PDF route streams the stored bytes with safe headers, only to someone who may read it. */
    @Test
    void theOriginalPdfIsServedByInvoiceIdWithSafeHeadersToTheOwnerOnly() throws Exception {
        com.actify.invoice.user.AppUser owner = TestLogins.user(7, Role.VENDOR);
        Cookie ownerCookie = TestLogins.cookieFor(owner, users, tokenService);
        Cookie otherCookie = TestLogins.cookieFor(TestLogins.user(8, Role.VENDOR), users, tokenService);
        Invoice invoice = new Invoice();
        invoice.setId(5L);
        invoice.setOwner(owner);
        when(invoiceRepository.findById(5L)).thenReturn(Optional.of(invoice));
        byte[] original = "%PDF-1.4 original vendor bytes".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        com.actify.invoice.entity.InvoiceDocument document = org.mockito.Mockito.mock(com.actify.invoice.entity.InvoiceDocument.class);
        when(document.getOriginalFileName()).thenReturn("inv 001.pdf");
        InvoiceDocumentService.DocumentContent content = org.mockito.Mockito.mock(InvoiceDocumentService.DocumentContent.class);
        when(content.getDocument()).thenReturn(document);
        when(content.getBytes()).thenReturn(original);
        when(documentService.loadForInvoice(5L)).thenReturn(content);

        mvc.perform(get("/api/invoices/5/document").cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("application/pdf"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(original))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Content-Type-Options", "nosniff"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(get("/api/invoices/5/document").cookie(otherCookie)).andExpect(status().isNotFound());
        org.mockito.Mockito.verify(documentService, org.mockito.Mockito.times(1)).loadForInvoice(5L);
    }

    @Test
    void adminWritesStillNeedTheCsrfToken() throws Exception {
        Cookie admin = TestLogins.cookieFor(TestLogins.user(1, Role.ADMIN), users, tokenService);

        mvc.perform(multipart("/api/invoices/extract").file(pdf).cookie(admin)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Missing or invalid CSRF token. Reload the page and try again."));
        mvc.perform(put("/api/invoices/5").cookie(admin).contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(invoiceService, editService);
    }
}
