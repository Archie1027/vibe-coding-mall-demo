<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../session'
import ProductCard from '../components/ProductCard.vue'
import { catalogPageSize, catalogResultLabel, catalogRouteUpdate, createCatalogApi, latestRequest, toCatalogCard, type Category, type Brand, type CatalogCard, type PageData, type ProductSort } from '@zhicai/shared/catalog-api.ts'

const route = useRoute(), router = useRouter(), catalog = createCatalogApi(api.request)
const categories = ref<Category[]>([]), brands = ref<Brand[]>([]), cards = ref<CatalogCard[]>([])
const search = ref(String(route.query.q ?? route.query.keyword ?? ''))
const loading = ref(true), error = ref(''), dictionaryReady = ref(false)
const resultsElement = ref<HTMLElement | null>(null), resultsMinHeight = ref(0)
const pageData = ref<PageData<unknown>>({ items: [], page: 1, size: 12, total: 0 })
const category = computed(() => String(route.query.categoryId ?? route.query.category ?? ''))
const brand = computed(() => String(route.query.brandId ?? route.query.brand ?? ''))
const price = computed(() => String(route.query.price ?? ''))
const customPrice = computed(() => !['low', 'mid', 'high'].includes(price.value) && (route.query.minPriceMinor !== undefined || route.query.maxPriceMinor !== undefined))
const sort = computed(() => String(route.query.sort ?? 'NEWEST'))
const sortOption = computed<ProductSort>(() => sort.value === 'priceAsc' ? 'PRICE_ASC' : sort.value === 'priceDesc' ? 'PRICE_DESC' : sort.value === 'PRICE_ASC' || sort.value === 'PRICE_DESC' ? sort.value : 'NEWEST')
const currentPage = computed(() => Math.max(1, Number(route.query.page) || 1))
const pageCount = computed(() => Math.ceil(pageData.value.total / pageData.value.size))
const visiblePages = computed(() => {
  const start = Math.max(1, Math.min(pageData.value.page - 2, pageCount.value - 4))
  return Array.from({ length: Math.min(5, pageCount.value) }, (_, index) => start + index)
})

