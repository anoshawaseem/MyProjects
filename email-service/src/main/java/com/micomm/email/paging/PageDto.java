package com.micomm.email.paging;

import java.util.List;

/** Keyset page: pass {@code nextCursor} back as {@code cursor}; null means no more rows. */
public record PageDto<T>(List<T> items, String nextCursor) {
}
