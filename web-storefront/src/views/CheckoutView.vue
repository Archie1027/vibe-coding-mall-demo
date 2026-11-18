<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../session'
import { useCustomerWrite } from '../customer-write'
import { ApiError } from '@zhicai/shared/api.ts'
import type { PageData } from '@zhicai/shared/catalog-api.ts'
import type { Address, CartLine, WriteOutcome } from '@zhicai/shared/customer-data.ts'
import { quoteMatchesItems, selectedQuoteItems, type CheckoutQuote } from '@zhicai/shared/checkout.ts'
import type { Order } from '@zhicai/shared/order-read.ts'
import { formatMinor, sumLines } from '@zhicai/shared/money.ts'

const checkoutEnabled = import.meta.env.VITE_CHECKOUT_WRITES_ENABLED === 'true'
const router = useRouter()
const addresses = ref<Address[]>([]), cart = ref<CartLine[]>([]), addressId = ref('')
const loading = ref(true), loaded = ref(false), loadError = ref(''), formError = ref('')
const quote = ref<CheckoutQuote | null>(null), remark = ref(''), now = ref(Date.now())
const createdOrderId = ref('')
const { busy, uncertain, frozen, message, send, retry } = useCustomerWrite()
const selected = computed(() => cart.value.filter(line => line.selected))
const selectionError = computed(() => { try { selectedQuoteItems(cart.value); return '' } catch (error) { return error instanceof Error ? error.message : '购物车无法结算' } })
const estimate = computed(() => formatMinor(sumLines(selected.value.filter(line => line.valid))))
const remainingSeconds = computed(() => quote.value ? Math.max(0, Math.ceil((Date.parse(quote.value.expiresAt) - now.value) / 1000)) : 0)
const quoteExpired = computed(() => !!quote.value && remainingSeconds.value === 0)
let clock: ReturnType<typeof setInterval> | undefined

async function readCart(): Promise<CartLine[]> {
  const items: CartLine[] = []
  let total: number | undefined
  for (let page = 1; ; page++) {
    const result = await api.request<PageData<CartLine>>(`/me/cart?page=${page}&size=100`)
    if (!Array.isArray(result.items) || !Number.isSafeInteger(result.total) || result.total < 0 || result.page !== page || result.size !== 100 || (total !== undefined && total !== result.total)) throw new Error('购物车分页结果发生变化，请重新加载')
    total = result.total
    items.push(...result.items)
    if (items.length >= total) {
      if (items.length !== total) throw new Error('购物车分页结果不完整，请重新加载')
      return items
    }
    if (!result.items.length || page >= 100) throw new Error('购物车分页结果不完整，请重新加载')
  }
}

async function load() {
  if (frozen.value) return
  loading.value = true; loaded.value = false; loadError.value = ''; formError.value = ''; quote.value = null
  try {
    const [addressPage, lines] = await Promise.all([api.request<PageData<Address>>('/me/addresses?page=1&size=20'), readCart()])
    if (!Array.isArray(addressPage.items) || addressPage.total !== addressPage.items.length) throw new Error('收货地址结果不完整，请重新加载')
    addresses.value = addressPage.items; cart.value = lines
    if (!addresses.value.some(item => item.id === addressId.value)) addressId.value = addresses.value.find(item => item.isDefault)?.id ?? addresses.value[0]?.id ?? ''
    loaded.value = true
  } catch (error) { loadError.value = error instanceof Error ? error.message : '结算资料加载失败' }
  finally { loading.value = false }
}

