package com.teecherteamc.platform.admin.interfaces;

/** reason이 비어 있으면 컨트롤러가 기본값("오탐 복원")을 채운다. */
public record RestoreQuarantineRequest(String reason) {
}
