package com.micomm.email.paging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Shared rules for the keyset-paged, date-bounded list APIs (email logs and
 * notifications): default/maximum date range, page size, and the opaque
 * cursors passed back to clients as {@code nextCursor}.
 */
public final class KeysetPaging {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    public static final Duration DEFAULT_RANGE = Duration.ofDays(30);
    /** Bounded so a query only ever spans a limited number of monthly partitions. */
    public static final Duration MAX_RANGE = Duration.ofDays(366);

    private KeysetPaging() {
    }

    /** Validated [start, end) window; defaults to the last 30 days. */
    public record Range(Instant start, Instant end) {
    }

    public static Range range(OffsetDateTime from, OffsetDateTime to) {
        Instant end = to != null ? to.toInstant() : Instant.now();
        Instant start = from != null ? from.toInstant() : end.minus(DEFAULT_RANGE);
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("'from' must be before 'to'");
        }
        if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException("Date range must not exceed " + MAX_RANGE.toDays() + " days");
        }
        return new Range(start, end);
    }

    public static int pageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("'limit' must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }

    /** One page from rows fetched with limit pageSize + 1: the extra row only signals that more exist. */
    public record Page<T>(List<T> items, T last, boolean hasMore) {
    }

    public static <T> Page<T> page(List<T> rows, int pageSize) {
        if (rows.size() <= pageSize) {
            return new Page<>(rows, rows.isEmpty() ? null : rows.get(rows.size() - 1), false);
        }
        List<T> items = List.copyOf(rows.subList(0, pageSize));
        return new Page<>(items, items.get(items.size() - 1), true);
    }

    /** Cursor for a page of a single-id ordering (recipients): the last id, or null when no more. */
    public static <T> String idCursor(Page<T> page, Function<T, UUID> id) {
        return page.hasMore() ? id.apply(page.last()).toString() : null;
    }

    public static UUID parseIdCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            return UUID.fromString(cursor);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid cursor");
        }
    }

    /** Opaque keyset position (created_at, id), encoded as base64url "epochMicros:uuid". */
    public record TimeCursor(Instant createdAt, UUID id) {

        public static <T> String of(Page<T> page, Function<T, OffsetDateTime> createdAt, Function<T, UUID> id) {
            return page.hasMore()
                    ? new TimeCursor(createdAt.apply(page.last()).toInstant(), id.apply(page.last())).encode()
                    : null;
        }

        public String encode() {
            long micros = Math.addExact(Math.multiplyExact(createdAt.getEpochSecond(), 1_000_000L),
                    createdAt.getNano() / 1_000L);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((micros + ":" + id).getBytes(StandardCharsets.UTF_8));
        }

        public static TimeCursor decode(String cursor) {
            if (cursor == null) {
                return null;
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int sep = raw.indexOf(':');
                long micros = Long.parseLong(raw.substring(0, sep));
                Instant createdAt = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                        Math.floorMod(micros, 1_000_000L) * 1_000L);
                return new TimeCursor(createdAt, UUID.fromString(raw.substring(sep + 1)));
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("Invalid cursor");
            }
        }
    }
}
