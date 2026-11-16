package com.teecherteamc.platform.common;

/** 업로드 크기 초과 안내 문구. 업로드 검사와 multipart 한도 초과 처리가 같은 문구를 쓴다. */
public final class FileSizeMessages {

    private static final long MIB = 1024 * 1024;

    private FileSizeMessages() {
    }

    public static String tooLarge(long maxBytes) {
        return "파일은 %dMiB 이하여야 합니다.".formatted(maxBytes / MIB);
    }
}
