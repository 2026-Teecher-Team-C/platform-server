import { describe, expect, it } from 'vitest'
import type { Severity } from '../../lib/api/dashboard'
import type { DownloadEventDetail } from '../../lib/api/events'
import {
  computeDailyBlocks,
  computeEngineStatus,
  computeSummary,
  computeThreats,
  computeTopSources,
  eventRules,
  eventSeverity,
  filterEvents,
  isBlock,
  listUnacknowledged,
  toListItem,
} from './aggregate'
import { createDashboardDataset } from './dataset'
import type { AnalysisRecord, DashboardDataset, EngineScenario } from './dataset'

const NOW = new Date('2026-11-08T03:00:00Z')
const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR
const TZ = 'Asia/Seoul'

const iso = (msAgo: number) => new Date(NOW.getTime() - msAgo).toISOString()

let nextId = 1
function makeEvent(partial: Partial<DownloadEventDetail>): DownloadEventDetail {
  const id = String(nextId++).padStart(12, '0')
  return {
    eventId: `ev-${id}`,
    agentId: 'agent-1',
    hostname: 'PC-1',
    sha256: null,
    filename: 'file.exe',
    mimeType: null,
    fileSize: null,
    decision: 'BLOCKED',
    decisionSource: 'ENGINE',
    cacheHit: false,
    holdDurationMs: 10,
    createdAt: iso(HOUR),
    severity: null,
    topRuleName: null,
    extraRuleCount: 0,
    acknowledged: false,
    requestHost: 'host.example.com',
    url: 'https://host.example.com/file.exe',
    bytesUploaded: 0,
    heldAt: null,
    decidedAt: null,
    similarity: null,
    ...partial,
  }
}

function makeAnalysis(partial: Partial<AnalysisRecord>): AnalysisRecord {
  return {
    sha256: 'x',
    status: 'SUCCESS',
    engineVersion: 'yara-4.5.2',
    createdAt: iso(2 * HOUR),
    rules: [],
    ...partial,
  }
}

function makeDataset(partial: Partial<DashboardDataset>): DashboardDataset {
  return {
    now: NOW,
    events: [],
    analyses: [],
    blacklist: new Map(),
    agents: [],
    acknowledgements: new Map(),
    ...partial,
  }
}

function sumCounts(daily: ReturnType<typeof computeDailyBlocks>, keys?: string[]) {
  return daily.days.reduce(
    (total, day) =>
      total +
      Object.entries(day.counts)
        .filter(([key]) => !keys || keys.includes(key))
        .reduce((s, [, n]) => s + n, 0),
    0,
  )
}

// Asia/Seoul(UTC+9, DST 없음) 기준 오늘 0시
function seoulStartOfDay(now: Date) {
  const key = new Intl.DateTimeFormat('en-CA', { timeZone: TZ }).format(now)
  return new Date(`${key}T00:00:00+09:00`)
}