function lookupId(value: string, entries: { id: string; name: string }[]): string | undefined {
  if (!value || value === '全部') return undefined
  return entries.find(item => item.id === value || item.name === value)?.id ?? value
}
function priceBounds() {
  if (price.value === 'low') return { maxPriceMinor: '99999' }
  if (price.value === 'mid') return { minPriceMinor: '100000', maxPriceMinor: '500000' }
  if (price.value === 'high') return { minPriceMinor: '500001' }
  return { minPriceMinor: String(route.query.minPriceMinor || '') || undefined, maxPriceMinor: String(route.query.maxPriceMinor || '') || undefined }
}
const loadPage = latestRequest<{ page: PageData<unknown>; cards: CatalogCard[] }>(value => {
  pageData.value = value.page; cards.value = value.cards; error.value = ''; loading.value = false
}, reason => {
  cards.value = []; error.value = reason instanceof Error ? reason.message : '商品加载失败'; loading.value = false
})
async function load() {
  if (!dictionaryReady.value) return
  // Keep the current viewport supported when loading, empty or error states shorten the grid.
  resultsMinHeight.value = Math.max(0, window.innerHeight - (resultsElement.value?.getBoundingClientRect().top ?? 0))
  loading.value = true
  const query = {
    page: currentPage.value, size: catalogPageSize(route.query.size), keyword: String(route.query.q ?? route.query.keyword ?? '').trim() || undefined,
    categoryId: lookupId(category.value, categories.value), brandId: lookupId(brand.value, brands.value),
    ...priceBounds(), featured: route.query.featured === 'true' ? true : undefined,
    sort: sortOption.value,
  }
  await loadPage(async () => {
    const page = await catalog.products(query)
    return { page, cards: page.items.map(item => toCatalogCard(item, categories.value, brands.value)) }
  })
}
async function loadDictionary() {
  loading.value = true
  try {
    const [nextCategories, nextBrands] = await Promise.all([catalog.allCategories(), catalog.allBrands()])
    categories.value = nextCategories; brands.value = nextBrands; dictionaryReady.value = true
    await load()
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '分类或品牌加载失败'; loading.value = false }
  finally { await nextTick(); window.dispatchEvent(new CustomEvent('zhicai-content-ready', { detail: '/products' })) }
}
function update(key: string, value: string) {
  void router.replace({ query: catalogRouteUpdate(route.query, key, value) })
}
function turn(page: number) { if (page >= 1 && page <= pageCount.value) void router.push({ query: { ...route.query, page: String(page) } }) }
watch(() => route.fullPath, () => { search.value = String(route.query.q ?? route.query.keyword ?? ''); void load() })
watch(search, value => { if (value !== String(route.query.q ?? route.query.keyword ?? '')) update('q', value) })
onMounted(loadDictionary)
</script>
<template>
<div id="productListView">
  <main class="shell product-detail-page catalog-page">
    <div class="catalog-intro"><h1>找到你要的那一件。</h1><p>智采商城收录电脑硬件、手机、平板等数码商品。按分类、品牌和价格慢慢挑，查看开发库中的商品资料与测试样本。</p><a class="catalog-intro-link" href="#catalogResults">开始挑选 <span aria-hidden="true">→</span></a></div>
    <div class="catalog-layout">
      <aside class="catalog-sidebar">
        <h3>商品分类</h3>
        <div class="catalog-category-options"><button class="catalog-side-link" :class="{ active: !category }" @click="update('category', '')">全部商品</button>
        <button v-for="item in categories" :key="item.id" class="catalog-side-link" :class="{ active: category === item.id || category === item.name }" @click="update('category', item.id)">{{ item.name }}</button></div>
        <select id="catalogMobileCategory" class="catalog-category-select" aria-label="商品分类" :value="lookupId(category, categories) || ''" @change="update('category', ($event.target as HTMLSelectElement).value)"><option value="">全部商品</option><option v-for="item in categories" :key="item.id" :value="item.id">{{ item.name }}</option></select>
        <div class="catalog-filter-block"><label for="listBrand">品牌</label><select id="listBrand" :value="lookupId(brand, brands) || ''" @change="update('brand', ($event.target as HTMLSelectElement).value)"><option value="">全部品牌</option><option v-for="item in brands" :key="item.id" :value="item.id">{{ item.name }}</option></select></div>
        <div class="catalog-filter-block"><label for="listPrice">价格区间</label><select id="listPrice" :value="customPrice ? 'custom' : price" @change="update('price', ($event.target as HTMLSelectElement).value)"><option value="">全部价格</option><option v-if="customPrice" value="custom">自定义价格区间</option><option value="low">¥1,000 以下</option><option value="mid">¥1,000–¥5,000</option><option value="high">¥5,000 以上</option></select></div>
        <div class="catalog-filter-block"><span class="subtle-note">价格按商品最低启用 SKU 查询；交易功能尚未开放。</span></div>
      </aside>
      <section id="catalogResults" ref="resultsElement" class="catalog-results" :style="{ minHeight: `${resultsMinHeight}px` }">
        <div class="catalog-toolbar"><span class="result-count" id="catalogResultCount">{{ catalogResultLabel(loading, error, pageData.total) }}</span><div class="catalog-controls"><input id="listSearch" class="form-field" placeholder="搜索商品名称或型号" aria-label="搜索商品列表" v-model="search"><select id="listSort" aria-label="商品排序" :value="sortOption" @change="update('sort', ($event.target as HTMLSelectElement).value)"><option value="NEWEST">最新上架</option><option value="PRICE_ASC">价格从低到高</option><option value="PRICE_DESC">价格从高到低</option></select></div></div>
        <div v-if="loading" class="catalog-status" role="status">正在加载商品…</div>
        <div v-else-if="error" class="catalog-status" role="alert">{{ error }} <button class="filter" @click="dictionaryReady ? load() : loadDictionary()">重试</button></div>
        <template v-else><div class="catalog-products" id="catalogProducts"><ProductCard v-for="product in cards" :key="product.id" :product="product" :show-rating="true" /></div><p v-if="cards.length === 0" id="catalogEmpty" class="catalog-status">没有匹配的商品，请调整筛选条件。</p>
          <div class="catalog-footer"><span>第 {{ pageData.page }} 页 · 每页 {{ pageData.size }} 件 · 共 {{ pageData.total }} 件</span><div class="page-number"><button :disabled="pageData.page <= 1" @click="turn(pageData.page - 1)">‹</button><button v-for="number in visiblePages" :key="number" :class="{ active: pageData.page === number }" @click="turn(number)">{{ number }}</button><button :disabled="pageData.page >= pageCount" @click="turn(pageData.page + 1)">›</button></div></div>
        </template>
      </section>
    </div>
  </main>
</div>
</template>
