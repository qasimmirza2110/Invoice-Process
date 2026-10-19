package com.actify.invoice.dashboard;

import com.actify.invoice.versioning.FieldChange;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/** Metric formulas and business-day boundaries, without a database. */
class DashboardMetricsTest {

    @Test
    void correctionRateCountsChangedFieldsOverComparedFields() {
        CorrectionStats stats = new CorrectionStats();
        assertThat(stats.getRate()).as("no submissions -> no number").isNull();

        // Submission 1: 2 line items in both versions, total + one quantity corrected.
        stats.add(Arrays.asList(
                new FieldChange("totalAmount", FieldChange.CHANGED, "999.00", "118.00"),
                new FieldChange("items[1].quantity", FieldChange.CHANGED, "1", "2")), 2, 2);
        // Submission 2: nothing changed, 1 item.
        stats.add(Collections.<FieldChange>emptyList(), 1, 1);
        // Submission 3: a line item was added (V1 had 1, submitted has 2).
        stats.add(Collections.singletonList(new FieldChange("items[1]", FieldChange.ADDED, null, "Cable")), 1, 2);

        assertThat(stats.getSubmissions()).isEqualTo(3);
        assertThat(stats.getFieldsCompared()).isEqualTo((12 + 10) + (12 + 5) + (12 + 10));
        assertThat(stats.getFieldsChanged()).isEqualTo(2 + 0 + 5);
        assertThat(stats.getRate()).isEqualTo(7.0 / 61.0);
        assertThat(stats.getChangesByField()).containsEntry("totalAmount", 1L).containsEntry("items[].quantity", 1L)
                .containsEntry("items[] (line added)", 1L);
    }

    @Test
    void businessDaysAreKolkataDaysWhateverTheServerZone() {
        // 2026-10-19 20:00 UTC is already 2026-10-20 01:30 in Kolkata.
        Clock utcServer = Clock.fixed(Instant.parse("2026-10-19T20:00:00Z"), ZoneId.of("UTC"));
        BusinessPeriod today = BusinessPeriod.lastDays(utcServer, 1);

        assertThat(today.getFirstDay()).isEqualTo(LocalDate.of(2026, 10, 20));
        // Kolkata midnight = 18:30 UTC of the previous day, expressed in the server's zone (UTC here).
        assertThat(today.start()).isEqualTo(LocalDateTime.of(2026, 10, 19, 18, 30));
        assertThat(today.businessDayOf(LocalDateTime.of(2026, 10, 19, 18, 29))).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(today.businessDayOf(LocalDateTime.of(2026, 10, 19, 18, 30))).isEqualTo(LocalDate.of(2026, 10, 20));

        BusinessPeriod week = BusinessPeriod.lastDays(utcServer, 7);
        assertThat(week.getFirstDay()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(week.getLastDay()).isEqualTo(LocalDate.of(2026, 10, 20));
    }
}
