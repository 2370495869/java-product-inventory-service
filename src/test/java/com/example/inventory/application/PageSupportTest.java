package com.example.inventory.application;

import com.example.inventory.domain.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PageSupportTest {
    @Test
    void calculatesOffsetsAndEmptyPages() {
        assertEquals(40, PageSupport.offset(2, 20));
        assertEquals(0, PageSupport.totalPages(0, 20));
        assertEquals(3, PageSupport.totalPages(41, 20));
    }

    @Test
    void rejectsInvalidOrOverflowingPageRequests() {
        assertEquals(400, assertThrows(ApiException.class, () -> PageSupport.offset(-1, 20)).status());
        assertEquals(400, assertThrows(ApiException.class, () -> PageSupport.offset(0, 101)).status());
        assertEquals(400, assertThrows(ApiException.class,
                () -> PageSupport.offset(Integer.MAX_VALUE, 100)).status());
    }
}
