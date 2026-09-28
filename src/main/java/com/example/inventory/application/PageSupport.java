package com.example.inventory.application;

import com.example.inventory.domain.ApiException;

public final class PageSupport {
    private PageSupport() {}

    public static int offset(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("page 必须大于或等于 0，size 必须介于 1 和 100 之间");
        }
        try {
            return Math.multiplyExact(page, size);
        } catch (ArithmeticException exception) {
            throw ApiException.badRequest("分页范围过大");
        }
    }

    public static int totalPages(long total, int size) {
        return total == 0 ? 0 : Math.toIntExact((total + size - 1) / size);
    }
}