describe('§0 용어 정의', () => {
  it('CACHE 차단은 같은 sha256의 엔진 분석 룰로 심각도를 정한다', () => {
    const ds = makeDataset({
      analyses: [
        makeAnalysis({ sha256: 'h1', createdAt: iso(3 * HOUR), rules: [{ ruleName: 'A', severity: 'LOW' }] }),
        makeAnalysis({
          sha256: 'h1',
          createdAt: iso(2 * HOUR),
          rules: [
            { ruleName: 'B', severity: 'MEDIUM' },
            { ruleName: 'C', severity: 'CRITICAL' },
          ],
        }),
        // 이벤트 이후의 분석은 쓰지 않는다
        makeAnalysis({ sha256: 'h1', createdAt: iso(10 * MINUTE), rules: [{ ruleName: 'D', severity: 'LOW' }] }),
        makeAnalysis({ sha256: 'h1', status: 'TIMEOUT', createdAt: iso(90 * MINUTE) }),
      ],
    })
    const cache = makeEvent({ sha256: 'h1', decisionSource: 'CACHE', createdAt: iso(HOUR) })
    expect(eventSeverity(ds, cache)).toBe('CRITICAL')

    // 이벤트 이전 SUCCESS 분석이 없으면 전체 중 가장 최근 것을 쓴다
    const early = makeEvent({ sha256: 'h1', decisionSource: 'CACHE', createdAt: iso(5 * HOUR) })
    expect(eventSeverity(ds, early)).toBe('LOW')
    expect(eventRules(ds, early)).toEqual({ topRuleName: 'D', extraRuleCount: 0 })

    // 차단이 아니면 null
    expect(eventSeverity(ds, makeEvent({ sha256: 'h1', decision: 'RELEASED', decisionSource: 'CACHE' }))).toBeNull()
  })

  it('블랙리스트 차단은 등록 심각도를 쓴다', () => {
    const ds = makeDataset({
      blacklist: new Map<string, Severity>([['bl', 'HIGH']]),
      analyses: [makeAnalysis({ sha256: 'bl', rules: [{ ruleName: 'A', severity: 'LOW' }] })],
    })
    const event = makeEvent({ sha256: 'bl', decisionSource: 'BLACKLIST' })
    expect(eventSeverity(ds, event)).toBe('HIGH')
    expect(eventRules(ds, event)).toEqual({ topRuleName: null, extraRuleCount: 0 })
    const item = toListItem(ds, event)
    expect(item.severity).toBe('HIGH')
    expect(item.topRuleName).toBeNull()
  })

  it('FAIL_CLOSE의 심각도는 null이다', () => {
    const ds = makeDataset({})
    const event = makeEvent({ decision: 'FAIL_CLOSE', decisionSource: null, sha256: null, filename: null })
    expect(isBlock(event)).toBe(true)
    expect(eventSeverity(ds, event)).toBeNull()
    expect(eventRules(ds, event)).toEqual({ topRuleName: null, extraRuleCount: 0 })
  })

  it('대표 룰은 최고 심각도, 같으면 이름순이고 나머지는 extraRuleCount다', () => {
    const rules = [
      { ruleName: 'Zeta', severity: 'HIGH' as const },
      { ruleName: 'Low_One', severity: 'LOW' as const },
      { ruleName: 'Alpha', severity: 'HIGH' as const },
    ]
    const ds = makeDataset({ analyses: [makeAnalysis({ sha256: 'h', rules })] })
    const event = makeEvent({ sha256: 'h' })
    expect(eventRules(ds, event)).toEqual({ topRuleName: 'Alpha', extraRuleCount: 2 })
    // 원본 rules 배열을 정렬하지 않는다
    expect(rules.map((r) => r.ruleName)).toEqual(['Zeta', 'Low_One', 'Alpha'])
  })

  it('목록 항목은 상세 필드를 빼고 계산한 확장 필드를 넣는다', () => {
    const ds = makeDataset({
      analyses: [makeAnalysis({ sha256: 'h', rules: [{ ruleName: 'A', severity: 'HIGH' }] })],
    })
    const blocked = makeEvent({ sha256: 'h', topRuleName: 'placeholder' })
    const released = makeEvent({ sha256: 'h', decision: 'RELEASED' })
    ds.acknowledgements.set(blocked.eventId, { acknowledgedBy: 'admin', acknowledgedAt: iso(0) })
    ds.acknowledgements.set(released.eventId, { acknowledgedBy: 'admin', acknowledgedAt: iso(0) })
    const item = toListItem(ds, blocked)
    expect(item).toMatchObject({ severity: 'HIGH', topRuleName: 'A', extraRuleCount: 0, acknowledged: true })
    expect(item).not.toHaveProperty('requestHost')
    expect(item).not.toHaveProperty('url')
    expect(toListItem(ds, released).acknowledged).toBe(false)
  })
})

