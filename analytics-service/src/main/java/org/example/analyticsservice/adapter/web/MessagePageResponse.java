package org.example.analyticsservice.adapter.web;

import java.util.List;

/** {@code nextCursor} is null on the last page; pass it back as {@code cursor}. */
public record MessagePageResponse(List<MessageResponse> items, String nextCursor) {}
