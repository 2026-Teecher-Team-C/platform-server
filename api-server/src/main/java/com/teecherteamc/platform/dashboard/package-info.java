/**
 * 위협 대시보드 컨텍스트. 관리자 콘솔 대시보드 위젯용 읽기 전용 집계를 낸다.
 *
 * <p>테이블: 없음 (download_events·download_event_threats 뷰(V7)·agents·devices를 읽기만 한다)
 * <br>애그리거트 루트: 없음 — lookup처럼 애그리거트를 로딩하지 않고 전용 쿼리로 읽는다
 *
 * <p>하위 패키지: domain / application / infrastructure / interfaces (api-server/README.md 참고)
 */
package com.teecherteamc.platform.dashboard;
