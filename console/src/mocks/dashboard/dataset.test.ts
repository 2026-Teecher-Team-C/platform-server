import { describe, expect, it } from 'vitest'
import type { Severity } from '../../lib/api/dashboard'
import { mockDownloadEvents } from '../data/events'
import { createDashboardDataset, DASHBOARD_RULES } from './dataset'
import type { DashboardDataset, EngineScenario } from './dataset'
import { createRandom } from './random'

const NOW = new Date('2026-11-08T03:00:00Z')
const MINUTE = 60_000
const DAY = 24 * 60 * MINUTE
const SEVERITY_RANK: Record<Severity, number> = { LOW: 1, MEDIUM: 2, HIGH: 3, CRITICAL: 4 }
const legacyIds = new Set(mockDownloadEvents.map((event) => event.eventId))

function generatedEvents(dataset: DashboardDataset) {
  return dataset.events.filter((event) => !legacyIds.has(event.eventId))
}

function isBlock(decision: string | null) {
  return decision === 'BLOCKED' || decision === 'FAIL_CLOSE'
}

// API 명세 §0 이벤트 심각도: BLACKLIST는 블랙리스트 값, ENGINE·CACHE는 같은 sha256의 최신 SUCCESS 분석 중 가장 높은 룰.
function severityOf(dataset: DashboardDataset, event: DashboardDataset['events'][number]): Severity | null {
  if (event.decision !== 'BLOCKED' || !event.sha256) return null
  if (event.decisionSource === 'BLACKLIST') return dataset.blacklist.get(event.sha256) ?? null
  const latest = dataset.analyses
    .filter((a) => a.sha256 === event.sha256 && a.status === 'SUCCESS')
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0]
  if (!latest || latest.rules.length === 0) return null
  return latest.rules.map((r) => r.severity).sort((a, b) => SEVERITY_RANK[b] - SEVERITY_RANK[a])[0]
}

function unacknowledged(dataset: DashboardDataset) {
  return dataset.events.filter((event) => {
    const severity = severityOf(dataset, event)
    return (
      severity !== null &&
      SEVERITY_RANK[severity] >= SEVERITY_RANK.HIGH &&
      !dataset.acknowledgements.has(event.eventId)
    )
  })
}

describe('createRandom', () => {
  it('같은 시드면 같은 수열이고 [0, 1) 범위다', () => {
    const a = createRandom(7)
    const b = createRandom(7)
    const values = Array.from({ length: 50 }, () => a())
    expect(values).toEqual(Array.from({ length: 50 }, () => b()))
    expect(values.every((v) => v >= 0 && v < 1)).toBe(true)
  })
})