function changeAddress(event: Event) { addressId.value = (event.target as HTMLSelectElement).value; quote.value = null; formError.value = '' }
async function getQuote() {
  if (!checkoutEnabled || frozen.value || loading.value || !loaded.value) return
  formError.value = ''; quote.value = null
  if (!addressId.value) { formError.value = '请先添加收货地址'; return }
  let items
  try { items = selectedQuoteItems(cart.value) } catch (error) { formError.value = error instanceof Error ? error.message : '购物车无法结算'; return }
  await send({ method: 'POST', path: '/checkout/quotes', body: JSON.stringify({ addressId: addressId.value, items }) }, async value => {
    const result = value as CheckoutQuote
    if (!quoteMatchesItems(result, items)) throw new Error('报价商品与购物车不一致，请刷新后重新确认')
    quote.value = result; now.value = Date.now()
  })
}
async function submitOrder() {
  if (!checkoutEnabled || frozen.value || !quote.value || quoteExpired.value) return
  formError.value = ''
  if (Array.from(remark.value).length > 200) { formError.value = '订单备注最多 200 个字'; return }
  const request = { quoteId: quote.value.id, ...(remark.value.trim() ? { remark: remark.value.trim() } : {}) }
  createdOrderId.value = ''
  const outcome = await send({ method: 'POST', path: '/orders', body: JSON.stringify(request) }, async value => {
    const result = value as Order
    if (!result || !/^[1-9]\d*$/.test(result.id) || result.state !== 'UNPAID' || result.payableMinor !== quote.value?.payableMinor) throw new Error('订单响应无法核对，请到“我的订单”查看结果')
    createdOrderId.value = result.id
  })
  await handleOrderOutcome(outcome)
}
async function handleOrderOutcome(outcome: WriteOutcome) {
  if (outcome.kind === 'success' && createdOrderId.value) await router.push(`/account/orders/${createdOrderId.value}`)
  if (outcome.kind === 'rejected' && outcome.error instanceof ApiError && ['QUOTE_EXPIRED', 'QUOTE_CHANGED', 'STATE_CONFLICT'].includes(outcome.error.code)) quote.value = null
}
async function retryWrite() { await handleOrderOutcome(await retry()) }
onMounted(() => { void load(); clock = setInterval(() => { now.value = Date.now() }, 1000) })
onUnmounted(() => { if (clock) clearInterval(clock) })
</script>