describe('computeEngineStatus', () => {
  const scenarios: EngineScenario[] = ['HEALTHY', 'DOWN', 'IDLE']
  for (const engine of scenarios) {
    it(`${engine} 시나리오 데이터셋은 ${engine} 상태다`, () => {
      const ds = createDashboardDataset({ now: NOW, engine })
      const status = computeEngineStatus(ds, ds.now)
      expect(status.state).toBe(engine)
      expect(status.windowMinutes).toBe(10)
      if (engine !== 'DOWN') {
        expect(status.since).toBeNull()
        expect(status.failCloseSinceDown).toBe(0)
        expect(status.affectedAgentsSinceDown).toBe(0)
      }
    })
  }

  it('DOWN이면 since는 마지막 SUCCESS 이후 첫 실패 시각이고 그 뒤 FAIL_CLOSE를 센다', () => {
    const ds = createDashboardDataset({ now: NOW, engine: 'DOWN' })
    const status = computeEngineStatus(ds, ds.now)
    const lastSuccess = ds.analyses
      .filter((a) => a.status === 'SUCCESS')
      .map((a) => new Date(a.createdAt).getTime())
      .reduce((a, b) => Math.max(a, b))
    const firstFailureAfter = ds.analyses
      .filter((a) => ['TIMEOUT', 'CRASH', 'OOM'].includes(a.status) && new Date(a.createdAt).getTime() > lastSuccess)
      .map((a) => new Date(a.createdAt).getTime())
      .reduce((a, b) => Math.min(a, b))
    expect(status.since).toBe(new Date(firstFailureAfter).toISOString())
    expect(status.lastSuccessAt).toBe(new Date(lastSuccess).toISOString())
    expect(status.lastFailureAt).toBe(iso(MINUTE))
    expect(status.failCloseSinceDown).toBe(7)
    expect(status.affectedAgentsSinceDown).toBe(5)
  })

  it('UNSUPPORTED 분석만 최근 10분에 있으면 IDLE이다', () => {
    const ds = makeDataset({
      analyses: [
        makeAnalysis({ status: 'UNSUPPORTED', createdAt: iso(2 * MINUTE) }),
        makeAnalysis({ status: 'TIMEOUT', createdAt: iso(30 * MINUTE) }),
      ],
    })
    const status = computeEngineStatus(ds, NOW)
    expect(status.state).toBe('IDLE')
    expect(status.lastFailureAt).toBe(iso(30 * MINUTE))
    expect(status.lastSuccessAt).toBeNull()
  })
})

describe('M2 위젯 간 등식', () => {
  const scenarios: EngineScenario[] = ['HEALTHY', 'DOWN', 'IDLE']
  const periods: [string, (now: Date) => Date][] = [
    ['오늘', seoulStartOfDay],
    ['최근 7일', (now) => new Date(now.getTime() - 7 * DAY)],
    ['최근 30일', (now) => new Date(now.getTime() - 30 * DAY)],
  ]
  for (const engine of scenarios) {
    for (const [label, fromOf] of periods) {
      it(`${engine} · ${label}`, () => {
        const ds = createDashboardDataset({ now: NOW, engine })
        const to = ds.now
        const from = fromOf(to)
        const summary = computeSummary(ds, from, to)
        const bySource = computeDailyBlocks(ds, from, to, TZ, 'SOURCE')
        const byRule = computeDailyBlocks(ds, from, to, TZ, 'RULE')
        const topAgents = computeTopSources(ds, from, to, 'AGENT', null)

        // 1
        expect(sumCounts(bySource)).toBe(summary.blocked)
        expect(sumCounts(byRule)).toBe(summary.blocked)
        // 2
        const { total, engine: e, cache, blacklist } = summary.malicious
        expect(e + cache + blacklist).toBe(total)
        expect(sumCounts(bySource, ['ENGINE', 'CACHE', 'BLACKLIST'])).toBe(total)
        // 3
        expect(sumCounts(bySource, ['FAIL_CLOSE'])).toBe(summary.failClose)
        // 4
        expect(topAgents.reduce((s, row) => s + row.blockCount, 0)).toBe(summary.blocked)
        // 5
        expect(listUnacknowledged(ds)).toHaveLength(
          filterEvents(ds, { decisions: ['BLOCKED'], minSeverity: 'HIGH', acknowledged: false }).length,
        )
        // 6
        const status = computeEngineStatus(ds, ds.now)
        if (status.state === 'DOWN') {
          const since = new Date(status.since!).getTime()
          if (from.getTime() <= since && since < to.getTime()) {
            expect(status.failCloseSinceDown).toBeLessThanOrEqual(summary.failClose)
          }
        }
        // 데이터가 실제로 있어야 등식이 의미 있다(오늘 기간은 0일 수 있음)
        if (label !== '오늘') expect(summary.blocked).toBeGreaterThan(0)
      })
    }
  }
})

