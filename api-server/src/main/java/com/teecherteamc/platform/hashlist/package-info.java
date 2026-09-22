/**
 * 해시 리스트 컨텍스트. 블랙리스트/화이트리스트를 소유한다.
 * 화이트리스트는 SHA256만 허용한다. 블룸 필터는 삭제가 불가능하므로 화이트리스트가 조회 사슬 맨 앞에 선다.
 *
 * <p>테이블: hash_blacklist, hash_whitelist
 * <br>애그리거트 루트: BlacklistEntry, WhitelistEntry
 *
 * <p>하위 패키지: domain / application / infrastructure / interfaces (api-server/README.md 참고)
 */
package com.teecherteamc.platform.hashlist;
