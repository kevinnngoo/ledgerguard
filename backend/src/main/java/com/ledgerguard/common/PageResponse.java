package com.ledgerguard.common;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static final int MAX_PAGE_SIZE = 100;

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalItems / size);
        return new PageResponse<>(items, page, size, totalItems, totalPages);
    }

    public static int clampSize(int size) {
        return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    }

    public static int clampPage(int page) {
        return Math.max(0, page);
    }
}
