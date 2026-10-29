import { useCallback, useEffect, useRef, useState } from 'react'
import type { ChangeEvent } from 'react'
import { activateRuleset, getRulesetRules, getRulesets, updateYaraRuleEnabled, uploadRuleset } from '../lib/api/rulesets'
import type { RulesetItem, YaraRuleItem } from '../lib/api/rulesets'
import { disableConfirmMessage, rulesetErrorMessage, rulesetStatus, validateRulesetFile } from '../lib/rulesets'

// 버전 목록은 한 화면에서 고르는 용도라 넉넉한 크기로 한 번에 받는다.
const VERSION_PAGE_SIZE = 50

function formatDate(value: string | null) {
  return value ? new Date(value).toLocaleString('ko-KR') : '—'
}

export function RulesetVersionItem({ item, selected, onSelect }: {
  item: RulesetItem
  selected: boolean
  onSelect: (version: number) => void
}) {
  const status = rulesetStatus(item)
  return (
    <li>
      <button
        className={selected ? 'ruleset-version ruleset-version--selected' : 'ruleset-version'}
        type="button"
        aria-current={selected ? 'true' : undefined}
        onClick={() => onSelect(item.version)}
      >
        <span className="ruleset-version-head">
          <strong>v{item.version}</strong>
          <span className={item.isActive ? 'events-badge events-badge--released' : 'events-badge events-badge--pending'}>{status}</span>
        </span>
        <span className="ruleset-version-meta">룰 {item.ruleCount}개</span>
        <span className="ruleset-version-meta">{formatDate(item.createdAt)}</span>
      </button>
    </li>
  )
}

export function RulesetRuleRow({ rule, isBusy, onToggle }: {
  rule: YaraRuleItem
  isBusy: boolean
  onToggle: (rule: YaraRuleItem) => void
}) {
  return (
    <tr>
      <td className="event-long-value">{rule.ruleName}</td>
      <td>{rule.severity}</td>
      <td>
        <button
          className="ruleset-switch"
          type="button"
          role="switch"
          aria-checked={rule.enabled}
          aria-label={rule.ruleName + ' 사용'}
          disabled={isBusy}
          onClick={() => onToggle(rule)}
        />
      </td>
    </tr>
  )
}

