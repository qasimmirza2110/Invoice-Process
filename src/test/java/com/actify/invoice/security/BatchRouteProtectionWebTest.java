package com.actify.invoice.security;

import com.actify.invoice.controller.BatchController;
import com.actify.invoice.cloud.CloudSourceSettings;
import com.actify.invoice.controller.CategoryController;
import com.actify.invoice.controller.CloudSourceController;
import com.actify.invoice.dto.BatchDtos.FileIntakeResponse;
import com.actify.invoice.intake.BatchIntakeService;
import com.actify.invoice.intake.CategoryCodes;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.user.AppUserRepository;
import com.actify.invoice.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Batch upload and category routes: who may call them, and the 202/422 contract of a single file upload. */
@WebMvcTest(controllers = {BatchController.class, CategoryController.class, CloudSourceController.class})
@WithInvoiceWebSecurity
class BatchRouteProtectionWebTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenService tokenService;
    @MockBean private AppUserRepository users;
    @MockBean private InvoiceRepository invoiceRepository;
    @MockBean private BatchIntakeService intakeService;
    @MockBean private CategoryCodes categoryCodes;
    @MockBean private CloudSourceSettings cloudSources;

    private final MockMultipartFile pdf = new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-1.4".getBytes());

    @Test
    void batchesNeedAVendorOrAdminLogin() throws Exception {
        RequestPostProcessor reviewer = TestLogins.bearerFor(TestLogins.user(3, Role.REVIEWER), users, tokenService);

        mvc.perform(get("/api/batches")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/batches").with(reviewer)).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/batches/1/files").file(pdf).with(reviewer)).andExpect(status().isForbidden());
        verifyNoInteractions(intakeService);
    }

    @Test
    void uploadAnswers202WhenQueuedAnd422WhenRejectedAndNeedsABearerToken() throws Exception {
        RequestPostProcessor vendor = TestLogins.bearerFor(TestLogins.user(2, Role.VENDOR), users, tokenService);

        mvc.perform(multipart("/api/batches/1/files").file(pdf)).andExpect(status().isUnauthorized());
        verifyNoInteractions(intakeService);

        when(intakeService.addFile(eq(1L), any(), any(), any())).thenReturn(FileIntakeResponse.accepted("a.pdf", 50L));
        mvc.perform(multipart("/api/batches/1/files").file(pdf).with(vendor))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.status").value("QUEUED"));

        when(intakeService.addFile(eq(2L), any(), any(), any()))
                .thenReturn(FileIntakeResponse.rejected("a.pdf", "INVALID_FILE", "File content is not a valid PDF"));
        mvc.perform(multipart("/api/batches/2/files").file(pdf).with(vendor))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"));
    }

    @Test
    void categoriesAreReadableByAnyoneLoggedInButManagedByAdminsOnly() throws Exception {
        RequestPostProcessor vendor = TestLogins.bearerFor(TestLogins.user(2, Role.VENDOR), users, tokenService);

        mvc.perform(get("/api/categories")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/categories").with(vendor)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/categories").with(vendor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"X1\",\"displayName\":\"X\"}")).andExpect(status().isForbidden());
    }

    @Test
    void theDeclaredSourceIsPassedToTheIntakeServiceUnchanged() throws Exception {
        RequestPostProcessor vendor = TestLogins.bearerFor(TestLogins.user(2, Role.VENDOR), users, tokenService);
        when(intakeService.addFile(eq(3L), any(), isNull(), eq("GOOGLE_DRIVE")))
                .thenReturn(FileIntakeResponse.rejected("a.pdf", "SOURCE_MISMATCH", "This batch only takes files from LOCAL"));
        mvc.perform(multipart("/api/batches/3/files").file(pdf).param("source", "GOOGLE_DRIVE").with(vendor))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SOURCE_MISMATCH"));
        verify(intakeService).addFile(eq(3L), any(), isNull(), eq("GOOGLE_DRIVE"));
    }

    @Test
    void cloudSourcesAreForVendorsAndAdminsOnly() throws Exception {
        RequestPostProcessor vendor = TestLogins.bearerFor(TestLogins.user(2, Role.VENDOR), users, tokenService);
        RequestPostProcessor reviewer = TestLogins.bearerFor(TestLogins.user(3, Role.REVIEWER), users, tokenService);
        when(cloudSources.describeAll()).thenReturn(java.util.Collections.emptyList());

        mvc.perform(get("/api/cloud-sources")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/cloud-sources").with(reviewer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/cloud-sources").with(vendor)).andExpect(status().isOk());
    }
}
