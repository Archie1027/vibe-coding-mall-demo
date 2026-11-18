import { computed, onMounted, onUnmounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { api } from './session'
import { ApiError } from '@zhicai/shared/api.ts'
import { createWriteAttempt } from '@zhicai/shared/write-attempt.ts'

// Shared by the three product dialogs so unknown outcomes always retain the original request.
export function useCatalogWrite() {
  const busy = ref(false), uncertain = ref(false), conflict = ref(false), error = ref('')
  const frozen = computed(() => busy.value || uncertain.value || conflict.value)
  const attempt = createWriteAttempt()
  function canLeave() {
    if (busy.value) return false
    return !uncertain.value || window.confirm('上次提交结果尚未确认。离开后重新提交可能产生重复操作，确认仍要离开？')
  }
  function beforeUnload(event: BeforeUnloadEvent) { if (busy.value || uncertain.value) { event.preventDefault(); event.returnValue = '' } }
  onMounted(() => window.addEventListener('beforeunload', beforeUnload))
  onUnmounted(() => window.removeEventListener('beforeunload', beforeUnload))
  onBeforeRouteLeave(canLeave)
  async function submit<T>(method: string, path: string, body: object, saved: (result: T) => void) {
    if (busy.value || conflict.value) return
    const serialized = JSON.stringify(body), key = attempt.prepare(method, path, serialized)
    busy.value = true; error.value = ''
    try {
      const result = await api.request<T>(path, { method, body: serialized, headers: { 'Idempotency-Key': key } })
      uncertain.value = false; attempt.clear(); saved(result)
    } catch (reason) {
      uncertain.value = !(reason instanceof ApiError) || reason.status === 0 || reason.status >= 500 || reason.code === 'REQUEST_IN_PROGRESS' || reason.code === 'INVALID_RESPONSE'
      conflict.value = reason instanceof ApiError && reason.code === 'VERSION_CONFLICT'
      error.value = conflict.value ? '数据已被其他操作更新。请关闭窗口并重新加载后编辑。' : reason instanceof Error ? reason.message : '保存失败'
      if (!uncertain.value) attempt.clear()
    } finally { busy.value = false }
  }
  return { busy, uncertain, conflict, frozen, error, canLeave, submit }
}
