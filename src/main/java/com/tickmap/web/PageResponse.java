package com.tickmap.web;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

// Spring's Page JSON isn't a stable contract, so we expose our own shape.
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages, boolean hasNext) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(), page.hasNext());
    }
}
