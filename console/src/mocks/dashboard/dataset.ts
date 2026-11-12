import type { AgentListItem, AgentStatus, OsPlatform } from '../../lib/api/agents'
import type { EngineState, Severity } from '../../lib/api/dashboard'
import type {
  DownloadDecision,
  DownloadDecisionSource,
  DownloadEventDetail,
} from '../../lib/api/events'
import { mockDownloadEvents } from '../data/events'
import { createRandom } from './random'

export type EngineScenario = EngineState

export type AnalysisRecord = {
  sha256: string
  status: 'SUCCESS' | 'TIMEOUT' | 'CRASH' | 'OOM' | 'UNSUPPORTED'
  engineVersion: string
  createdAt: string
  rules: { ruleName: string; severity: Severity }[]
}

// 확인 상태는 eventId로 찾는다.
export type Acknowledgement = { acknowledgedBy: string; acknowledgedAt: string }

export type DashboardDataset = {
  now: Date
  events: DownloadEventDetail[]
  analyses: AnalysisRecord[]
  blacklist: Map<string, Severity>
  agents: AgentListItem[]
  acknowledgements: Map<string, Acknowledgement>
}

type Rule = { ruleName: string; severity: Severity }

export const DASHBOARD_RULES: Rule[] = [
  { ruleName: 'Suspicious_PE_Packer', severity: 'HIGH' },
  { ruleName: 'Embedded_PowerShell_Loader', severity: 'HIGH' },
  { ruleName: 'Office_Macro_AutoOpen', severity: 'MEDIUM' },
  { ruleName: 'EICAR_Test_File', severity: 'CRITICAL' },
  { ruleName: 'Obfuscated_JS_Dropper', severity: 'MEDIUM' },
  { ruleName: 'Ransom_Note_Strings', severity: 'CRITICAL' },
  { ruleName: 'Packed_UPX_Generic', severity: 'LOW' },
]

const DEFAULT_SEED = 20261106

// ---- 시간 상수 (모두 now 기준 "얼마 전"으로 쓴다) ----
const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR
const PERIOD = 14 * DAY
// 일반 이벤트는 이 시간보다 오래된 것만 만든다. 엔진 시나리오가 최근 시각을 독점한다.
const QUIET_TAIL = 45 * MINUTE
// 이 기간 안의 HIGH 이상 차단만 미확인이다. 이보다 오래된 것은 확인됨.
const UNACK_WINDOW = 3 * DAY

// ---- 규모 상수 ----
const AGENT_COUNTS: [AgentStatus, number][] = [['ACTIVE', 38], ['INACTIVE', 2], ['REVOKED', 2]]
const NEVER_BLOCKED_AGENTS = 4 // 마지막 ACTIVE PC 몇 대는 차단이 없다
const BACKGROUND_EVENTS = 560
const ENGINE_BLOCKS = 14 // 이 중 RECENT_HIGH_ENGINE_BLOCKS건은 최근 3일의 HIGH 이상(미확인)
const RECENT_HIGH_ENGINE_BLOCKS = 3
const CACHE_BLOCKS = 6
const BLACKLIST_BLOCKS = 6 // 이 중 RECENT_HIGH_BLACKLIST_BLOCKS건은 최근 3일의 HIGH 이상(미확인)
const RECENT_HIGH_BLACKLIST_BLOCKS = 2
const BACKGROUND_FAIL_CLOSE = 4
const RELEASED_RATIO = 0.62 // 차단이 아닌 이벤트 중 RELEASED 비율. 나머지는 BYPASSED
const CLEAN_HASH_POOL = 40
const DOWN_FAIL_CLOSE_COUNT = 7
const DOWN_AFFECTED_AGENTS = 5

const SEVERITY_RANK: Record<Severity, number> = { LOW: 1, MEDIUM: 2, HIGH: 3, CRITICAL: 4 }
const isHigh = (severity: Severity) => SEVERITY_RANK[severity] >= SEVERITY_RANK.HIGH

