import { isMockApiEnabled } from './config'

export async function enableMocking() {
  if (!import.meta.env.DEV || !isMockApiEnabled) {
    return
  }

  const { worker } = await import('./browser')

  await worker.start({
    onUnhandledFrame: 'bypass',
  })
}