describe('computeSummary', () => {
  it('blockedPreviousPeriod는 바로 앞 같은 길이 기간을 센다', () => {
    const ds = makeDataset({
      events: [
        makeEvent({ createdAt: iso(1 * DAY) }), // 이번 기간
        makeEvent({ decision: 'FAIL_CLOSE', decisionSource: null, createdAt: iso(3 * DAY) }), // 앞 기간
        makeEvent({ createdAt: iso(4 * DAY - MINUTE) }), // 앞 기간
        makeEvent({ createdAt: iso(4 * DAY + MINUTE) }), // 범위 밖
        makeEvent({ decision: 'RELEASED', createdAt: iso(3 * DAY) }), // 차단 아님
      ],
    })
    const summary = computeSummary(ds, new Date(NOW.getTime() - 2 * DAY), NOW)
    expect(summary.blocked).toBe(1)
    expect(summary.blockedPreviousPeriod).toBe(2)
  })

  it('차단이 없는 기간이면 blockRate는 0이다', () => {
    const ds = createDashboardDataset({ now: NOW })
    const to = new Date(NOW.getTime() + 2 * HOUR)
    const from = new Date(to.getTime() - HOUR)
    const summary = computeSummary(ds, from, to)
    expect(summary.blocked).toBe(0)
    expect(summary.inspected).toBe(0)
    expect(summary.blockRate).toBe(0)
    expect(summary.period).toEqual({ from: from.toISOString(), to: to.toISOString() })
  })

  it('영향 PC와 등록 PC 수를 센다', () => {
    const ds = createDashboardDataset({ now: NOW })
    const summary = computeSummary(ds, new Date(NOW.getTime() - 14 * DAY), NOW)
    expect(summary.totalAgents).toBe(ds.agents.filter((a) => a.status !== 'REVOKED').length)
    expect(summary.affectedAgents).toBeGreaterThan(0)
    expect(summary.affectedAgents).toBeLessThan(summary.totalAgents)
    expect(summary.blockRate).toBeCloseTo(summary.blocked / summary.inspected)
  })
})