// ---- 위협(해시) 정의: 룰 조합과 심각도 ----
const rule = (name: string): Rule => DASHBOARD_RULES.find((r) => r.ruleName === name)!
const THREAT_RULES: Rule[][] = [
  [rule('Suspicious_PE_Packer'), rule('Packed_UPX_Generic')], // 룰 2개
  [rule('Embedded_PowerShell_Loader')],
  [rule('Office_Macro_AutoOpen')],
  [rule('EICAR_Test_File')],
  [rule('Obfuscated_JS_Dropper')],
  [rule('Ransom_Note_Strings'), rule('Embedded_PowerShell_Loader')], // 룰 2개
  [rule('Packed_UPX_Generic')],
  [rule('Office_Macro_AutoOpen'), rule('Obfuscated_JS_Dropper')], // 룰 2개
]
const BLACKLIST_SEVERITIES: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM']

type Threat = { sha256: string; rules: Rule[]; severity: Severity }

function maxSeverity(rules: Rule[]): Severity {
  return rules.reduce<Severity>(
    (top, r) => (SEVERITY_RANK[r.severity] > SEVERITY_RANK[top] ? r.severity : top),
    'LOW',
  )
}

// ---- 파일/호스트 샘플 ----
const FILE_SAMPLES = [
  { stem: 'invoice', ext: 'exe', mime: 'application/x-msdownload' },
  { stem: 'setup', ext: 'msi', mime: 'application/x-msi' },
  { stem: 'report', ext: 'docm', mime: 'application/vnd.ms-word.document.macroEnabled.12' },
  { stem: 'update', ext: 'js', mime: 'text/javascript' },
  { stem: 'guide', ext: 'pdf', mime: 'application/pdf' },
  { stem: 'client', ext: 'dmg', mime: 'application/x-apple-diskimage' },
  { stem: 'archive', ext: 'zip', mime: 'application/zip' },
  { stem: 'sheet', ext: 'xlsx', mime: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' },
]
const REQUEST_HOSTS = [
  'downloads.example.com',
  'files.example.org',
  'cdn.vendor-sample.net',
  'malicious-cdn.example.com',
  'share.partner-sample.co.kr',
  'updates.tool-sample.io',
]
const DEPARTMENTS = ['DEV', 'QA', 'OPS', 'HR', 'FIN', 'SALES']
const OS_PLATFORMS: OsPlatform[] = ['WINDOWS', 'WINDOWS', 'WINDOWS', 'MACOS', 'LINUX']

const AGENT_ID_PREFIX = '10000000-0000-4000-8000-'
const EVENT_ID_PREFIX = '30000000-0000-4000-8000-'
// 기존 mock(…0001~0004)과 겹치지 않게 100부터 센다.
const ID_START = 100
const formatId = (prefix: string, n: number) => prefix + String(n).padStart(12, '0')

// 이벤트 한 건의 초안. 시각은 now에서 뺄 오프셋(ms)이라 now가 바뀌어도 모양이 같다.
type EventDraft = {
  offset: number
  agent: AgentListItem
  decision: DownloadDecision
  source: DownloadDecisionSource | null
  sha256: string | null
  hasFilename: boolean
  // 이 이벤트와 같은 시각에 SUCCESS 분석을 남긴다(ENGINE 판정).
  analysisRules: Rule[] | null
}

export function createDashboardDataset(options: {
  now: Date
  seed?: number
  engine?: EngineScenario
  ackAll?: boolean
}): DashboardDataset {
  const { now, seed = DEFAULT_SEED, engine = 'HEALTHY', ackAll = false } = options
  const random = createRandom(seed)
  const int = (min: number, max: number) => min + Math.floor(random() * (max - min + 1))
  const pick = <T>(items: readonly T[]): T => items[Math.floor(random() * items.length)]
  const hex = () => Array.from({ length: 64 }, () => Math.floor(random() * 16).toString(16)).join('')
  const iso = (offset: number) => new Date(now.getTime() - offset).toISOString()
  // 일반 이벤트가 쓸 수 있는 오프셋 범위에서 고른다.
  const offsetBetween = (min: number, max: number) => min + Math.floor(random() * (max - min))
  const anyOffset = () => offsetBetween(QUIET_TAIL, PERIOD)
  // 미확인 창(최근 3일)보다 확실히 오래된 오프셋
  const oldOffset = () => offsetBetween(UNACK_WINDOW + HOUR, PERIOD)
  const recentOffset = () => offsetBetween(QUIET_TAIL, UNACK_WINDOW - HOUR)
  const isRecent = (offset: number) => offset <= UNACK_WINDOW

  // ---- 에이전트 ----
  const statuses = AGENT_COUNTS.flatMap(([status, n]) => Array<AgentStatus>(n).fill(status))
  const agents: AgentListItem[] = statuses.map((status, i) => ({
    agentId: formatId(AGENT_ID_PREFIX, ID_START + i),
    hostname: `${DEPARTMENTS[i % DEPARTMENTS.length]}-${String(Math.floor(i / DEPARTMENTS.length) + 1).padStart(3, '0')}`,
    osPlatform: OS_PLATFORMS[i % OS_PLATFORMS.length],
    agentVersion: i % 7 === 0 ? '1.3.9' : '1.4.2',
    status,
    lastHeartbeatAt:
      status === 'ACTIVE'
        ? iso(int(1, 4) * MINUTE)
        : status === 'INACTIVE'
          ? iso(10 * DAY)
          : null,
  }))
  const activeAgents = agents.filter((a) => a.status === 'ACTIVE')
  const blockableAgents = activeAgents.slice(0, activeAgents.length - NEVER_BLOCKED_AGENTS)

  // ---- 위협/블랙리스트 해시 ----
  const threats: Threat[] = THREAT_RULES.map((rules) => ({ sha256: hex(), rules, severity: maxSeverity(rules) }))
  const lowThreats = threats.filter((t) => !isHigh(t.severity))
  const blacklistHashes = BLACKLIST_SEVERITIES.map((severity) => ({ sha256: hex(), severity }))
  const blacklist = new Map(blacklistHashes.map((b) => [b.sha256, b.severity]))
  const cleanHashes = Array.from({ length: CLEAN_HASH_POOL }, hex)

  // 최근 3일 안에서는 HIGH 이상 위협을 쓰지 않는다(강제로 넣은 미확인 건만 예외).
  const threatFor = (index: number, offset: number) =>
    isRecent(offset) ? lowThreats[index % lowThreats.length] : threats[index % threats.length]
  const blacklistFor = (index: number, offset: number) => {
    const candidate = blacklistHashes[index % blacklistHashes.length]
    return isRecent(offset) && isHigh(candidate.severity) ? blacklistHashes[2] : candidate
  }

  const drafts: EventDraft[] = []
  const addDraft = (draft: Omit<EventDraft, 'agent' | 'hasFilename' | 'analysisRules'> &
    Partial<Pick<EventDraft, 'agent' | 'hasFilename' | 'analysisRules'>>) =>
    drafts.push({
      agent: pick(blockableAgents),
      hasFilename: true,
      analysisRules: null,
      ...draft,
    })

  // ---- 차단: ENGINE ----
  type EngineBlock = { offset: number; sha256: string; severity: Severity; forced: boolean }
  const engineBlocks: EngineBlock[] = []
  const addEngineBlock = (threat: Threat, offset: number, forced: boolean) => {
    engineBlocks.push({ offset, sha256: threat.sha256, severity: threat.severity, forced })
    addDraft({
      offset,
      decision: 'BLOCKED',
      source: 'ENGINE',
      sha256: threat.sha256,
      analysisRules: threat.rules,
    })
  }
  const highThreats = threats.filter((t) => isHigh(t.severity))
  for (let i = 0; i < RECENT_HIGH_ENGINE_BLOCKS; i += 1) {
    addEngineBlock(highThreats[i % highThreats.length], recentOffset(), true)
  }
  for (let i = 0; i < ENGINE_BLOCKS - RECENT_HIGH_ENGINE_BLOCKS; i += 1) {
    // 앞 N건은 모든 위협(=모든 룰)이 한 번씩 14일 안에 쓰이도록 오래된 쪽에 둔다.
    const offset = i < threats.length ? oldOffset() : anyOffset()
    addEngineBlock(threatFor(i, offset), offset, false)
  }

  // ---- 차단: CACHE (앞선 ENGINE 차단의 sha256 재사용) ----
  const cacheSources = engineBlocks.filter((b) => !b.forced)
  for (let i = 0; i < CACHE_BLOCKS; i += 1) {
    const source = cacheSources[i % cacheSources.length]
    // HIGH 이상은 3일보다 오래된 시점에만 재사용한다.
    const youngest = isHigh(source.severity) ? UNACK_WINDOW + 1 : QUIET_TAIL
    const offset = youngest + Math.floor(random() * (source.offset - youngest))
    addDraft({ offset, decision: 'BLOCKED', source: 'CACHE', sha256: source.sha256 })
  }

  // ---- 차단: BLACKLIST ----
  for (let i = 0; i < RECENT_HIGH_BLACKLIST_BLOCKS; i += 1) {
    addDraft({
      offset: recentOffset(),
      decision: 'BLOCKED',
      source: 'BLACKLIST',
      sha256: blacklistHashes[i].sha256,
    })
  }
  for (let i = 0; i < BLACKLIST_BLOCKS - RECENT_HIGH_BLACKLIST_BLOCKS; i += 1) {
    // 앞 2건은 HIGH 이상 해시를 오래된 쪽에, 나머지는 시점 무관.
    const offset = i < 2 ? oldOffset() : anyOffset()
    addDraft({ offset, decision: 'BLOCKED', source: 'BLACKLIST', sha256: blacklistFor(i, offset).sha256 })
  }

  // ---- 차단: FAIL_CLOSE (해시 계산 전 장애 차단) ----
  for (let i = 0; i < BACKGROUND_FAIL_CLOSE; i += 1) {
    addDraft({
      offset: anyOffset(),
      decision: 'FAIL_CLOSE',
      source: null,
      sha256: null,
      hasFilename: i % 2 === 0, // 절반은 파일명도 없다
    })
  }

  // ---- 일반 트래픽: RELEASED / BYPASSED ----
  const blockCount = drafts.length
  for (let i = 0; i < BACKGROUND_EVENTS - blockCount; i += 1) {
    const agent = pick(activeAgents)
    const offset = anyOffset()
    if (random() >= RELEASED_RATIO) {
      addDraft({ offset, agent, decision: 'BYPASSED', source: 'POLICY', sha256: null })
      continue
    }
    const sourceRoll = random()
    const sha256 = pick(cleanHashes)
    if (sourceRoll < 0.35) {
      addDraft({ offset, agent, decision: 'RELEASED', source: 'ENGINE', sha256, analysisRules: [] })
    } else if (sourceRoll < 0.6) {
      addDraft({ offset, agent, decision: 'RELEASED', source: 'CACHE', sha256 })
    } else {
      addDraft({ offset, agent, decision: 'RELEASED', source: 'WHITELIST', sha256 })
    }
  }

  // ---- 엔진 시나리오 ----
  const extraAnalyses: AnalysisRecord[] = []
  const failure = (offset: number): AnalysisRecord => ({
    sha256: hex(),
    status: 'TIMEOUT',
    engineVersion: 'unavailable',
    createdAt: iso(offset),
    rules: [],
  })
  const addCleanScan = (offset: number) =>
    addDraft({
      offset,
      agent: pick(activeAgents),
      decision: 'RELEASED',
      source: 'ENGINE',
      sha256: pick(cleanHashes),
      analysisRules: [],
    })

  if (engine === 'HEALTHY') {
    addCleanScan(2 * MINUTE)
    extraAnalyses.push(failure(90 * MINUTE)) // 이미 회복된 장애
  } else if (engine === 'DOWN') {
    addCleanScan(40 * MINUTE)
    for (const minutesAgo of [18, 15, 12, 9, 6, 3, 1]) extraAnalyses.push(failure(minutesAgo * MINUTE))
    const affected = blockableAgents.slice(0, DOWN_AFFECTED_AGENTS)
    for (let i = 0; i < DOWN_FAIL_CLOSE_COUNT; i += 1) {
      addDraft({
        offset: (17 - i * 2) * MINUTE, // 17, 15, ... 5분 전
        agent: affected[i % affected.length],
        decision: 'FAIL_CLOSE',
        source: null,
        sha256: null,
        hasFilename: i % 2 === 0,
      })
    }
  } else {
    addCleanScan(35 * MINUTE)
  }

  // ---- 초안 -> 이벤트/분석 ----
  const ordered = [...drafts].sort((a, b) => a.offset - b.offset) // 최신(작은 오프셋)부터
  const analyses: AnalysisRecord[] = [...extraAnalyses]
  const events: DownloadEventDetail[] = ordered.map((draft, index) => {
    const createdAt = iso(draft.offset)
    const file = pick(FILE_SAMPLES)
    const named = draft.hasFilename
    const filename = named ? `${file.stem}_${int(1000, 9999)}.${file.ext}` : null
    const host = pick(REQUEST_HOSTS)
    const holdDurationMs = draft.decision === 'FAIL_CLOSE' ? null : int(30, 2400)
    const inspected = draft.source === 'ENGINE'
    if (draft.analysisRules && draft.sha256) {
      analyses.push({
        sha256: draft.sha256,
        status: 'SUCCESS',
        engineVersion: 'yara-4.5.2',
        createdAt, // ENGINE 판정과 같은 시각
        rules: draft.analysisRules,
      })
    }
    return {
      eventId: formatId(EVENT_ID_PREFIX, ID_START + index),
      agentId: draft.agent.agentId,
      hostname: draft.agent.hostname,
      sha256: draft.sha256,
      filename,
      mimeType: named ? file.mime : null,
      fileSize: named ? int(20_000, 8_000_000) : null,
      decision: draft.decision,
      decisionSource: draft.source,
      cacheHit: draft.source === 'CACHE',
      holdDurationMs,
      createdAt,
      // 아래 4개는 자리표시자다. 실제 값은 aggregate(Task 3)가 analyses·blacklist·acknowledgements로 계산한다.
      severity: null,
      topRuleName: null,
      extraRuleCount: 0,
      acknowledged: false,
      requestHost: host,
      url: `https://${host}/${filename ?? 'download'}`,
      bytesUploaded: inspected ? int(20_000, 8_000_000) : 0,
      heldAt: holdDurationMs === null ? null : iso(draft.offset + holdDurationMs),
      decidedAt: createdAt,
      similarity: null,
    }
  })
  analyses.sort((a, b) => b.createdAt.localeCompare(a.createdAt))

  // ---- 확인 상태 ----
  const severityBySha = new Map<string, Severity>(blacklist)
  for (const threat of threats) severityBySha.set(threat.sha256, threat.severity)
  const acknowledgements = new Map<string, Acknowledgement>()
  const nowMs = now.getTime()
  const allEvents = [...events, ...mockDownloadEvents]
  for (const event of allEvents) {
    const createdMs = new Date(event.createdAt).getTime()
    const severity = event.decision === 'BLOCKED' && event.sha256 ? severityBySha.get(event.sha256) : undefined
    const acknowledge =
      (event.decision === 'BLOCKED' || event.decision === 'FAIL_CLOSE') &&
      (ackAll || (severity !== undefined && isHigh(severity) && createdMs < nowMs - UNACK_WINDOW))
    if (acknowledge) {
      acknowledgements.set(event.eventId, {
        acknowledgedBy: 'admin',
        acknowledgedAt: new Date(Math.min(createdMs + 2 * HOUR, nowMs)).toISOString(),
      })
    }
  }

  return {
    now,
    events: allEvents,
    analyses,
    blacklist,
    agents,
    acknowledgements,
  }
}
