package com.actify.invoice.security;

import com.actify.invoice.controller.AdminReviewController;
import com.actify.invoice.dto.WorkflowDtos.AssignmentResult;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.review.IntakeBoardService;
import com.actify.invoice.review.ReviewAssignmentService;
import com.actify.invoice.user.AppUserRepository;
import com.actify.invoice.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.servlet.http.Cookie;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin intake board and assignment routes: ADMIN only; "reassign" reaches the service only when sent. */
@WebMvcTest(controllers = AdminReviewController.class)
@WithInvoiceWebSecurity
class AdminIntakeRouteProtectionWebTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenService tokenService;
    @MockBean private AppUserRepository users;
    @MockBean private InvoiceRepository invoiceRepository;
    @MockBean private ReviewAssignmentService assignments;
    @MockBean private IntakeBoardService intakeBoard;

    @Test
    void onlyAdminsSeeTheIntakeBoardOrAssign() throws Exception {
        Cookie vendor = TestLogins.cookieFor(TestLogins.user(2, Role.VENDOR), users, tokenService);
        Cookie reviewer = TestLogins.cookieFor(TestLogins.user(3, Role.REVIEWER), users, tokenService);
        String body = "{\"invoiceIds\":[41],\"reviewerId\":7}";

        mvc.perform(get("/api/admin/intake")).andExpect(status().isUnauthorized());
        for (Cookie cookie : new Cookie[]{vendor, reviewer}) {
            mvc.perform(get("/api/admin/intake").cookie(cookie)).andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/assignments").cookie(cookie).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(assignments, intakeBoard);
    }

    @Test
    void anEmptyBoardIsAValidAnswerNotAnError() throws Exception {
        Cookie admin = TestLogins.cookieFor(TestLogins.user(1, Role.ADMIN), users, tokenService);
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("needsAssignment", Collections.emptyList());
        empty.put("assigned", Collections.emptyList());
        when(intakeBoard.board()).thenReturn(empty);

        mvc.perform(get("/api/admin/intake").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsAssignment").isEmpty())
                .andExpect(jsonPath("$.assigned").isEmpty());
    }

    // The 2026-10-23 local-demo failure: the running JVM could not load a class that Eclipse's rebuild had deleted.
    // The admin gets a generic message plus a reference to the log line, never the class name or a stack trace.
    @Test
    void aBrokenBoardIsA500WithAReferenceAndNoInternals() throws Exception {
        Cookie admin = TestLogins.cookieFor(TestLogins.user(1, Role.ADMIN), users, tokenService);
        when(intakeBoard.board()).thenThrow(new NoClassDefFoundError("com/actify/invoice/review/IntakeBoardService$1"));

        mvc.perform(get("/api/admin/intake").cookie(admin))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.errorId").value(matchesPattern("[0-9a-f]{8}")))
                .andExpect(content().string(not(containsString("IntakeBoardService"))))
                .andExpect(content().string(not(containsString("NoClassDefFoundError"))));
    }

    @Test
    void assignDefaultsToNoReassignAndReturnsCanonicalValues() throws Exception {
        Cookie admin = TestLogins.cookieFor(TestLogins.user(1, Role.ADMIN), users, tokenService);
        when(assignments.assign(any(), eq(7L), eq(false))).thenReturn(Collections.singletonList(
                new AssignmentResult(41L, true, "Assigned.", InvoiceStatus.QUEUED, 7L, "alex")));

        mvc.perform(post("/api/admin/assignments").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"invoiceIds\":[41],\"reviewerId\":7}")).andExpect(status().isForbidden()); // no CSRF
        mvc.perform(post("/api/admin/assignments").cookie(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceIds\":[41],\"reviewerId\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assigned").value(true))
                .andExpect(jsonPath("$[0].status").value("QUEUED"))
                .andExpect(jsonPath("$[0].reviewerName").value("alex"));
        verify(assignments).assign(any(), eq(7L), eq(false));

        mvc.perform(post("/api/admin/assignments").cookie(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"invoiceIds\":[41],\"reviewerId\":8,\"reassign\":true}")).andExpect(status().isOk());
        verify(assignments).assign(any(), eq(8L), eq(true));
    }
}
