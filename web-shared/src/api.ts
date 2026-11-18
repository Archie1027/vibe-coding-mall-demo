export interface Account {
  id: string; username: string; displayName: string; accountType: 'CUSTOMER' | 'STAFF'
  permissions: string[]; warehouseIds: string[]; roleIds: string[]; enabled: boolean; version: number
}
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly traceId: string
  constructor(status: number, code: string, message: string, traceId = '') {
    super(message); this.name = 'ApiError'; this.status = status; this.code = code; this.traceId = traceId
  }
}
type Fetch = (url: string, init?: RequestInit) => Promise<Response>

/** A separate instance per app; tokens have no persistent browser storage. */
export function createApiClient(accountType: Account['accountType'], fetcher: Fetch = globalThis.fetch.bind(globalThis)) {
  let accessToken: string | undefined
  let onUnauthorized: (() => void) | undefined
  async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
    const headers = new Headers(options.headers)
    headers.set('Accept', 'application/json')
    if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
    let response: Response
    try { response = await fetcher(`/api/v1${path}`, { ...options, headers, credentials: 'omit' }) }
    catch { throw new ApiError(0, 'NETWORK_ERROR', '无法连接服务，请检查网络或稍后重试') }
    if (response.status === 401) { accessToken = undefined; onUnauthorized?.() }
    let result: { code?: string; message?: string; traceId?: string; data?: T }
    try { result = await response.json() }
    catch { throw new ApiError(response.status, 'INVALID_RESPONSE', '服务返回了无法读取的响应，请稍后重试') }
    if (!response.ok || result.code !== 'OK') {
      throw new ApiError(response.status, result.code ?? 'REQUEST_FAILED', result.message ?? '操作未完成，请重试', result.traceId)
    }
    return result.data as T
  }
  return {
    request,
    hasSession: () => Boolean(accessToken),
    onUnauthorized: (listener: () => void) => { onUnauthorized = listener },
    async login(username: string, password: string) {
      accessToken = undefined
      const token = await request<{ accessToken: string; tokenType: string; expiresAt: string }>(`/auth/${accountType === 'STAFF' ? 'staff' : 'customer'}/login`, {
        method: 'POST', body: JSON.stringify({ username, password }),
      })
      if (!token.accessToken || token.tokenType !== 'Bearer' || !Number.isFinite(Date.parse(token.expiresAt))) {
        throw new ApiError(502, 'INVALID_RESPONSE', '登录响应无效，请重试')
      }
      accessToken = token.accessToken
    },
    async logout() {
      try { if (accessToken) await request('/auth/logout', { method: 'POST' }) }
      finally { accessToken = undefined }
    },
    clearSession: () => { accessToken = undefined },
  }
}
