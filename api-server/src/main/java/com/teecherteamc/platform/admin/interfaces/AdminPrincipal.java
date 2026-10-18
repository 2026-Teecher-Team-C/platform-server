package com.teecherteamc.platform.admin.interfaces;

import java.io.Serializable;
import java.util.UUID;

/** 세션에 들어가는 로그인 주체. 권한 판단에 쓸 값은 매번 admin_users에서 다시 읽는다. */
public record AdminPrincipal(UUID adminId, String username) implements Serializable {
}