describe('computeThreats', () => {
  const twoRules = [
    { ruleName: 'Rule_A', severity: 'HIGH' as const },
    { ruleName: 'Rule_B', severity: 'LOW' as const },
  ]
  const ds = makeDataset({
    analyses: [
      makeAnalysis({ sha256: 'two', createdAt: iso(10 * HOUR), rules: twoRules }),
      makeAnalysis({ sha256: 'one', createdAt: iso(10 * HOUR), rules: [{ ruleName: 'Rule_C', severity: 'MEDIUM' }] }),
    ],
    blacklist: new Map<string, Severity>([
      ['bl1', 'MEDIUM'],
      ['bl2', 'CRITICAL'],
    ]),
    events: [
      makeEvent({ agentId: 'a1', sha256: 'two', createdAt: iso(5 * HOUR) }),
      makeEvent({ agentId: 'a2', sha256: 'two', decisionSource: 'CACHE', createdAt: iso(3 * HOUR) }),
      makeEvent({ agentId: 'a1', sha256: 'one', createdAt: iso(1 * HOUR) }),
      makeEvent({ agentId: 'a3', sha256: 'bl1', decisionSource: 'BLACKLIST', createdAt: iso(4 * HOUR) }),
      makeEvent({ agentId: 'a3', sha256: 'bl2', decisionSource: 'BLACKLIST', createdAt: iso(2 * HOUR) }),
      makeEvent({ decision: 'FAIL_CLOSE', decisionSource: null, createdAt: iso(30 * MINUTE) }),
      makeEvent({ sha256: 'two', createdAt: iso(2 * DAY) }), // 기간 밖
    ],
  })
  const from = new Date(NOW.getTime() - DAY)
  const rows = computeThreats(ds, from, NOW, 10)

  it('위협별 현황은 이벤트의 모든 룰 줄에 센다', () => {
    const a = rows.find((r) => r.ruleName === 'Rule_A')!
    const b = rows.find((r) => r.ruleName === 'Rule_B')!
    expect(a).toEqual({
      kind: 'RULE',
      ruleName: 'Rule_A',
      severity: 'HIGH',
      affectedAgents: 2,
      blockCount: 2,
      firstSeenAt: iso(5 * HOUR),
      lastSeenAt: iso(3 * HOUR),
    })
    expect(b.blockCount).toBe(2)
    expect(b.severity).toBe('LOW')
  })

  it('블랙리스트는 한 줄로 묶이고 ruleName이 null이다', () => {
    const blacklistRows = rows.filter((r) => r.kind === 'BLACKLIST')
    expect(blacklistRows).toEqual([
      {
        kind: 'BLACKLIST',
        ruleName: null,
        severity: 'CRITICAL',
        affectedAgents: 1,
        blockCount: 2,
        firstSeenAt: iso(4 * HOUR),
        lastSeenAt: iso(2 * HOUR),
      },
    ])
  })

  it('lastSeenAt 내림차순이다', () => {
    expect(rows.map((r) => r.ruleName)).toEqual(['Rule_C', null, 'Rule_A', 'Rule_B'])
    expect(computeThreats(ds, from, NOW, 2)).toHaveLength(2)
  })
})

describe('FAIL_CLOSE가 섞인 집계', () => {
  it('FAIL_CLOSE 이벤트가 섞여도 상위 PC와 일별 차단을 계산한다', () => {
    const ds = makeDataset({
      analyses: [makeAnalysis({ sha256: 'h', createdAt: iso(3 * DAY), rules: [{ ruleName: 'R', severity: 'LOW' }] })],
      events: [
        makeEvent({ agentId: 'a1', hostname: 'PC-1', sha256: 'h', requestHost: 'x.example.com', createdAt: iso(2 * DAY) }),
        makeEvent({
          agentId: 'a1',
          hostname: 'PC-1',
          decision: 'FAIL_CLOSE',
          decisionSource: null,
          sha256: null,
          filename: null,
          requestHost: '',
          createdAt: iso(1 * HOUR),
        }),
        makeEvent({
          agentId: 'a2',
          hostname: 'PC-2',
          decision: 'FAIL_CLOSE',
          decisionSource: null,
          sha256: null,
          filename: null,
          requestHost: '',
          createdAt: iso(2 * HOUR),
        }),
        makeEvent({ agentId: 'a3', decision: 'RELEASED', createdAt: iso(HOUR) }),
      ],
    })
    const from = new Date(NOW.getTime() - 3 * DAY)

    expect(computeTopSources(ds, from, NOW, 'AGENT', 5)).toEqual([
      { key: 'a1', label: 'PC-1', blockCount: 2 },
      { key: 'a2', label: 'PC-2', blockCount: 1 },
    ])
    expect(computeTopSources(ds, from, NOW, 'HOST', null)).toEqual([
      { key: '(알 수 없음)', label: '(알 수 없음)', blockCount: 2 },
      { key: 'x.example.com', label: 'x.example.com', blockCount: 1 },
    ])
    expect(computeTopSources(ds, from, NOW, 'AGENT', 1)).toHaveLength(1)

    const bySource = computeDailyBlocks(ds, from, NOW, TZ, 'SOURCE')
    expect(bySource.keys).toEqual(['ENGINE', 'CACHE', 'BLACKLIST', 'FAIL_CLOSE'])
    // 3일 전 12:00 KST ~ 오늘 12:00 KST → 4일
    expect(bySource.days.map((d) => d.date)).toEqual(['2026-11-05', '2026-11-06', '2026-11-07', '2026-11-08'])
    expect(bySource.days[1].counts).toEqual({ ENGINE: 1, CACHE: 0, BLACKLIST: 0, FAIL_CLOSE: 0 })
    expect(bySource.days[3].counts).toEqual({ ENGINE: 0, CACHE: 0, BLACKLIST: 0, FAIL_CLOSE: 2 })
    expect(bySource.days[0].counts).toEqual({ ENGINE: 0, CACHE: 0, BLACKLIST: 0, FAIL_CLOSE: 0 })

    const byRule = computeDailyBlocks(ds, from, NOW, TZ, 'RULE')
    expect(byRule.keys).toEqual(['R', 'BLACKLIST', 'OTHER', 'FAIL_CLOSE'])
    expect(byRule.days[3].counts).toEqual({ R: 0, BLACKLIST: 0, OTHER: 0, FAIL_CLOSE: 2 })
  })

  it('RULE 쌓기는 상위 5개 룰 뒤에 BLACKLIST·OTHER·FAIL_CLOSE를 둔다', () => {
    const ds = createDashboardDataset({ now: NOW })
    const daily = computeDailyBlocks(ds, new Date(NOW.getTime() - 14 * DAY), NOW, TZ, 'RULE')
    expect(daily.keys).toHaveLength(8)
    expect(daily.keys.slice(5)).toEqual(['BLACKLIST', 'OTHER', 'FAIL_CLOSE'])
    expect(sumCounts(daily, ['OTHER'])).toBeGreaterThan(0)
  })
})

