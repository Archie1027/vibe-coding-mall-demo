import type { Router } from 'vue-router'
import type { Account, createApiClient } from './api.ts'
import { ApiError } from './api.ts'
import { notify } from './notifications.ts'

/** Revalidate server identity on protected navigation; browser state is not authorization. */
export function installSessionGuard(router: Router, api: ReturnType<typeof createApiClient>, account: { value: Account | null }, kind: Account['accountType'], loginPath: string, report = notify) {
  let pendingChecks = 0
  const login = (redirect: string) => ({ path: loginPath, query: { redirect } })
  router.beforeEach(async to => {
    if (!to.meta.requiresAuth) return true
    if (!api.hasSession()) return login(to.fullPath)
    pendingChecks++
    try {
      const current = await api.request<Account>('/me')
      if (!current.enabled || current.accountType !== kind) {
        api.clearSession(); account.value = null
        return login(to.fullPath)
      }
      account.value = current
      return true
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) return login(to.fullPath)
      report(error instanceof Error ? error.message : '暂时无法验证会话，请稍后重试')
      return false
    } finally { pendingChecks-- }
  })
  api.onUnauthorized(() => {
    account.value = null
    if (!pendingChecks && router.currentRoute.value.path !== loginPath) {
      void router.replace(login(router.currentRoute.value.fullPath))
    }
  })
}
