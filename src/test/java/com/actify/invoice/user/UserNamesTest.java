package com.actify.invoice.user;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How users are named in API responses: the display name when filled in, otherwise the e-mail before the "@";
 * names are loaded in one query, and a deleted account is shown as "Unknown user", never as a raw id.
 */
class UserNamesTest {

    @Test
    void nameIsTheEmailBeforeTheAt() {
        assertThat(UserNames.fromEmail("alex@example.test")).isEqualTo("alex");
        assertThat(UserNames.fromEmail("  lee.tester@example.test ")).isEqualTo("lee.tester");
        assertThat(UserNames.fromEmail("no-at-sign")).isEqualTo("no-at-sign");
        assertThat(UserNames.fromEmail("@leading-at")).isEqualTo("@leading-at");
        assertThat(UserNames.fromEmail(null)).isNull();
        assertThat(UserNames.fromEmail("   ")).isNull();
    }

    @Test
    void theLabelIsTheDisplayNameWhenFilledInOtherwiseTheEmailName() {
        assertThat(UserNames.label("Sam Admin", "sam@example.test")).isEqualTo("Sam Admin");
        assertThat(UserNames.label("  Robin  ", "robin@example.test")).isEqualTo("Robin");
        assertThat(UserNames.label("   ", "kim@example.test")).isEqualTo("kim");
        assertThat(UserNames.label(null, "kim@example.test")).isEqualTo("kim");
    }

    @Test
    void namesByIdUseTheDisplayNameFirst() {
        AppUserRepository repository = mock(AppUserRepository.class);
        AppUser sam = mock(AppUser.class);
        when(sam.getId()).thenReturn(6L);
        when(sam.getEmail()).thenReturn("sam@example.test");
        when(sam.getDisplayName()).thenReturn("Sam Admin");
        when(repository.findAllById(any())).thenReturn(Collections.singletonList(sam));

        assertThat(new UserNames(repository).namesById(Collections.singletonList(6L))).containsEntry(6L, "Sam Admin");
    }

    @Test
    void onlyUserActorLabelsBecomeIds() {
        assertThat(UserNames.idFromActorLabel("user:42")).isEqualTo(42L);
        assertThat(UserNames.idFromActorLabel(null)).isNull();
        assertThat(UserNames.idFromActorLabel("worker")).isNull();
        assertThat(UserNames.idFromActorLabel("user:abc")).isNull();
    }

    @Test
    void namesAreLoadedInOneQueryAndUnknownIdsAreLeftOut() {
        AppUserRepository repository = mock(AppUserRepository.class);
        AppUser alex = mock(AppUser.class);
        when(alex.getId()).thenReturn(7L);
        when(alex.getEmail()).thenReturn("alex@example.test");
        when(repository.findAllById(any())).thenReturn(Collections.singletonList(alex));

        Map<Long, String> names = new UserNames(repository).namesById(Arrays.asList(7L, null, 99L, 7L));

        assertThat(names).containsOnlyKeys(7L).containsEntry(7L, "alex");
    }

    @Test
    void aMissingAccountIsShownAsUnknownUserNeverAsARawId() {
        Map<Long, String> names = Collections.singletonMap(7L, "alex");
        assertThat(UserNames.nameOrUnknown(names, 7L)).isEqualTo("alex");
        assertThat(UserNames.nameOrUnknown(names, 99L)).isEqualTo(UserNames.UNKNOWN_USER).doesNotContain("99");
        assertThat(UserNames.nameOrUnknown(names, null)).isNull();
    }

    @Test
    void noIdsMeansNoQuery() {
        AppUserRepository repository = mock(AppUserRepository.class);
        assertThat(new UserNames(repository).namesById(Arrays.asList(null, null))).isEmpty();
        verify(repository, never()).findAllById(any());
    }
}