describe('listUnacknowledged / filterEvents', () => {
  it('확인 안 한 HIGH 이상 차단을 최신순으로 준다', () => {
    const ds = createDashboardDataset({ now: NOW })
    const rows = listUnacknowledged(ds)
    expect(rows.length).toBeGreaterThan(0)
    const times = rows.map((r) => new Date(r.createdAt).getTime())
    expect([...times].sort((a, b) => b - a)).toEqual(times)
    for (const row of rows) {
      expect(['HIGH', 'CRITICAL']).toContain(row.severity)
      if (row.reasonType === 'BLACKLIST') expect(row.topRuleName).toBeNull()
      else expect(row.topRuleName).not.toBeNull()
    }
    expect(rows.some((r) => r.reasonType === 'BLACKLIST')).toBe(true)
    expect(listUnacknowledged(createDashboardDataset({ now: NOW, ackAll: true }))).toEqual([])
  })

  it('filterEvents는 결정·기간·심각도·확인 여부로 거르고 최신순이다', () => {
    const ds = createDashboardDataset({ now: NOW })
    const from = new Date(NOW.getTime() - 7 * DAY)
    const items = filterEvents(ds, { decisions: ['BLOCKED', 'FAIL_CLOSE'], from, to: NOW })
    expect(items.length).toBeGreaterThan(0)
    for (const item of items) {
      expect(isBlock(item)).toBe(true)
      expect(new Date(item.createdAt).getTime()).toBeGreaterThanOrEqual(from.getTime())
    }
    const times = items.map((i) => new Date(i.createdAt).getTime())
    expect([...times].sort((a, b) => b - a)).toEqual(times)

    const high = filterEvents(ds, { minSeverity: 'HIGH' })
    expect(high.every((i) => i.severity === 'HIGH' || i.severity === 'CRITICAL')).toBe(true)
    const agentId = ds.events[0].agentId
    expect(filterEvents(ds, { agentId }).every((i) => i.agentId === agentId)).toBe(true)
    expect(filterEvents(ds, {})).toHaveLength(ds.events.length)
  })
})
