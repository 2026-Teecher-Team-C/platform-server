const ACCESS_TOKEN_KEY = 'teecher.console.accessToken'

// 브라우저 밖(Node 22 테스트 등)에는 sessionStorage가 없다. 그때는 토큰이 없는 것으로 본다.
function storage(): Storage | undefined {
  return typeof sessionStorage === 'undefined' ? undefined : sessionStorage
}

export function getAccessToken() {
  return storage()?.getItem(ACCESS_TOKEN_KEY) ?? null
}

export function setAccessToken(token: string) {
  storage()?.setItem(ACCESS_TOKEN_KEY, token)
}

export function clearAccessToken() {
  storage()?.removeItem(ACCESS_TOKEN_KEY)
}
