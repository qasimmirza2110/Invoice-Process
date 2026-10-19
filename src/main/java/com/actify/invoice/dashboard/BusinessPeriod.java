package com.actify.invoice.dashboard;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Dashboard dates are BUSINESS days in Asia/Kolkata (decision D16), whatever time zone the server runs in.
 *
 * How timestamps really are stored (proven by a pgtest): Hibernate writes every LocalDateTime as UTC wall-clock time
 * (hibernate.jdbc.time_zone=UTC) and converts it back to the server's zone when reading. So a period boundary is
 * computed as an instant ("00:00 in Kolkata") and handed to Hibernate as a server-zone LocalDateTime - never written
 * into SQL as text.
 */
public final class BusinessPeriod {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    private final Clock clock;
    private final LocalDate firstDay;
    private final LocalDate lastDay;

    private BusinessPeriod(Clock clock, LocalDate firstDay, LocalDate lastDay) {
        this.clock = clock;
        this.firstDay = firstDay;
        this.lastDay = lastDay;
    }

    /** The last `days` business days including today (1 = today only). */
    public static BusinessPeriod lastDays(Clock clock, int days) {
        LocalDate today = ZonedDateTime.now(clock.withZone(BUSINESS_ZONE)).toLocalDate();
        return new BusinessPeriod(clock, today.minusDays(Math.max(1, days) - 1L), today);
    }

    /** Start of the first day, as a server-zone LocalDateTime (the form Hibernate converts correctly). */
    public LocalDateTime start() {
        return LocalDateTime.ofInstant(firstDay.atStartOfDay(BUSINESS_ZONE).toInstant(), clock.getZone());
    }

    /** The business day (Kolkata) of a timestamp read from the database (server-zone LocalDateTime). */
    public LocalDate businessDayOf(LocalDateTime serverTime) {
        return serverTime.atZone(clock.getZone()).withZoneSameInstant(BUSINESS_ZONE).toLocalDate();
    }

    public LocalDate getFirstDay() { return firstDay; }
    public LocalDate getLastDay() { return lastDay; }
    public String getTimeZone() { return BUSINESS_ZONE.getId(); }
}
