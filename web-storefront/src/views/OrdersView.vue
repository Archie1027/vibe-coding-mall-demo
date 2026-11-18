<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../session'
import type { PageData } from '@zhicai/shared/catalog-api.ts'
import { formatMinor } from '@zhicai/shared/money.ts'
import { orderStateLabel, parseOrderPage, type Order, type OrderState } from '@zhicai/shared/order-read.ts'
import { ApiError } from '@zhicai/shared/api.ts'
import { useCustomerWrite } from '../customer-write'
import type { WriteOutcome } from '@zhicai/shared/customer-data.ts'

const route = useRoute(), router = useRouter()
const orders = ref<Order[]>([]), detail = ref<Order | null>(null), total = ref(0), loading = ref(true), loaded = ref(false), error = ref('')
const states = Object.keys(orderStateLabel) as OrderState[]
const page = computed(() => parseOrderPage(route.query.page))
const state = computed(() => states.includes(route.query.state as OrderState) ? route.query.state as OrderState : undefined)
const id = computed(() => typeof route.params.id === 'string' ? route.params.id : '')
const orderWritesEnabled = import.meta.env.VITE_CHECKOUT_WRITES_ENABLED === 'true'
const { busy, uncertain, frozen, message, send, retry } = useCustomerWrite()
const cancelFormOpen = ref(false)
const cancelReason = ref('')
const cancelNotice = ref('')
const requiresReload = ref(false)
const reasonLength = computed(() => Array.from(cancelReason.value.trim()).length)
const validReason = computed(() => reasonLength.value > 0 && reasonLength.value <= 500)
let requestId = 0
async function load() {
  if (frozen.value) return
  const current = ++requestId
  loading.value = true; error.value = ''; loaded.value = false; orders.value = []; detail.value = null
  cancelFormOpen.value = false
  cancelReason.value = ''
  cancelNotice.value = ''
  message.value = ''
  try {
    if (id.value) {
      const result = await api.request<Order>(`/orders/${encodeURIComponent(id.value)}`)
      if (current !== requestId) return
      detail.value = result
    }
    else {
      const params = new URLSearchParams({ page: String(page.value), size: '20' })
      if (state.value) params.set('state', state.value)
      const result = await api.request<PageData<Order>>(`/orders?${params}`)
      if (current !== requestId) return
      orders.value = result.items; total.value = result.total
    }
    if (current === requestId) {
      loaded.value = true
      requiresReload.value = false
    }
  } catch (reason) {
    if (current === requestId) error.value = reason instanceof Error ? reason.message : '订单加载失败'
  } finally { if (current === requestId) loading.value = false }
}
async function submitCancel() {
  const previous = detail.value
  if (!orderWritesEnabled || frozen.value || requiresReload.value || !validReason.value || previous?.state !== 'UNPAID') return
  cancelNotice.value = ''
  const outcome = await send({
    method: 'POST',
    path: `/orders/${encodeURIComponent(previous.id)}/cancel`,
    body: JSON.stringify({ reason: cancelReason.value.trim() }),
  }, async value => {
    requiresReload.value = true
    cancelFormOpen.value = false
    const response = value as Partial<Order> | null
    if (!response || response.id !== previous.id || response.customerId !== previous.customerId
      || !['CLOSING', 'CANCELLED'].includes(response.state ?? '') || response.version !== previous.version + 1) {
      throw new Error('取消响应与订单资料不一致，请重新加载确认结果')
    }
    const latest = await api.request<Order>(`/orders/${encodeURIComponent(previous.id)}`)
    if (latest.id !== previous.id || latest.state === 'UNPAID') {
      throw new Error('订单状态尚未确认，请重新加载订单')
    }
    detail.value = latest
    requiresReload.value = false
    cancelReason.value = ''
    cancelNotice.value = latest.state === 'CANCELLED' ? '订单已取消。' : '已读取最新订单状态。'
  })
  handleCancelOutcome(outcome)
}
function handleCancelOutcome(outcome: WriteOutcome) {
  if (outcome.kind === 'rejected' && outcome.error instanceof ApiError && outcome.error.code === 'STATE_CONFLICT') {
    requiresReload.value = true
    cancelFormOpen.value = false
    message.value = '订单状态发生变化，请重新加载订单。'
  }
}
async function retryCancel() {
  handleCancelOutcome(await retry())
}
function changePage(next: number) { void router.push({ path: '/account/orders', query: { ...route.query, page: next === 1 ? undefined : String(next) } }) }
function changeState(event: Event) {
  const next = (event.target as HTMLSelectElement).value
  void router.push({ path: '/account/orders', query: { state: next || undefined } })
}
watch(() => route.fullPath, load, { immediate: true })
</script>
<template>
<main class="customer-page shell">
  <nav class="customer-breadcrumb" aria-label="当前位置"><RouterLink to="/account">我的账户</RouterLink><span>/</span><RouterLink v-if="id" :to="{ path: '/account/orders', query: route.query }">我的订单</RouterLink><span v-else>我的订单</span><template v-if="id"><span>/</span><span>订单详情</span></template></nav>
  <div class="customer-heading"><div><h1>{{ id ? '订单详情' : '我的订单' }}</h1><p>查看订单状态、下单时的商品与收货资料。</p></div><RouterLink class="customer-link" to="/products">继续选购 →</RouterLink></div>
  <nav class="customer-tabs" aria-label="账户导航"><RouterLink to="/account">账户信息</RouterLink><RouterLink to="/account/addresses">收货地址</RouterLink><RouterLink to="/cart">购物车</RouterLink><RouterLink class="active" to="/account/orders">我的订单</RouterLink></nav>
  <div v-if="message" class="customer-notice" role="alert">{{ message }} <button v-if="uncertain" :disabled="busy" @click="retryCancel">重试原操作</button><button v-if="requiresReload" :disabled="frozen || loading" @click="load">重新加载订单</button></div>
  <p v-if="cancelNotice" class="customer-notice" role="status">{{ cancelNotice }}</p>
  <div v-if="error" class="customer-notice" role="alert">{{ error }} <button :disabled="frozen || loading" @click="load">重新加载</button></div>
  <section class="customer-sheet" :aria-label="id ? '订单详情' : '订单列表'" :aria-busy="loading">
    <div class="customer-section-heading"><h2>{{ id ? '订单资料' : '订单记录' }}</h2><span v-if="!id && loaded">共 {{ total }} 单</span></div>
    <p v-if="loading" class="customer-state" role="status">正在加载订单…</p>
    <div v-else-if="!loaded" class="customer-state">订单资料尚未加载，请重新加载。</div>
    <template v-else-if="detail">
      <div class="order-read-head"><div><p>订单号 {{ detail.orderNo }}</p><strong>{{ orderStateLabel[detail.state] }}</strong></div><span>{{ new Date(detail.createdAt).toLocaleString('zh-CN') }}</span></div>
      <div v-if="detail.state === 'CLOSING'" class="order-state-note" role="status"><p>正在核实支付结果，请稍后刷新查看订单状态。</p><button :disabled="frozen || loading" @click="load">刷新订单状态</button></div>
      <div v-for="line in detail.items" :key="line.id" class="order-read-line"><div><h3>{{ line.productName }}</h3><p>{{ line.specs.map(spec => `${spec.name}：${spec.value}`).join(' · ') || '标准规格' }} · 数量 {{ line.quantity }}</p></div><strong>{{ formatMinor(line.payableMinor) }}</strong></div>
      <div class="order-read-foot"><div><h3>收货资料</h3><p>{{ detail.addressSnapshot.recipient }} · {{ detail.addressSnapshot.phone }}</p><p>{{ detail.addressSnapshot.province }} {{ detail.addressSnapshot.city }} {{ detail.addressSnapshot.district }} {{ detail.addressSnapshot.detail }}</p></div><div><span>订单应付</span><strong>{{ formatMinor(detail.payableMinor) }}</strong></div></div>
      <section v-if="detail.state === 'UNPAID'" class="order-cancel-section" aria-label="取消订单">
        <template v-if="!cancelFormOpen"><button :disabled="!orderWritesEnabled || frozen || requiresReload" @click="cancelFormOpen = true">取消订单</button><p v-if="!orderWritesEnabled">取消服务暂未开放。</p></template>
        <form v-else class="order-cancel-form" @submit.prevent="submitCancel">
          <label for="order-cancel-reason">取消原因</label>
          <textarea id="order-cancel-reason" v-model="cancelReason" :disabled="frozen" maxlength="1000" rows="3" aria-describedby="order-cancel-hint"></textarea>
          <p id="order-cancel-hint">填写 1–500 个字的取消原因。支付仍在处理中时，订单会显示“支付核实中”。</p>
          <p v-if="reasonLength > 500" role="alert">取消原因最多 500 个字。</p>
          <div class="order-cancel-actions"><button class="customer-primary" type="submit" :disabled="frozen || requiresReload || !validReason">{{ busy ? '正在提交…' : '确认取消订单' }}</button><button type="button" :disabled="frozen" @click="cancelFormOpen = false">暂不取消</button></div>
        </form>
      </section>
      <RouterLink class="customer-link" :to="{ path: '/account/orders', query: route.query }">返回订单列表 →</RouterLink>
    </template>
    <template v-else>
      <label class="order-read-filter">订单状态 <select :value="state ?? ''" @change="changeState"><option value="">全部状态</option><option v-for="option in states" :key="option" :value="option">{{ orderStateLabel[option] }}</option></select></label>
      <div v-if="!orders.length && !error" class="customer-state"><h3>暂无订单</h3><p>这里会显示你的真实订单记录。</p><RouterLink class="customer-link" to="/products">浏览商品 →</RouterLink></div>
      <article v-for="order in orders" :key="order.id" class="order-read-card"><div class="order-read-head"><div><p>订单号 {{ order.orderNo }}</p><strong>{{ orderStateLabel[order.state] }}</strong></div><span>{{ new Date(order.createdAt).toLocaleString('zh-CN') }}</span></div><p v-if="order.state === 'CLOSING'">正在核实支付结果，请稍后刷新查看订单状态。</p><p>{{ order.items.map(line => line.productName).join('、') }}</p><div class="order-read-card-foot"><strong>{{ formatMinor(order.payableMinor) }}</strong><RouterLink class="customer-link" :to="{ path: `/account/orders/${order.id}`, query: route.query }">查看详情 →</RouterLink></div></article>
      <div v-if="total > 20" class="customer-pagination"><button :disabled="page <= 1 || loading" @click="changePage(page - 1)">上一页</button><span>第 {{ page }} / {{ Math.ceil(total / 20) }} 页</span><button :disabled="page * 20 >= total || loading" @click="changePage(page + 1)">下一页</button></div>
    </template>
  </section>
</main>
</template>
