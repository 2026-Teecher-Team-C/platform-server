package com.teecherteamc.platform.admin.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** admin_users.username은 64자까지다. 비밀번호 상한은 BCrypt(72바이트)보다 넉넉하게만 둔다. */
public record LoginRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 128) String password) {

    // 비밀번호가 로그·예외 메시지에 찍히지 않게 한다.
    @Override
    public String toString() {
        return "LoginRequest[username=" + username + "]";
    }
}
