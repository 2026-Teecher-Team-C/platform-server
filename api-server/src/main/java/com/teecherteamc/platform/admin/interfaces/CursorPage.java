package com.teecherteamc.platform.admin.interfaces;

import java.util.List;

/** console/src/lib/api/types.ts의 CursorPage&lt;T&gt;와 1:1 대응하는 응답 포장 타입. */
public record CursorPage<T>(List<T> items, String nextCursor) {
}
