package com.teecherteamc.platform.hashlist.domain;

/**
 * 같은 해시에 대한 블랙/화이트리스트 쓰기를 한 번에 하나씩만 처리하게 하는 잠금.
 *
 * 없으면 같은 해시를 동시에 블랙리스트와 화이트리스트에 등록할 때 서로의 "상대 목록에 있나?" 확인을
 * 통과해서 둘 다 활성이 될 수 있다(그러면 조회 순서상 화이트리스트가 이긴다). 같은 해시를 두 번 동시에
 * 등록할 때도 PK 충돌로 500이 나는 대신, 뒤의 요청이 앞의 결과를 보고 409를 받는다.
 */
public interface HashListWriteLock {

    /** 현재 트랜잭션이 끝날 때까지 그 해시를 잠근다. 반드시 트랜잭션 안에서 부른다. */
    void lock(HashKey key);
}
