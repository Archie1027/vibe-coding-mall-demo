<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../session'
import { createCatalogApi, latestRequest, type Product, type Sku } from '@zhicai/shared/catalog-api.ts'
import { formatMinor } from '@zhicai/shared/money.ts'
import { notify } from '@zhicai/shared/notifications.ts'
import { ApiError } from '@zhicai/shared/api.ts'
import { useCustomerWrite } from '../customer-write'

const route = useRoute(), catalog = createCatalogApi(api.request)
const router = useRouter()
const { busy, frozen, uncertain, message, send, retry } = useCustomerWrite()
const product = ref<Product | null>(null), brandName = ref(''), categoryName = ref('')
const loading = ref(true), error = ref(''), missing = ref(false), selectedSkuId = ref(''), imageUrl = ref(''), quantity = ref(1)
const enabledSkus = computed(() => product.value?.skus.filter(sku => sku.enabled) ?? [])
const selectedSku = computed<Sku | undefined>(() => enabledSkus.value.find(sku => sku.id === selectedSkuId.value))
const minPrice = computed(() => enabledSkus.value.reduce<string | undefined>((min, sku) => min === undefined || BigInt(sku.priceMinor) < BigInt(min) ? sku.priceMinor : min, undefined))
function skuLabel(sku: Sku) { return sku.specs.length ? sku.specs.map(spec => `${spec.name}：${spec.value}`).join(' · ') : sku.code }
const request = latestRequest<{ product: Product; brand: string; category: string }>(value => {
  product.value = value.product; brandName.value = value.brand; categoryName.value = value.category
  imageUrl.value = value.product.images[0]?.url ?? ''; selectedSkuId.value = ''; quantity.value = 1
  loading.value = false; error.value = ''; missing.value = false
}, reason => {
  product.value = null; loading.value = false; missing.value = reason instanceof ApiError && reason.status === 404
  error.value = reason instanceof Error ? reason.message : '商品加载失败'
})
async function load() {
  loading.value = true; error.value = ''; missing.value = false
  const id = String(route.params.id ?? '')
  await request(async () => {
    const [item, categories, brands] = await Promise.all([catalog.product(id), catalog.allCategories(), catalog.allBrands()])
    return { product: item, brand: brands.find(entry => entry.id === item.brandId)?.name ?? '品牌信息加载中', category: categories.find(entry => entry.id === item.categoryId)?.name ?? '未分类' }
  })
}
function changeQty(delta: number) { quantity.value = Math.max(1, Math.min(99, quantity.value + delta)) }
async function addToCart() {
  if (frozen.value) return
  if (!selectedSku.value) { notify('请先选择商品规格'); return }
  if (!api.hasSession()) { await router.push({ path: '/login', query: { redirect: route.fullPath } }); return }
  await send({ method: 'POST', path: '/me/cart/items', body: JSON.stringify({ skuId: selectedSku.value.id, quantity: quantity.value }) }, async () => { notify('已加入购物车') })
}
watch(() => route.params.id, () => { void load() })
onMounted(load)
</script>
<template>
<main v-if="loading" class="shell product-detail-page catalog-status" role="status">正在加载商品详情…</main>
<main v-else-if="error" class="shell product-detail-page catalog-status" role="alert"><h1>{{ missing ? '未找到该商品' : '商品详情暂时无法加载' }}</h1><p>{{ error }}</p><button v-if="!missing" class="btn btn-secondary" @click="load">重试</button><RouterLink class="btn btn-secondary" to="/products">返回商品列表</RouterLink></main>
<div v-else-if="product" id="productDetailView">
  <main class="shell product-detail-page">
    <div class="breadcrumbs"><RouterLink to="/">首页</RouterLink><span>›</span><RouterLink to="/products">{{ categoryName }}</RouterLink><span>›</span><strong>{{ product.name }}</strong></div>
    <div class="detail-top">
      <section class="detail-gallery"><div class="detail-main-image"><span v-if="product.featured" class="tag">旗舰精选</span><img v-if="imageUrl" class="detail-product-photo" :src="imageUrl" :alt="product.name" decoding="async"><span v-else class="subtle-note">暂无商品图片</span></div><div class="detail-thumbs"><button v-for="image in product.images" :key="image.id" class="thumb detail-photo-thumb" :class="{ active: imageUrl === image.url }" :aria-label="`查看${product.name}图片`" @click="imageUrl = image.url"><img :src="image.url" alt="" decoding="async"></button></div></section>
      <section class="detail-info"><h1>{{ product.name }}</h1><div class="detail-rating"><template v-if="product.ratingCount"><strong>{{ product.ratingAverage.toFixed(2) }}</strong><span>{{ product.ratingCount }} 条公开评价</span></template><span v-else>暂无公开评价</span></div>
        <div class="detail-price-row"><span class="detail-price">{{ selectedSku ? formatMinor(selectedSku.priceMinor) : minPrice ? `${formatMinor(minPrice)} 起` : '暂无价格' }}</span><span class="detail-price-note">开发库商品价格</span></div>
        <p class="detail-description">{{ product.description }}</p>
        <div class="detail-spec"><span>商品分类</span><strong>{{ categoryName }}</strong></div><div class="detail-spec"><span>品牌</span><strong>{{ brandName }}</strong></div>
        <div class="option-label">选择商品规格</div><div class="option-group"><button v-for="sku in enabledSkus" :key="sku.id" class="option" :disabled="frozen" :class="{ selected: selectedSkuId === sku.id }" @click="selectedSkuId = sku.id">{{ skuLabel(sku) }} · {{ formatMinor(sku.priceMinor) }}</button></div><p v-if="!selectedSku" class="subtle-note">请选择规格以查看对应价格并加入购物车。</p>
        <template v-if="selectedSku"><div v-for="spec in selectedSku.specs" :key="spec.name" class="detail-spec"><span>{{ spec.name }}</span><strong>{{ spec.value }}</strong></div><div class="detail-spec"><span>SKU 编码</span><strong>{{ selectedSku.code }}</strong></div></template>
        <div class="option-label">数量</div><div class="buy-row"><div class="quantity"><button :disabled="frozen || quantity <= 1" aria-label="减少数量" @click="changeQty(-1)">−</button><span id="detailQty">{{ quantity }}</span><button :disabled="frozen || quantity >= 99" aria-label="增加数量" @click="changeQty(1)">＋</button></div><span class="stock-note">库存查询尚未开放</span></div>
        <div v-if="message" class="customer-notice" role="alert">{{ message }} <button v-if="uncertain" :disabled="busy" @click="retry">重试原操作</button></div>
        <div class="detail-actions"><button class="btn btn-primary" :disabled="frozen || !selectedSku" @click="addToCart">{{ busy ? '正在加入…' : '加入购物车' }}</button><button class="btn btn-secondary" disabled>立即购买（暂未开放）</button></div><RouterLink class="customer-detail-cart" to="/cart">查看购物车 →</RouterLink><div class="detail-service"><span>加入购物车不预留库存</span><span>下单功能尚未开放</span></div>
      </section>
    </div>
    <section class="detail-lower" id="reviews"><div class="detail-tabs"><a class="active" href="#productOverview">商品详情</a><a href="#productSpecs">规格参数</a><a href="#reviews">商品评价 <span>{{ product.ratingCount }}</span></a></div><div class="detail-content-grid"><article class="detail-content-panel" id="productOverview"><h2>{{ product.name }}</h2><p class="detail-description">{{ product.description }}</p></article><aside v-if="product.ratingCount" class="review-summary-card"><div class="review-summary-top"><div><span class="review-summary-label">综合评分</span><strong class="review-big-score">{{ product.ratingAverage.toFixed(2) }}<span>/5</span></strong></div><div class="review-count"><strong>{{ product.ratingCount }}</strong><span>条公开评价</span></div></div></aside></div><div id="productSpecs" class="detail-content-panel"><h2>规格参数</h2><p v-if="!selectedSku" class="subtle-note">选择 SKU 后查看规格参数。</p><div v-else v-for="spec in selectedSku.specs" :key="spec.name" class="detail-spec"><span>{{ spec.name }}</span><strong>{{ spec.value }}</strong></div></div><div class="reviews-toolbar"><div><h2>用户评价</h2><p>{{ product.ratingCount ? '评价详情接口尚未接入，当前只展示真实汇总。' : '暂无公开评价。' }}</p></div><button class="btn btn-secondary" @click="notify('评价功能尚未开放')">写评价</button></div></section>
  </main>
</div>
</template>
