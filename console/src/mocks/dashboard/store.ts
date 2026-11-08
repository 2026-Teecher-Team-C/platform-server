import { createDashboardDataset, type DashboardDataset, type EngineScenario } from './dataset'

let dataset: DashboardDataset | null = null

const ENGINE_SCENARIOS: readonly EngineScenario[] = ['HEALTHY', 'DOWN', 'IDLE']

// URL 쿼리(?mockEngine=HEALTHY|DOWN|IDLE, ?mockAck=all)로 시나리오를 고른다.
function scenarioFromLocation(): { engine: EngineScenario; ackAll: boolean } {
  const search = globalThis.location?.search ?? ''
  const params = new URLSearchParams(search)
  const engine = ENGINE_SCENARIOS.find((s) => s === params.get('mockEngine')) ?? 'HEALTHY'
  return { engine, ackAll: params.get('mockAck') === 'all' }
}

export function getDashboardDataset(): DashboardDataset {
  if (!dataset) dataset = createDashboardDataset({ now: new Date(), ...scenarioFromLocation() })
  return dataset
}

// 테스트용. 옵션이 없으면 다음 getDashboardDataset()에서 다시 만든다.
export function resetDashboardDataset(options?: Parameters<typeof createDashboardDataset>[0]): void {
  dataset = options ? createDashboardDataset(options) : null
}
