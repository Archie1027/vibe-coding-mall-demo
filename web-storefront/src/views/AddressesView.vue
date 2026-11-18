<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../session'
import { ApiError } from '@zhicai/shared/api.ts'
import type { PageData } from '@zhicai/shared/catalog-api.ts'
import type { Address, AddressInput, WriteOutcome } from '@zhicai/shared/customer-data.ts'
import { useCustomerWrite } from '../customer-write'
import AddressEditor from '../components/AddressEditor.vue'

const items = ref<Address[]>([]), total = ref(0), page = ref(1), loading = ref(true), error = ref(''), loaded = ref(false)
const editor = ref(false), editing = ref<Address>(), editorKey = ref(0)
const { busy, frozen, uncertain, message, send, retry } = useCustomerWrite()
async function loadStrict() {
  loading.value = true; error.value = ''
  try {
    let result = await api.request<PageData<Address>>(`/me/addresses?page=${page.value}&size=20`)
    if (!result.items.length && result.total && page.value > 1) { page.value = Math.ceil(result.total / 20); result = await api.request<PageData<Address>>(`/me/addresses?page=${page.value}&size=20`) }
    items.value = result.items; total.value = result.total; loaded.value = true
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '地址加载失败'; throw reason }
  finally { loading.value = false }
}
async function load() { try { await loadStrict() } catch { /* Error is shown beside the list. */ } }
function open(address?: Address) { editing.value = address ? { ...address } : undefined; editorKey.value++; editor.value = true }
async function save(input: AddressInput) {
  const address = editing.value
  const result = await send({ method: address ? 'PUT' : 'POST', path: address ? `/me/addresses/${encodeURIComponent(address.id)}` : '/me/addresses', body: JSON.stringify(address ? { ...input, version: address.version } : input) }, async () => { editor.value = false; editing.value = undefined; await loadStrict() })
  await handleConflict(result)
}
async function handleConflict(result: WriteOutcome) { if (result.kind === 'rejected' && result.error instanceof ApiError && result.error.code === 'VERSION_CONFLICT') { editor.value = false; editing.value = undefined; await load(); message.value = '这条地址已发生变化，请根据最新资料重新编辑。' } }
async function retryWrite() { await handleConflict(await retry()) }
async function remove(address: Address) {
  if (frozen.value || !window.confirm(`确定删除 ${address.recipient} 的这条收货地址？`)) return
  await send({ method: 'DELETE', path: `/me/addresses/${encodeURIComponent(address.id)}` }, async () => { editor.value = false; editing.value = undefined; await loadStrict() })
}
async function changePage(next: number) { if (frozen.value || loading.value) return; page.value = next; editor.value = false; await load() }
onMounted(load)
</script>
<template>
<main class="customer-page shell">
  <nav class="customer-breadcrumb" aria-label="当前位置"><RouterLink to="/account">我的账户</RouterLink><span>/</span><span>收货地址</span></nav>
  <div class="customer-heading"><div><h1>收货地址</h1><p>最多保存 20 条地址，默认地址最多一条。</p></div><button class="customer-primary" :disabled="frozen || loading || !loaded || !!error || total >= 20" @click="open()">新增地址</button></div>
  <nav class="customer-tabs" aria-label="账户导航"><RouterLink to="/account">账户信息</RouterLink><RouterLink to="/account/addresses" class="active">收货地址</RouterLink><RouterLink to="/cart">购物车</RouterLink><RouterLink to="/account/orders">我的订单</RouterLink></nav>
  <div v-if="message" class="customer-notice" role="alert">{{ message }} <button v-if="uncertain" :disabled="busy" @click="retryWrite">重试原操作</button></div>
  <div v-if="error" class="customer-notice" role="alert">{{ error }} <button :disabled="frozen || loading" @click="load">重新加载</button></div>
  <AddressEditor v-if="editor" :key="editorKey" :initial="editing" :editing="!!editing" :disabled="frozen" @save="save" @cancel="editor = false" />
  <section class="customer-sheet" aria-label="已保存地址" :aria-busy="loading">
    <div class="customer-section-heading"><h2>已保存地址</h2><span>{{ loaded ? `${total} / 20 条` : '— / 20 条' }}</span></div>
    <p v-if="loading" class="customer-state" role="status">正在加载地址…</p>
    <div v-else-if="!loaded" class="customer-state">地址尚未加载，请重新加载。</div>
    <div v-else-if="!items.length && !error" class="customer-state"><h3>还没有收货地址</h3><p>添加常用收货地址，方便后续使用。</p><button :disabled="frozen" @click="open()">新增地址</button></div>
    <div v-for="address in items" v-else :key="address.id" class="address-row">
      <div><h3>{{ address.recipient }} <span class="address-phone">{{ address.phone }}</span><span v-if="address.isDefault" class="customer-tag">默认地址</span></h3><p>{{ address.province }} {{ address.city }} {{ address.district }}</p><p>{{ address.detail }}</p></div>
      <div class="customer-actions"><button :disabled="frozen || loading || !!error" @click="open(address)">编辑</button><button class="customer-delete" :disabled="frozen || loading || !!error" @click="remove(address)">删除</button></div>
    </div>
    <div v-if="total > 20" class="customer-pagination"><button :disabled="page <= 1 || frozen || loading" @click="changePage(page - 1)">上一页</button><span>第 {{ page }} / {{ Math.ceil(total / 20) }} 页</span><button :disabled="page * 20 >= total || frozen || loading" @click="changePage(page + 1)">下一页</button></div>
  </section>
</main>
</template>