<template>
  <main class="customer-page checkout-page shell">
    <nav class="customer-breadcrumb" aria-label="当前位置"><RouterLink to="/">首页</RouterLink><span>/</span><RouterLink to="/cart">购物车</RouterLink><span>/</span><span>确认订单</span></nav>
    <div class="customer-heading"><div><h1>确认订单</h1><p>先核对购物车，再由服务端计算报价和可用仓库。</p></div><RouterLink class="customer-link" to="/cart">返回购物车 →</RouterLink></div>
    <div v-if="!checkoutEnabled" class="customer-notice" role="status">本地结算写入尚未启用。你可以查看真实购物车和地址；获取报价与下单暂不可用。</div>
    <div v-if="message" class="customer-notice" role="alert">{{ message }} <button v-if="uncertain" :disabled="busy" @click="retryWrite">重试原操作</button></div>
    <div v-if="loadError || formError" class="customer-notice" role="alert">{{ loadError || formError }} <button v-if="loadError" :disabled="frozen || loading" @click="load">重新加载</button></div>
    <p v-if="loading" class="customer-state" role="status">正在加载购物车和收货地址…</p>
    <div v-else-if="loaded" class="checkout-grid">
      <div class="checkout-content">
        <section class="customer-sheet" aria-labelledby="checkout-address-title">
          <div class="customer-section-heading"><h2 id="checkout-address-title">收货地址</h2><RouterLink class="customer-link" to="/account/addresses">管理地址 →</RouterLink></div>
          <p v-if="!addresses.length" class="customer-state">还没有收货地址，请先添加。</p>
          <label v-else class="checkout-field">选择收货地址
            <select :value="addressId" :disabled="frozen" @change="changeAddress"><option v-for="address in addresses" :key="address.id" :value="address.id">{{ address.recipient }} · {{ address.province }} {{ address.city }} {{ address.district }} {{ address.detail }}{{ address.isDefault ? '（默认）' : '' }}</option></select>
          </label>
          <p v-if="addressId" class="checkout-address-detail">{{ addresses.find(item => item.id === addressId)?.recipient }} · {{ addresses.find(item => item.id === addressId)?.phone }}</p>
        </section>
        <section class="customer-sheet" aria-labelledby="checkout-items-title">
          <div class="customer-section-heading"><h2 id="checkout-items-title">已选商品</h2><span>{{ selected.length }} 种规格</span></div>
          <p v-if="selectionError" class="checkout-warning">{{ selectionError }} <RouterLink to="/cart">返回购物车调整</RouterLink></p>
          <article v-for="line in selected" :key="line.id" class="checkout-product"><img v-if="line.imageUrl" class="checkout-product-image" :src="line.imageUrl" :alt="line.productName" loading="lazy"><div class="checkout-product-info"><h3>{{ line.productName }}</h3><p>{{ line.specs.map(spec => `${spec.name}：${spec.value}`).join(' · ') || '标准规格' }}</p><p v-if="!line.valid" class="checkout-warning">{{ line.invalidReason || '商品暂不可用' }}</p></div><div class="checkout-product-price"><strong>{{ formatMinor(line.priceMinor) }}</strong><span>× {{ line.quantity }}</span></div></article>
          <p class="checkout-hint">购物车金额仅供参考，最终金额和整单履约仓以服务端报价为准。获取报价不预留库存。</p>
        </section>
        <section class="customer-sheet" aria-labelledby="checkout-remark-title"><h2 id="checkout-remark-title">订单备注</h2><label class="checkout-field">给商家的留言（选填）<textarea v-model="remark" rows="3" maxlength="400" :disabled="frozen" placeholder="例如：工作日配送，送达前请电话联系"></textarea></label><p class="checkout-hint">最多 200 个字；提交订单后不再修改本次请求。</p></section>
      </div>
      <aside class="customer-sheet checkout-summary" aria-label="报价与应付金额">
        <h2>报价与金额</h2>
        <div class="checkout-amount"><span>购物车预估</span><strong>{{ estimate }}</strong></div>
        <template v-if="quote"><div class="checkout-amount"><span>商品金额</span><strong>{{ formatMinor(quote.grossMinor) }}</strong></div><div class="checkout-amount"><span>优惠</span><strong>− {{ formatMinor(quote.discountMinor) }}</strong></div><div class="checkout-amount"><span>运费</span><strong>{{ formatMinor(quote.shippingMinor) }}</strong></div><div class="checkout-amount checkout-payable"><span>服务端应付</span><strong>{{ formatMinor(quote.payableMinor) }}</strong></div><p class="checkout-hint">由仓库 {{ quote.warehouseId }} 整单履约。报价{{ quoteExpired ? '已过期，请重新获取' : `剩余约 ${remainingSeconds} 秒` }}；待付款订单到期时间为创建后 30 分钟，自动关单与释放库存仍在开发中。</p></template>
        <p v-else class="checkout-hint">尚未获取服务端报价。优惠券和支付尚未接入。</p>
        <button v-if="!quote || quoteExpired" class="customer-primary checkout-action" :disabled="!checkoutEnabled || frozen || loading || !loaded || !!loadError || !!selectionError || !addressId" @click="getQuote">{{ busy ? '正在获取报价…' : '获取服务端报价' }}</button>
        <button v-else class="customer-primary checkout-action" :disabled="!checkoutEnabled || frozen" @click="submitOrder">{{ busy ? '正在创建订单…' : '确认金额并创建待付款订单' }}</button>
        <button class="checkout-refresh" :disabled="frozen || loading" @click="load">重新读取购物车与地址</button>
        <p class="checkout-hint">当前不提供支付。创建成功后可在“我的订单”查看待付款订单。</p>
      </aside>
    </div>
  </main>
</template>
