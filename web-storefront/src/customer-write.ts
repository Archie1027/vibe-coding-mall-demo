import { computed, onBeforeUnmount, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { createCustomerWriteAttempt, type CustomerWriteRequest, type WriteOutcome } from '@zhicai/shared/customer-data.ts'
import { ApiError } from '@zhicai/shared/api.ts'
import { api } from './session'

export function useCustomerWrite() {
  const router = useRouter(), route = useRoute()
  const revision = ref(0), message = ref('')
  const attempt = createCustomerWriteAttempt(request => api.request(request.path, { method: request.method, body: request.body, headers: { 'Idempotency-Key': request.key } }), () => revision.value++)
  const busy = computed(() => { revision.value; return attempt.busy })
  const uncertain = computed(() => { revision.value; return attempt.uncertain })
  const frozen = computed(() => busy.value || uncertain.value)
  function show(result: WriteOutcome) {
    if (result.kind === 'success') message.value = ''
    if (result.kind === 'unknown') message.value = '尚未确认这次操作的结果，请点击“重试原操作”确认。确认前暂不能进行其他修改。'
    else if (result.kind === 'refresh_failed') message.value = '操作已成功，但最新资料加载失败，请重新加载。'
    else if (result.kind === 'rejected') message.value = result.error instanceof Error ? result.error.message : '操作未完成，请重试'
    return result
  }
  async function finish(result: WriteOutcome) {
    show(result)
    // The API's immediate 401 navigation can meet the still-busy route guard.
    if ((result.kind === 'rejected' || result.kind === 'refresh_failed') && result.error instanceof ApiError && result.error.status === 401 && route.path !== '/login') await router.replace({ path: '/login', query: { redirect: route.fullPath } })
    return result
  }
  async function send(request: Omit<CustomerWriteRequest, 'key'>, success?: (value: unknown) => Promise<void>) { if (!frozen.value) message.value = ''; return finish(await attempt.send(request, success)) }
  async function retry() { return finish(await attempt.retry()) }
  function leave() {
    if (busy.value) return false
    if (!uncertain.value) return true
    if (!window.confirm('上次操作结果尚未确认。离开后请先重新查看资料，避免重复操作。确定离开？')) return false
    attempt.clear(); message.value = ''; return true
  }
  function beforeUnload(event: BeforeUnloadEvent) { if (frozen.value) { event.preventDefault(); event.returnValue = '' } }
  window.addEventListener('beforeunload', beforeUnload)
  onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
  onBeforeRouteLeave(leave); onBeforeRouteUpdate(leave)
  return { busy, uncertain, frozen, message, send, retry }
}
