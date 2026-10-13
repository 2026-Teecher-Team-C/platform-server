package com.teecherteamc.platform.verdict.domain;

/** 스풀 파일 하나에 대한 쓰기 세션. 청크를 받을 때마다 디스크에 쓰면서 동시에 SHA-256을 갱신한다. */
public interface SpoolSession {

    /** @throws SpoolSizeExceededException 누적 크기가 상한을 넘으면 */
    void write(byte[] chunk);

    /** 스트림이 끝난 뒤 호출한다. 해시와 최종 크기를 확정한다. */
    SpoolResult finish();

    /** 어떤 경로로 끝나든(성공/해시불일치/연결끊김) 반드시 호출해 디스크에서 지운다. */
    void delete();
}
