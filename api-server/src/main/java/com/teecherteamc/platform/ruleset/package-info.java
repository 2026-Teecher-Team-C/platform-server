/**
 * 룰셋 컨텍스트. YARA 룰셋 버전과 개별 룰을 소유한다. 활성 룰셋은 항상 1개다.
 *
 * <p>테이블: yara_rulesets, yara_rules
 * <br>애그리거트 루트: Ruleset
 *
 * <p>하위 패키지: domain / application / infrastructure / interfaces (api-server/README.md 참고)
 */
package com.teecherteamc.platform.ruleset;
