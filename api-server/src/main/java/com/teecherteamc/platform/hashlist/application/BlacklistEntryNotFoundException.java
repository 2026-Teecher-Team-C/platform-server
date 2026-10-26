package com.teecherteamc.platform.hashlist.application;

/**
 * 블랙리스트에서 대상을 못 찾았을 때 던진다. 컨트롤러가 404로 바꾼다.
 *
 * "못 찾았다"에는 두 경우가 같이 들어 있다.
 *  - 그 해시가 블랙리스트에 아예 없다.
 *  - 있지만 해제된 항목(is_active = false)이다.
 * 사유 수정은 활성 항목만 고칠 수 있어서, 두 경우를 구분하지 않고 같은 404로 내려준다. (카드: "활성 항목이 없으면 404")
 *
 * 이 예외는 HTTP를 모른다. 상태 코드로 바꾸는 건 interfaces 계층(컨트롤러)이 한다.
 */
public class BlacklistEntryNotFoundException extends RuntimeException {

    public BlacklistEntryNotFoundException(String message) {
        super(message);
    }
}