package hu.auxin.ibkrfacade.session;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Parsed trading_hours / liquid_hours of a ContractDetails. TWS sends about one week ahead, in the time zone of the
 * exchange (timeZoneId), eg.:
 * <pre>
 * 20261015:0930-20261015:1600;20261016:0930-20261016:1600;20261017:CLOSED     (API 970+)
 * 20261015:0930-1600,1700-1800;20261017:CLOSED                                 (older format)
 * </pre>
 * Adjacent or overlapping sessions are merged, so a session is a continuous open period.
 *
 * @param lastDate the last date mentioned (including the CLOSED days), null if there is no valid entry
 */
public record TradingHours(List<Session> sessions, LocalDate lastDate) {

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmm");

    public record Session(Instant open, Instant close) {

        boolean contains(Instant time) {
            return !time.isBefore(open) && time.isBefore(close);
        }
    }

    /**
     * Malformed entries are skipped.
     */
    public static TradingHours parse(String hours, ZoneId zone) {
        List<Session> parsed = new ArrayList<>();
        LocalDate lastDate = null;
        if (hours != null) {
            for (String entry : hours.split(";")) {
                int colon = entry.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                try {
                    LocalDate date = LocalDate.parse(entry.substring(0, colon).trim(), DATE);
                    lastDate = max(lastDate, date);
                    String ranges = entry.substring(colon + 1).trim();
                    if (ranges.equalsIgnoreCase("CLOSED")) {
                        continue;
                    }
                    for (String range : ranges.split(",")) {
                        Session session = session(date, range.trim(), zone);
                        parsed.add(session);
                        lastDate = max(lastDate, session.close().atZone(zone).toLocalDate());
                    }
                } catch (DateTimeParseException | IndexOutOfBoundsException e) {
                    // malformed entry
                }
            }
        }
        return new TradingHours(merge(parsed), lastDate);
    }

    /**
     * @param range HHmm-yyyyMMdd:HHmm or HHmm-HHmm (an end not after the start is on the next day)
     */
    private static Session session(LocalDate date, String range, ZoneId zone) {
        int dash = range.indexOf('-');
        LocalTime start = LocalTime.parse(range.substring(0, dash), TIME);
        String endPart = range.substring(dash + 1);
        int colon = endPart.indexOf(':');
        LocalDate endDate = colon < 0 ? date : LocalDate.parse(endPart.substring(0, colon), DATE);
        LocalTime end = LocalTime.parse(colon < 0 ? endPart : endPart.substring(colon + 1), TIME);
        if (colon < 0 && !end.isAfter(start)) {
            endDate = endDate.plusDays(1);
        }
        return new Session(date.atTime(start).atZone(zone).toInstant(), endDate.atTime(end).atZone(zone).toInstant());
    }

    private static List<Session> merge(List<Session> sessions) {
        List<Session> sorted = sessions.stream()
                .filter(session -> session.close().isAfter(session.open()))
                .sorted(Comparator.comparing(Session::open))
                .toList();
        List<Session> merged = new ArrayList<>();
        for (Session session : sorted) {
            Session previous = merged.isEmpty() ? null : merged.getLast();
            if (previous != null && !session.open().isAfter(previous.close())) {
                Instant close = session.close().isAfter(previous.close()) ? session.close() : previous.close();
                merged.set(merged.size() - 1, new Session(previous.open(), close));
            } else {
                merged.add(session);
            }
        }
        return List.copyOf(merged);
    }

    /**
     * The session containing the given time
     */
    public Optional<Session> current(Instant time) {
        return sessions.stream().filter(session -> session.contains(time)).findFirst();
    }

    /**
     * The first session starting after the given time
     */
    public Optional<Session> next(Instant time) {
        return sessions.stream().filter(session -> session.open().isAfter(time)).findFirst();
    }

    private static LocalDate max(LocalDate first, LocalDate second) {
        return first == null || second.isAfter(first) ? second : first;
    }
}
