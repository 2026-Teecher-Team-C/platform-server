package com.teecherteamc.platform.hashlist.domain;

import java.util.List;

/**
 * download_events는 event 컨텍스트 소유 테이블이지만, 블랙리스트 등록 직후 "이 해시를 이미 받은 기기"를
 * 알려야 해서 hashlist가 자기 소유의 얇은 포트로 직접 읽는다(event/domain/FileVerdictExistsPort와 같은 패턴).
 */
public interface FindAffectedDevicesPort {

    List<AffectedDeviceRow> findBySha256(String sha256);
}
