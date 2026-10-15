import { useEffect, useState } from 'react'

type PollingState<T> = {
  data: T | null
  error: unknown
  lastUpdatedAt: Date | null
}

// 응답이 온 뒤에 다음 요청을 예약하므로 느린 서버에서도 요청이 겹치지 않는다.
// 응답 없이 매달린 요청은 timeoutMs 뒤 실패로 처리해 폴링이 멈추지 않게 한다.
// 실패해도 마지막으로 받은 data는 유지한다.
export function usePolling<T>(
  load: (signal: AbortSignal) => Promise<T>,
  intervalMs: number,
  timeoutMs: number,
): PollingState<T> {
  const [state, setState] = useState<PollingState<T>>({
    data: null,
    error: null,
    lastUpdatedAt: null,
  })

  useEffect(() => {
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const unmount = new AbortController()

    async function tick() {
      try {
        const data = await load(AbortSignal.any([unmount.signal, AbortSignal.timeout(timeoutMs)]))
        if (!cancelled) {
          setState({ data, error: null, lastUpdatedAt: new Date() })
        }
      } catch (error) {
        if (!cancelled) {
          setState((previous) => ({ ...previous, error }))
        }
      }

      if (!cancelled) {
        timer = setTimeout(tick, intervalMs)
      }
    }

    void tick()

    return () => {
      cancelled = true
      clearTimeout(timer)
      unmount.abort()
    }
  }, [load, intervalMs, timeoutMs])

  return state
}
