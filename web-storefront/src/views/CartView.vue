<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../session'
import type { PageData } from '@zhicai/shared/catalog-api.ts'
import { cartPageSubtotal, type CartLine } from '@zhicai/shared/customer-data.ts'
import { formatMinor, sumLines } from '@zhicai/shared/money.ts'
import { useCustomerWrite } from '../customer-write'
const items = ref<CartLine[]>([]), quantities = ref<Record<string, number>>({}), page = ref(1), total = ref(0), loading = ref(true), loaded = ref(false), error = ref('')
const { busy, frozen, uncertain, message, send, retry } = useCustomerWrite()
const subtotal = computed(() => formatMinor(cartPageSubtotal(items.value)))
const checkoutEnabled = import.meta.env.VITE_CHECKOUT_WRITES_ENABLED === 'true'
const selectedCount = computed(() => items.value.filter(item => item.selected && item.valid).reduce((sum, item) => sum + item.quantity, 0))
async function loadStrict() {
  loading.value = true; error.value = ''
  try {
    let result = await api.request<PageData<CartLine>>(`/me/cart?page=${page.value}&size=20`)
    if (!result.items.length && result.total && page.value > 1) { page.value = Math.ceil(result.total / 20); result = await api.request<PageData<CartLine>>(`/me/cart?page=${page.value}&size=20`) }
    items.value = result.items; quantities.value = Object.fromEntries(result.items.map(item => [item.id, item.quantity])); total.value = result.total; loaded.value = true
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '购物车加载失败'; throw reason }
  finally { loading.value = false }
}
async function load() { try { await loadStrict() } catch { /* Error is shown beside the list. */ } }
async function update(line: CartLine, selected = line.selected) {
  const quantity = quantities.value[line.id]
  if (!Number.isInteger(quantity) || quantity! < 1 || quantity! > 99) { message.value = '商品数量须为 1–99 的整数'; return }
  await send({ method: 'PUT', path: `/me/cart/items/${encodeURIComponent(line.id)}`, body: JSON.stringify({ quantity, selected }) }, async () => { await loadStrict() })
}
function toggle(line: CartLine, event: Event) { const checkbox = event.target as HTMLInputElement; const selected = checkbox.checked; checkbox.checked = line.selected; void update(line, selected) }
async function remove(line: CartLine) { if (frozen.value || !window.confirm(`确定从购物车移除“${line.productName}”？`)) return; await send({ method: 'DELETE', path: `/me/cart/items/${encodeURIComponent(line.id)}` }, async () => { await loadStrict() }) }
async function changePage(next: number) { if (frozen.value || loading.value) return; page.value = next; await load() }
onMounted(load)
</script>
<template>
<main class="customer-page shell">
  <nav class="customer-breadcrumb" aria-label="当前位置"><RouterLink to="/">首页</RouterLink><span>/</span><span>购物车</span></nav>
  <div class="customer-heading"><div><h1>购物车</h1><p>保存心仪商品；加入购物车不预留库存。</p></div><RouterLink class="customer-link" to="/products">继续选购 →</RouterLink></div>
  <div v-if="message" class="customer-notice" role="alert">{{ message }} <button v-if="uncertain" :disabled="busy" @click="retry">重试原操作</button></div>
  <div v-if="error" class="customer-notice" role="alert">{{ error }} <button :disabled="frozen || loading" @click="load">重新加载</button></div>
  <section class="customer-sheet" aria-label="购物车商品" :aria-busy="loading">
    <div class="customer-section-heading"><h2>已加入商品</h2><span>{{ loaded ? `共 ${total} 种规格` : '—' }}</span></div>
    <p v-if="loading" class="customer-state" role="status">正在加载购物车…</p>
    <div v-else-if="!loaded" class="customer-state">购物车尚未加载，请重新加载。</div>
    <div v-else-if="!items.length && !error" class="customer-state"><h3>购物车还是空的</h3><p>从商品详情选择规格后，即可加入购物车。</p><RouterLink class="customer-link" to="/products">去选购商品 →</RouterLink></div>
    <template v-else>
      <div class="cart-column-head" aria-hidden="true"><span>商品 / 规格</span><span>单价</span><span>数量</span><span>小计 / 操作</span></div>
      <article v-for="line in items" :key="line.id" class="cart-row" :class="{ 'cart-invalid': !line.valid }">
        <div class="cart-product"><div class="cart-product-row"><img v-if="line.imageUrl" class="cart-product-image" :src="line.imageUrl" :alt="line.productName" loading="lazy"><div class="cart-product-copy"><label class="customer-check"><input type="checkbox" :checked="line.selected" :aria-label="`选择${line.productName}`" :disabled="frozen || loading || !!error || (!line.valid && !line.selected)" @change="toggle(line, $event)"><span>{{ line.productName }}</span></label><p>{{ line.specs.map(spec => `${spec.name}：${spec.value}`).join(' · ') || '标准规格' }}</p><p v-if="!line.valid" class="cart-invalid-reason">{{ line.invalidReason || '商品暂不可用' }}，可取消勾选或移除。</p></div></div></div>
        <div class="cart-price"><span class="mobile-label">单价</span>{{ formatMinor(line.priceMinor) }}</div>
        <div class="cart-quantity"><label :for="`qty-${line.id}`" class="mobile-label">数量</label><input :id="`qty-${line.id}`" v-model.number="quantities[line.id]" :aria-label="`${line.productName}数量`" type="number" min="1" max="99" step="1" inputmode="numeric" :disabled="frozen || loading || !!error || !line.valid"><button :disabled="frozen || loading || !!error || !line.valid || quantities[line.id] === line.quantity" @click="update(line)">更新数量</button></div>
        <div class="cart-line-total"><strong>{{ formatMinor(sumLines([line])) }}</strong><button class="customer-delete" :disabled="frozen || loading || !!error" @click="remove(line)">移除</button></div>
      </article>
    </template>
    <div v-if="total > 20" class="customer-pagination"><button :disabled="page <= 1 || frozen || loading" @click="changePage(page - 1)">上一页</button><span>第 {{ page }} / {{ Math.ceil(total / 20) }} 页</span><button :disabled="page * 20 >= total || frozen || loading" @click="changePage(page + 1)">下一页</button></div>
  </section>
  <section class="cart-summary" aria-label="本页购物车合计"><div><span>本页已选合计</span><strong>{{ loaded && !error && !loading ? subtotal : '—' }}</strong><p>本页已选 {{ loaded && !error && !loading ? selectedCount : '—' }} 件有效商品，失效商品不计入合计。</p></div><div><RouterLink v-if="loaded && !error && !loading && !frozen" class="customer-link" to="/checkout">{{ checkoutEnabled ? '前往确认订单' : '查看结算页' }} →</RouterLink><button v-else class="customer-primary" disabled>结算资料暂不可用</button><p>{{ checkoutEnabled ? '正式金额与库存由服务端报价确认。' : '本地结算写入尚未启用，可先查看结算资料。' }}</p></div></section>
</main>
</template>
