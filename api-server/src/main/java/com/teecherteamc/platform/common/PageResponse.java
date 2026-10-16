package com.teecherteamc.platform.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * 목록 API의 공통 응답 모양.
 * page는 0부터 시작한다. (Spring Data의 Page와 같은 규칙)
 *
 * @param content       이번 페이지의 항목들
 * @param page          현재 페이지 번호 (0부터)
 * @param size          한 페이지에 요청한 개수
 * @param totalElements 전체 항목 수
 * @param totalPages    전체 페이지 수
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    /** Spring Data의 Page를 그대로 응답 모양으로 바꾼다. */
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    /**
     * 엔티티 페이지를 응답용 객체(DTO) 페이지로 바꾼다.
     * 예: PageResponse.from(page, WhitelistItem::from)
     * 엔티티를 그대로 내려주지 않고 응답용 객체로 감싸는 데 쓴다.
     */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}