describe('createDashboardDataset', () => {
  it('같은 시드와 now면 같은 데이터셋을 만든다', () => {
    const a = createDashboardDataset({ now: NOW })
    const b = createDashboardDataset({ now: NOW })
    expect(a.events.map((e) => [e.eventId, e.createdAt])).toEqual(
      b.events.map((e) => [e.eventId, e.createdAt]),
    )
  })

  it('이벤트는 최근 14일 안에 있고 now 기준 상대 시각이다', () => {
    const dataset = createDashboardDataset({ now: NOW })
    const generated = generatedEvents(dataset)
    for (const event of generated) {
      const time = new Date(event.createdAt).getTime()
      expect(time).toBeGreaterThanOrEqual(NOW.getTime() - 14 * DAY)
      expect(time).toBeLessThanOrEqual(NOW.getTime())
    }
    const times = generated.map((e) => new Date(e.createdAt).getTime())
    expect([...times].sort((x, y) => y - x)).toEqual(times)

    const shifted = createDashboardDataset({ now: new Date(NOW.getTime() + DAY) })
    const original = new Map(generated.map((e) => [e.eventId, e.createdAt]))
    const shiftedGenerated = generatedEvents(shifted)
    expect(shiftedGenerated).toHaveLength(generated.length)
    for (const event of shiftedGenerated) {
      expect(new Date(event.createdAt).getTime() - new Date(original.get(event.eventId)!).getTime()).toBe(DAY)
    }
  })

  it('기존 이벤트와 TLSH fixture를 모두 그대로 뒤에 붙인다', () => {
    const dataset = createDashboardDataset({ now: NOW })
    expect(dataset.events.slice(-mockDownloadEvents.length)).toEqual(mockDownloadEvents)
  })

  it('M1 최소 조건을 만족한다', () => {
    const dataset = createDashboardDataset({ now: NOW })
    const generated = generatedEvents(dataset)
    expect(generated.length).toBeGreaterThanOrEqual(400)
    expect(generated.length).toBeLessThanOrEqual(800)

    // 에이전트
    expect(dataset.agents).toHaveLength(42)
    const count = (status: string) => dataset.agents.filter((a) => a.status === status).length
    expect([count('ACTIVE'), count('INACTIVE'), count('REVOKED')]).toEqual([38, 2, 2])
    expect(dataset.agents.every((a) => /^[A-Z]+-\d{3}$/.test(a.hostname))).toBe(true)
    expect(dataset.agents.every((a) => /^10000000-0000-4000-8000-\d{12}$/.test(a.agentId))).toBe(true)
    expect(generated.every((e) => /^30000000-0000-4000-8000-\d{12}$/.test(e.eventId))).toBe(true)
    const blockedAgentIds = new Set(generated.filter((e) => isBlock(e.decision)).map((e) => e.agentId))
    expect(
      dataset.agents.some((a) => a.status === 'ACTIVE' && !blockedAgentIds.has(a.agentId)),
    ).toBe(true)

    // 결정 분포
    const blocks = generated.filter((e) => isBlock(e.decision))
    const ratio = blocks.length / generated.length
    expect(ratio).toBeGreaterThanOrEqual(0.03)
    expect(ratio).toBeLessThanOrEqual(0.08)
    const decisions = new Set(generated.map((e) => e.decision))
    for (const decision of ['RELEASED', 'BYPASSED', 'BLOCKED', 'FAIL_CLOSE']) {
      expect(decisions).toContain(decision)
    }
    const passed = generated.filter((e) => e.decision === 'RELEASED' || e.decision === 'BYPASSED')
    expect(passed.length / generated.length).toBeGreaterThan(0.85)
    const blockedSources = new Set(
      generated.filter((e) => e.decision === 'BLOCKED').map((e) => e.decisionSource),
    )
    expect(blockedSources).toEqual(new Set(['ENGINE', 'CACHE', 'BLACKLIST']))

    // ENGINE 판정마다 같은 시각 SUCCESS 분석, RELEASED+ENGINE은 룰 없음
    for (const event of generated.filter((e) => e.decisionSource === 'ENGINE')) {
      const match = dataset.analyses.find(
        (a) => a.sha256 === event.sha256 && a.createdAt === event.createdAt && a.status === 'SUCCESS',
      )
      expect(match, event.eventId).toBeDefined()
      if (event.decision === 'RELEASED') expect(match!.rules).toEqual([])
    }
    // CACHE 차단은 앞선 ENGINE 차단의 sha256을 재사용
    for (const event of generated.filter((e) => e.decision === 'BLOCKED' && e.decisionSource === 'CACHE')) {
      expect(
        generated.some(
          (e) =>
            e.decision === 'BLOCKED' &&
            e.decisionSource === 'ENGINE' &&
            e.sha256 === event.sha256 &&
            e.createdAt < event.createdAt,
        ),
      ).toBe(true)
    }

    // 룰
    expect(DASHBOARD_RULES.map((r) => r.ruleName)).toEqual([
      'Suspicious_PE_Packer',
      'Embedded_PowerShell_Loader',
      'Office_Macro_AutoOpen',
      'EICAR_Test_File',
      'Obfuscated_JS_Dropper',
      'Ransom_Note_Strings',
      'Packed_UPX_Generic',
    ])
    expect(dataset.analyses.some((a) => a.rules.length >= 2)).toBe(true)
    const usedRules = new Set(dataset.analyses.flatMap((a) => a.rules.map((r) => r.ruleName)))
    for (const rule of DASHBOARD_RULES) expect(usedRules).toContain(rule.ruleName)
    for (const analysis of dataset.analyses) {
      for (const rule of analysis.rules) {
        expect(DASHBOARD_RULES).toContainEqual(rule)
      }
    }

    // 블랙리스트
    expect(dataset.blacklist.size).toBe(3)
    expect([...dataset.blacklist.values()].some((s) => SEVERITY_RANK[s] >= SEVERITY_RANK.HIGH)).toBe(true)
    for (const event of generated.filter((e) => e.decisionSource === 'BLACKLIST')) {
      expect(dataset.blacklist.has(event.sha256!)).toBe(true)
    }
  })

  it('FAIL_CLOSE는 해시와 판정 소스가 비어 있다', () => {
    const failCloses = generatedEvents(createDashboardDataset({ now: NOW })).filter(
      (e) => e.decision === 'FAIL_CLOSE',
    )
    expect(failCloses.length).toBeGreaterThan(0)
    for (const event of failCloses) {
      expect(event.sha256).toBeNull()
      expect(event.decisionSource).toBeNull()
    }
    const withoutName = failCloses.filter((e) => e.filename === null).length
    expect(withoutName).toBeGreaterThan(0)
    expect(withoutName).toBeLessThan(failCloses.length)
  })

  it('미확인 HIGH 이상 차단이 어떤 now에서도 2~8건이다', () => {
    for (const now of [NOW, new Date('2027-03-01T12:34:56Z'), new Date('2026-12-25T00:00:00Z')]) {
      const dataset = createDashboardDataset({ now })
      const count = unacknowledged(dataset).length
      expect(count).toBeGreaterThanOrEqual(2)
      expect(count).toBeLessThanOrEqual(8)
      // 확인됨/미확인 경계: 3일보다 오래된 HIGH 이상 차단은 모두 확인됨
      for (const event of dataset.events) {
        const severity = severityOf(dataset, event)
        if (severity && SEVERITY_RANK[severity] >= SEVERITY_RANK.HIGH) {
          const old = new Date(event.createdAt).getTime() < now.getTime() - 3 * DAY
          expect(dataset.acknowledgements.has(event.eventId)).toBe(old)
        }
      }
    }
    const sample = [...createDashboardDataset({ now: NOW }).acknowledgements.values()][0]
    expect(sample.acknowledgedBy).toBe('admin')
  })

  it('ackAll이면 미확인 차단이 없다', () => {
    const dataset = createDashboardDataset({ now: NOW, ackAll: true })
    expect(unacknowledged(dataset)).toHaveLength(0)
    for (const event of dataset.events.filter((e) => isBlock(e.decision))) {
      expect(dataset.acknowledgements.has(event.eventId)).toBe(true)
    }
  })

  describe('엔진 시나리오', () => {
    const since = (scenario: EngineScenario) => createDashboardDataset({ now: NOW, engine: scenario })
    const tenMinutesAgo = NOW.getTime() - 10 * MINUTE
    const recent = (d: DashboardDataset) =>
      d.analyses.filter((a) => new Date(a.createdAt).getTime() >= tenMinutesAgo)
    const lastSuccess = (d: DashboardDataset) =>
      d.analyses
        .filter((a) => a.status === 'SUCCESS')
        .map((a) => new Date(a.createdAt).getTime())
        .reduce((max, t) => Math.max(max, t), 0)

    it('HEALTHY는 가장 최근 분석이 SUCCESS이고 이미 회복된 TIMEOUT이 있다', () => {
      const dataset = since('HEALTHY')
      const latest = [...dataset.analyses].sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0]
      expect(latest.status).toBe('SUCCESS')
      expect(NOW.getTime() - new Date(latest.createdAt).getTime()).toBeLessThanOrEqual(10 * MINUTE)
      const timeouts = dataset.analyses.filter((a) => a.status === 'TIMEOUT')
      expect(timeouts).toHaveLength(1)
      expect(new Date(timeouts[0].createdAt).getTime()).toBeLessThan(NOW.getTime() - 30 * MINUTE)
      expect(lastSuccess(dataset)).toBe(NOW.getTime() - 2 * MINUTE)
    })

    it('DOWN 시나리오는 최근 10분 실패가 있고 그 뒤 성공이 없다', () => {
      const dataset = since('DOWN')
      const failures = recent(dataset).filter((a) => a.status === 'TIMEOUT')
      expect(failures.length).toBeGreaterThan(0)
      expect(failures.every((a) => a.engineVersion === 'unavailable')).toBe(true)
      expect(lastSuccess(dataset)).toBe(NOW.getTime() - 40 * MINUTE)
      const outage = dataset.analyses
        .filter((a) => a.status === 'TIMEOUT')
        .map((a) => new Date(a.createdAt).getTime())
        .sort((a, b) => a - b)
      expect(outage[0]).toBe(NOW.getTime() - 18 * MINUTE)
      expect(outage.at(-1)).toBeLessThanOrEqual(NOW.getTime() - MINUTE)
      expect(outage.at(-1)).toBeGreaterThanOrEqual(NOW.getTime() - 2 * MINUTE)

      const sinceTime = NOW.getTime() - 18 * MINUTE
      const failClose = generatedEvents(dataset).filter(
        (e) => e.decision === 'FAIL_CLOSE' && new Date(e.createdAt).getTime() >= sinceTime,
      )
      expect(failClose).toHaveLength(7)
      expect(new Set(failClose.map((e) => e.agentId)).size).toBe(5)
    })

    it('IDLE 시나리오는 최근 10분 분석이 없다', () => {
      const dataset = since('IDLE')
      expect(recent(dataset)).toHaveLength(0)
      expect(lastSuccess(dataset)).toBe(NOW.getTime() - 35 * MINUTE)
      expect(
        generatedEvents(dataset).filter((e) => new Date(e.createdAt).getTime() >= tenMinutesAgo),
      ).toHaveLength(0)
    })
  })
})