export function RulesetsPage() {
  const [rulesets, setRulesets] = useState<RulesetItem[]>([])
  const [selected, setSelected] = useState<number | null>(null)
  const [rules, setRules] = useState<YaraRuleItem[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [isRulesLoading, setIsRulesLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  const [rulesError, setRulesError] = useState(false)
  const [actionError, setActionError] = useState('')
  const [isBusy, setIsBusy] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const fileInput = useRef<HTMLInputElement>(null)
  // 업로드 직후에는 새 버전을, 처음 로드할 때는 활성 버전을 고르기 위한 다음 선택값.
  const pendingSelect = useRef<number | 'active' | null>('active')

  useEffect(() => {
    let active = true
    getRulesets({ page: 0, size: VERSION_PAGE_SIZE })
      .then((result) => {
        if (!active) return
        setRulesets(result.content)
        setLoadError(false)
        const next = pendingSelect.current
        pendingSelect.current = null
        setSelected((current) => {
          if (typeof next === 'number') return next
          if (next === 'active') return result.content.find((item) => item.isActive)?.version ?? result.content[0]?.version ?? null
          return current !== null && result.content.some((item) => item.version === current) ? current : null
        })
      })
      .catch(() => { if (active) setLoadError(true) })
      .finally(() => { if (active) setIsLoading(false) })
    return () => { active = false }
  }, [refreshKey])

  useEffect(() => {
    if (selected === null) return
    let active = true
    getRulesetRules(selected)
      .then((result) => {
        if (!active) return
        setRules(result.content)
        setRulesError(false)
      })
      .catch(() => {
        if (!active) return
        setRules([])
        setRulesError(true)
      })
      .finally(() => { if (active) setIsRulesLoading(false) })
    return () => { active = false }
  }, [selected, refreshKey])

  const refresh = useCallback(() => {
    setIsLoading(true)
    setRefreshKey((key) => key + 1)
  }, [])

  function selectVersion(version: number) {
    if (version === selected) return
    setSelected(version)
    setRules([])
    setIsRulesLoading(true)
    setActionError('')
  }

  async function onFileChosen(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    // 같은 파일을 다시 고를 수 있도록 입력값을 비운다.
    event.target.value = ''
    if (!file) return
    const invalid = validateRulesetFile(file)
    if (invalid) {
      setActionError(invalid)
      return
    }
    setIsBusy(true)
    setActionError('')
    try {
      const created = await uploadRuleset(file)
      pendingSelect.current = created.version
      setRules([])
      setIsRulesLoading(true)
      refresh()
    } catch (cause) {
      setActionError(rulesetErrorMessage(cause))
    } finally {
      setIsBusy(false)
    }
  }

  async function activate(version: number) {
    if (!window.confirm('v' + version + '을 적용하면 기존 엔진 판정이 다시 검사됩니다. 계속할까요?')) return
    setIsBusy(true)
    setActionError('')
    try {
      await activateRuleset(version)
      refresh()
    } catch (cause) {
      setActionError(rulesetErrorMessage(cause))
    } finally {
      setIsBusy(false)
    }
  }

  async function toggleRule(rule: YaraRuleItem) {
    const current = rulesets.find((item) => item.version === selected)
    if (!current) return
    if (rule.enabled) {
      const message = disableConfirmMessage(rules, rule, current.isActive)
      if (message && !window.confirm(message)) return
    }
    setIsBusy(true)
    setActionError('')
    try {
      const updated = await updateYaraRuleEnabled(current.version, rule.ruleId, !rule.enabled)
      setRules((list) => list.map((item) => (item.ruleId === updated.ruleId ? updated : item)))
      // 꺼진 룰 수는 버전 목록에도 쓰이므로 목록만 조용히 다시 받는다.
      getRulesets({ page: 0, size: VERSION_PAGE_SIZE }).then((result) => setRulesets(result.content)).catch(() => {})
    } catch (cause) {
      setActionError(rulesetErrorMessage(cause))
    } finally {
      setIsBusy(false)
    }
  }

  const current = rulesets.find((item) => item.version === selected) ?? null

  return (
    <section className="page events-page ruleset-page">
      <div className="page-heading ruleset-heading">
        <div>
          <p className="eyebrow">YARA rulesets</p>
          <h1 className="whitelist-title">YARA 룰셋</h1>
        </div>
        <div>
          <button className="events-button events-button--primary" type="button" disabled={isBusy} onClick={() => fileInput.current?.click()}>.yar 업로드</button>
          <input ref={fileInput} type="file" accept=".yar" hidden aria-label=".yar 파일 선택" onChange={onFileChosen} />
        </div>
      </div>

      {actionError && <p className="events-filter-error" role="alert">{actionError}</p>}

      {isLoading ? (
        <p className="events-state" role="status">룰셋을 불러오는 중입니다.</p>
      ) : loadError ? (
        <div className="events-state" role="alert">
          <p>룰셋을 불러오지 못했습니다.</p>
          <button className="events-button" type="button" onClick={refresh}>다시 시도</button>
        </div>
      ) : !rulesets.length ? (
        <p className="events-state">업로드된 룰셋이 없습니다. .yar 파일을 올려 주세요.</p>
      ) : (
        <div className="ruleset-layout">
          <div className="events-panel">
            <div className="events-panel-heading"><h2>버전</h2></div>
            <ul className="ruleset-versions">
              {rulesets.map((item) => (
                <RulesetVersionItem key={item.version} item={item} selected={item.version === selected} onSelect={selectVersion} />
              ))}
            </ul>
          </div>

          <div className="events-panel">
            {current ? (
              <>
                <div className="events-panel-heading">
                  <h2>v{current.version} · 룰 {current.ruleCount}개</h2>
                  {current.isActive ? (
                    <span className="events-badge events-badge--released">적용 중</span>
                  ) : (
                    <button className="events-button events-button--primary" type="button" disabled={isBusy} onClick={() => activate(current.version)}>이 버전 활성화</button>
                  )}
                </div>
                {isRulesLoading ? (
                  <p className="events-state" role="status">룰을 불러오는 중입니다.</p>
                ) : rulesError ? (
                  <p className="events-state" role="alert">룰을 불러오지 못했습니다.</p>
                ) : !rules.length ? (
                  <p className="events-state">이 버전에는 룰이 없습니다.</p>
                ) : (
                  <div className="events-table-scroll">
                    <table className="events-table">
                      <thead><tr><th scope="col">룰 이름</th><th scope="col">심각도</th><th scope="col">사용</th></tr></thead>
                      <tbody>{rules.map((rule) => (
                        <RulesetRuleRow key={rule.ruleId} rule={rule} isBusy={isBusy} onToggle={toggleRule} />
                      ))}</tbody>
                    </table>
                  </div>
                )}
              </>
            ) : (
              <p className="events-state">왼쪽에서 버전을 선택해 주세요.</p>
            )}
          </div>
        </div>
      )}
    </section>
  )
}
