package com.teecherteamc.platform.config;

import java.util.Arrays;
import java.util.List;

/** platform.cors.allowed-origins(쉼표 구분)를 CORS와 Origin 검사가 같은 목록으로 쓰게 한다. */
final class AllowedOrigins {

    private AllowedOrigins() {
    }

    static List<String> parse(String[] origins) {
        return Arrays.stream(origins)
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }
}
