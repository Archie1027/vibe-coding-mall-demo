<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { api } from '../session'
import { ApiError } from '@zhicai/shared/api.ts'
import type { Brand } from '@zhicai/shared/catalog-api.ts'
import { createWriteAttempt } from '@zhicai/shared/write-attempt.ts'

const props = defineProps<{ brand: Brand | null }>()
const emit = defineEmits<{ close: []; saved: [brand: Brand] }>()
const dialog = ref<HTMLDialogElement | null>(null)
const name = ref(props.brand?.name ?? ''), enabled = ref(props.brand?.enabled ?? true)
const busy = ref(false), error = ref(''), uncertain = ref(false), conflict = ref(false)
const attempt = createWriteAttempt()
watch([name, enabled], () => { if (!uncertain.value && !conflict.value) error.value = '' })
function canLeave() {
  if (busy.value) return false
  return !uncertain.value || window.confirm('上次保存结果尚未确认。离开后重新创建可能产生重复品牌，确认仍要离开？')
}
function close() { if (canLeave()) dialog.value?.close() }
function cancel(event: Event) { event.preventDefault(); close() }
function beforeUnload(event: BeforeUnloadEvent) { if (busy.value || uncertain.value) { event.preventDefault(); event.returnValue = '' } }
onMounted(() => { dialog.value?.showModal(); window.addEventListener('beforeunload', beforeUnload) })
onUnmounted(() => window.removeEventListener('beforeunload', beforeUnload))
onBeforeRouteLeave(canLeave)
async function save() {
  if (busy.value || conflict.value) return
  if (!name.value.trim() || [...name.value].length > 120) { error.value = '品牌名称需为1–120个字符'; return }
  const path = props.brand ? `/admin/brands/${props.brand.id}` : '/admin/brands'
  const method = props.brand ? 'PUT' : 'POST'
  const body = JSON.stringify({ name: name.value, enabled: enabled.value, ...(props.brand ? { version: props.brand.version } : {}) })
  const key = attempt.prepare(method, path, body)
  busy.value = true; error.value = ''
  try {
    const result = await api.request<Brand>(path, { method, body, headers: { 'Idempotency-Key': key } })
    uncertain.value = false; attempt.clear(); emit('saved', result)
  } catch (reason) {
    uncertain.value = !(reason instanceof ApiError) || reason.status === 0 || reason.status >= 500 || reason.code === 'REQUEST_IN_PROGRESS' || reason.code === 'INVALID_RESPONSE'
    conflict.value = reason instanceof ApiError && reason.code === 'VERSION_CONFLICT'
    error.value = conflict.value ? '品牌已被其他操作更新。请关闭窗口并重新加载列表后编辑。' : reason instanceof Error ? reason.message : '保存失败'
    if (!uncertain.value) attempt.clear()
  } finally { busy.value = false }
}
</script>

<template>
  <dialog ref="dialog" class="brand-editor admin-card" :aria-label="brand ? '编辑品牌' : '新增品牌'" @cancel="cancel" @close="emit('close')">
    <form @submit.prevent="save">
      <h2>{{ brand ? '编辑品牌' : '新增品牌' }}</h2>
      <p v-if="brand">品牌 ID {{ brand.id }} · 当前版本 {{ brand.version }}</p>
      <label for="brand-name">品牌名称</label>
      <input id="brand-name" v-model="name" autofocus required :disabled="busy || uncertain || conflict" autocomplete="off">
      <label class="brand-enabled"><input v-model="enabled" type="checkbox" :disabled="busy || uncertain || conflict">启用品牌</label>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="uncertain" class="brand-pending" role="status">上次提交结果尚未确认，请保持原内容重试。重试会使用相同请求编号；关闭或刷新可能丢失该编号。</p>
      <div class="brand-actions"><button type="button" class="ghost-btn" :disabled="busy" @click="close">关闭</button><button class="admin-primary" :disabled="busy || conflict">{{ busy ? '正在保存…' : uncertain ? '确认原请求结果' : '保存品牌' }}</button></div>
    </form>
  </dialog>
</template>

<style scoped>
.brand-editor{width:min(520px,90vw);max-height:85svh;overflow:auto;margin:auto;padding:28px;box-sizing:border-box;color:#30485e;border:1px solid #dbe4ed;border-radius:12px}.brand-editor::backdrop{background:rgba(15,35,50,.5)}.brand-editor h2{margin-top:0}.brand-editor form>label{display:block;margin:22px 0 10px}.brand-editor input:not([type=checkbox]){box-sizing:border-box;width:100%;padding:13px;border:1px solid #bbcbd7;border-radius:6px;background:white;color:#263c50;font:inherit}.brand-enabled{display:flex!important;align-items:center;gap:10px}.brand-enabled input{width:20px;height:20px}.brand-actions{display:flex;gap:12px;justify-content:flex-end;margin-top:28px}.brand-pending{line-height:1.7;color:#8a4c00}.brand-editor button:disabled{opacity:.5;cursor:default}.brand-editor input:focus-visible,.brand-editor button:focus-visible{outline:3px solid #216f8f;outline-offset:3px}
</style>
