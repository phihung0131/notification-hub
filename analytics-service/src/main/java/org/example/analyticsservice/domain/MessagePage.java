package org.example.analyticsservice.domain;

import java.util.List;

/** One keyset page. {@code nextCursor} is null on the last page. */
public record MessagePage(List<MessageRecord> items, String nextCursor) {}
