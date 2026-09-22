/**
 * 장비 컨텍스트. 물리 장비와 에이전트 설치 인스턴스를 소유한다.
 * 식별 단위는 장비이며 사용자 신원과 MAC 주소는 보관하지 않는다.
 *
 * <p>테이블: devices, agents
 * <br>애그리거트 루트: Device, Agent
 *
 * <p>하위 패키지: domain / application / infrastructure / interfaces (api-server/README.md 참고)
 */
package com.teecherteamc.platform.fleet;
