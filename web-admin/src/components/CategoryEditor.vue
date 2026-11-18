<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { api } from '../session'
import { ApiError } from '@zhicai/shared/api.ts'
import type { Category } from '@zhicai/shared/catalog-api.ts'
import { categoryParentCandidates } from '@zhicai/shared/category-tree.ts'
import { createWriteAttempt } from '@zhicai/shared/write-attempt.ts'

const props = defineProps<{ category: Category | null; categories: Category[] }>()
const emit = defineEmits<{ close: []; saved: [category: Category] }>()
const dialog = ref<HTMLDialogElement | null>(null)
const name = ref(props.category?.name ?? ''), parent = ref(props.category?.parentId ?? ''), sort = ref(props.category?.sort ?? 0), enabled = ref(props.category?.enabled ?? true)
const parents = computed(() => categoryParentCandidates(props.categories, props.category?.id))
const busy = ref(false), error = ref(''), uncertain = ref(false), conflict = ref(false)
const attempt = createWriteAttempt()
watch([name, parent, sort, enabled], () => { if (!uncertain.value && !conflict.value) error.value = '' })
function canLeave() { return !busy.value && (!uncertain.value || window.confirm('上次保存结果尚未确认。离开后重新创建可能产生重复分类，确认仍要离开？')) }
function close() { if (canLeave()) dialog.value?.close() }
function cancel(event: Event) { event.preventDefault(); close() }
function beforeUnload(event: BeforeUnloadEvent) { if (busy.value || uncertain.value) { event.preventDefault(); event.returnValue = '' } }
onMounted(() => { dialog.value?.showModal(); window.addEventListener('beforeunload', beforeUnload) })
onUnmounted(() => window.removeEventListener('beforeunload', beforeUnload))
onBeforeRouteLeave(canLeave)
async function save() {
  if (busy.value || conflict.value) return
  if (!name.value.trim() || [...name.value].length > 120) { error.value = '分类名称需为1–120个字符'; return }
  if (!Number.isInteger(sort.value) || sort.value < 0 || sort.value > 2147483647) { error.value = '排序需为0–2147483647的整数'; return }
  const path = props.category ? `/admin/categories/${props.category.id}` : '/admin/categories'
  const method = props.category ? 'PUT' : 'POST'
  const body = JSON.stringify({ name: name.value, parentId: parent.value || null, sort: sort.value, enabled: enabled.value, ...(props.category ? { version: props.category.version } : {}) })
  const key = attempt.prepare(method, path, body)
  busy.value = true; error.value = ''
  try {
    const result = await api.request<Category>(path, { method, body, headers: { 'Idempotency-Key': key } })
    uncertain.value = false; attempt.clear(); emit('saved', result)
  } catch (reason) {
    uncertain.value = !(reason instanceof ApiError) || reason.status === 0 || reason.status >= 500 || reason.code === 'REQUEST_IN_PROGRESS' || reason.code === 'INVALID_RESPONSE'
    conflict.value = reason instanceof ApiError && reason.code === 'VERSION_CONFLICT'
    error.value = conflict.value ? '分类已被其他操作更新。请关闭窗口并重新加载列表后编辑。' : reason instanceof Error ? reason.message : '保存失败'
    if (!uncertain.value) attempt.clear()
  } finally { busy.value = false }
}
</script>

<template>
  <dialog ref="dialog" class="category-editor admin-card" :aria-label="category ? '编辑分类' : '新增分类'" @cancel="cancel" @close="emit('close')">
    <form @submit.prevent="save">
      <h2>{{ category ? '编辑分类' : '新增分类' }}</h2><p v-if="category">分类 ID {{ category.id }} · 当前版本 {{ category.version }}</p>
      <label for="category-name">分类名称</label><input id="category-name" v-model="name" autofocus required :disabled="busy || uncertain || conflict" autocomplete="off">
      <label for="category-parent">父级分类</label><select id="category-parent" v-model="parent" :disabled="busy || uncertain || conflict"><option value="">无父级（一级分类）</option><option v-for="item in parents" :key="item.id" :value="item.id">{{ item.name }} · ID {{ item.id }}{{ item.enabled ? '' : '（停用）' }}</option></select>
      <p class="category-help">分类最多三层；父级列表已排除自身、子孙和会导致层级超限的分类。</p>
      <label for="category-sort">排序</label><input id="category-sort" v-model.number="sort" type="number" min="0" max="2147483647" step="1" required :disabled="busy || uncertain || conflict">
      <label class="category-enabled"><input v-model="enabled" type="checkbox" :disabled="busy || uncertain || conflict">启用分类</label>
      <p v-if="error" role="alert">{{ error }}</p><p v-if="uncertain" role="status">上次提交结果尚未确认，请保持原内容重试。关闭或刷新可能丢失原请求编号。</p>
      <div class="category-actions"><button type="button" class="ghost-btn" :disabled="busy" @click="close">关闭</button><button class="admin-primary" :disabled="busy || conflict">{{ busy ? '正在保存…' : uncertain ? '确认原请求结果' : '保存分类' }}</button></div>
    </form>
  </dialog>
</template>

<style scoped>
.category-editor{width:min(560px,90vw);max-height:85svh;overflow:auto;margin:auto;padding:28px;box-sizing:border-box;color:#30485e;border:1px solid #dbe4ed;border-radius:12px}.category-editor::backdrop{background:rgba(15,35,50,.5)}.category-editor h2{margin-top:0}.category-editor form>label{display:block;margin:20px 0 9px}.category-editor input:not([type=checkbox]),.category-editor select{box-sizing:border-box;width:100%;padding:12px;border:1px solid #bbcbd7;border-radius:6px;background:white;color:#263c50;font:inherit}.category-help{font-size:14px;line-height:1.6;color:#5f7386}.category-enabled{display:flex!important;align-items:center;gap:10px}.category-enabled input{width:20px;height:20px}.category-actions{display:flex;gap:12px;justify-content:flex-end;margin-top:24px}.category-editor button:disabled{opacity:.5;cursor:default}.category-editor input:focus-visible,.category-editor select:focus-visible,.category-editor button:focus-visible{outline:3px solid #216f8f;outline-offset:3px}
</style>
