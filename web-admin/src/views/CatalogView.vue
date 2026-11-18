<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, account } from '../session'
import AdminSidebar from '../components/AdminSidebar.vue'
import BrandEditor from '../components/BrandEditor.vue'
import CategoryEditor from '../components/CategoryEditor.vue'
import ProductEditor from '../components/ProductEditor.vue'
import SkuEditor from '../components/SkuEditor.vue'
import ProductStateEditor from '../components/ProductStateEditor.vue'
import { notify } from '@zhicai/shared/notifications.ts'
import { latestRequest, type Product, type Category, type Brand, type PageData, type Sku } from '@zhicai/shared/catalog-api.ts'
import { formatMinor } from '@zhicai/shared/money.ts'

const route = useRoute(), router = useRouter()
const allowed = computed(() => account.value?.permissions.includes('catalog:read'))
const canWrite = computed(() => account.value?.permissions.includes('catalog:write'))
const brandEditor = ref<{ brand: Brand | null } | null>(null)
const categoryEditor = ref<{ category: Category | null } | null>(null), dictionaryLoading = ref(false)
const productEditor = ref<{ product: Product | null } | null>(null)
const skuEditor = ref<{ productId: string; sku: Sku | null } | null>(null)
const stateEditor = ref<{ product: Product; state: 'ON_SALE' | 'OFF_SALE' } | null>(null)
const tab = computed(() => route.query.tab === 'categories' || route.query.tab === 'brands' ? route.query.tab : 'products')
const keyword = ref(String(route.query.q ?? ''))
const page = computed(() => Math.max(1, Number(route.query.page) || 1))
const loading = ref(false), error = ref(''), total = ref(0)
const products = ref<Product[]>([]), dictionaries = ref<(Category | Brand)[]>([])
const categories = ref<Category[]>([]), brands = ref<Brand[]>([])
const detail = ref<Product | null>(null), detailLoading = ref(false), detailError = ref('')
const detailElement = ref<HTMLDialogElement | null>(null), dictionaryError = ref('')
let detailSerial = 0
const pages = computed(() => Math.ceil(total.value / 20))
const names = { products: '商品', categories: '分类', brands: '品牌' }
const publish = latestRequest<PageData<Product | Category | Brand>>(result => {
  total.value = result.total
  if (tab.value === 'products') products.value = result.items as Product[]
  else dictionaries.value = result.items as (Category | Brand)[]
  loading.value = false
}, reason => { error.value = reason instanceof Error ? reason.message : '加载失败'; loading.value = false })
async function load() {
  if (!allowed.value) return
  keyword.value = String(route.query.q ?? '')
  loading.value = true; error.value = ''; detail.value = null; detailError.value = ''; detailLoading.value = false; detailSerial++
  await publish(() => api.request(`/admin/${tab.value}?${new URLSearchParams({ page: String(page.value), size: '20', keyword: keyword.value })}`))
}
async function loadDictionaries() {
  if (!allowed.value) return
  dictionaryLoading.value = true
  try {
    async function all<T>(path: string): Promise<T[]> {
      const items: T[] = []
      for (let n = 1; ; n++) {
        const result = await api.request<PageData<T>>(`/admin/${path}?page=${n}&size=100`)
        items.push(...result.items)
        if (items.length >= result.total) return items
        if (!result.items.length) throw new Error('字典响应不完整')
      }
    }
    [categories.value, brands.value] = await Promise.all([all<Category>('categories'), all<Brand>('brands')])
    dictionaryError.value = ''
  } catch { dictionaryError.value = '分类或品牌名称加载失败，表格暂时显示对应 ID。' }
  finally { dictionaryLoading.value = false }
}
function navigate(next: Record<string, string | undefined>) { void router.push({ query: { ...route.query, ...next } }) }
function search() { navigate({ q: keyword.value.trim() || undefined, page: undefined }) }
function price(item: Product) {
  const enabled = item.skus.filter(sku => sku.enabled)
  return enabled.length ? formatMinor(enabled.reduce((min, sku) => BigInt(sku.priceMinor) < BigInt(min) ? sku.priceMinor : min, enabled[0]!.priceMinor)) : '暂无启用 SKU'
}
async function open(id: string) {
  const serial = ++detailSerial
  detail.value = null; detailError.value = ''; detailLoading.value = true
  await nextTick(); if (!detailElement.value?.open) detailElement.value?.showModal()
  try { const item = await api.request<Product>(`/admin/products/${encodeURIComponent(id)}`); if (serial === detailSerial) detail.value = item }
  catch (reason) { if (serial === detailSerial) detailError.value = reason instanceof Error ? reason.message : '详情加载失败' }
  finally { if (serial === detailSerial) detailLoading.value = false }
}
function closeDetail() { detailSerial++; detail.value = null; detailLoading.value = false; detailError.value = '' }
function brandSaved() { brandEditor.value = null; notify('品牌已保存'); void load(); void loadDictionaries() }
function closeBrandEditor() { brandEditor.value = null; void load(); void loadDictionaries() }
function openCategory(item: Category | Brand) { if ('sort' in item) categoryEditor.value = { category: item } }
function categorySaved() { categoryEditor.value = null; notify('分类已保存'); void load(); void loadDictionaries() }
function closeCategoryEditor() { categoryEditor.value = null; void load(); void loadDictionaries() }
async function refreshProduct(id?: string) { detailElement.value?.close(); await load(); if (id) await open(id) }
function productSaved(item: Product) { productEditor.value = null; stateEditor.value = null; notify('商品已保存'); void refreshProduct(item.id) }
function closeProductEditor() { const id = productEditor.value?.product?.id; productEditor.value = null; void refreshProduct(id) }
function skuSaved(item: Sku) { skuEditor.value = null; notify('SKU已保存'); void refreshProduct(item.productId) }
function closeSkuEditor() { const id = skuEditor.value?.productId; skuEditor.value = null; void refreshProduct(id) }
function closeStateEditor() { const id = stateEditor.value?.product.id; stateEditor.value = null; void refreshProduct(id) }
watch(() => route.fullPath, load, { immediate: true })
void loadDictionaries()
</script>

<template>
  <main class="shell catalog-admin">
    <div class="admin-layout"><AdminSidebar /><section class="admin-main">
      <div class="admin-top"><div><h1>商品目录</h1><p>维护商品、分类、品牌和 SKU；商品先存为草稿，配置图片与规格后上架。</p></div></div>
      <p v-if="!allowed" role="alert">当前员工没有商品目录读取权限。</p>
      <template v-else>
        <div class="admin-card catalog-table-panel">
          <p v-if="dictionaryError" role="status">{{ dictionaryError }} <button class="ghost-btn" @click="loadDictionaries">重试名称加载</button></p>
          <div class="catalog-admin-tabs" role="group" aria-label="目录类型"><button v-for="(label, key) in names" :key="key" :class="{ active: tab === key }" @click="navigate({ tab: key, page: undefined, q: undefined })">{{ label }}</button></div>
          <form class="catalog-admin-search" @submit.prevent="search"><input v-model="keyword" :aria-label="`搜索${names[tab]}`" :placeholder="`搜索${names[tab]}名称`"><button class="admin-primary">搜索</button><button type="button" class="ghost-btn" @click="keyword = ''; search()">重置</button></form>
          <button v-if="tab === 'brands' && canWrite" class="admin-primary" @click="brandEditor = { brand: null }">新增品牌</button>
          <button v-if="tab === 'categories' && canWrite" class="admin-primary" :disabled="dictionaryLoading || !!dictionaryError" @click="categoryEditor = { category: null }">新增分类</button>
          <button v-if="tab === 'products' && canWrite" class="admin-primary" :disabled="dictionaryLoading || !!dictionaryError" @click="productEditor = { product: null }">新增商品</button>
          <p v-if="loading" role="status">正在查询…</p>
          <p v-else-if="error" role="alert">{{ error }} <button class="ghost-btn" @click="load">重试</button></p>
          <template v-else>
            <p class="catalog-admin-count">共 {{ total }} 条 · 第 {{ page }} 页</p>
            <div class="catalog-admin-table-wrap"><table class="catalog-admin-table"><thead><tr><th>ID</th><th>名称</th><template v-if="tab === 'products'"><th>分类 / 品牌</th><th>状态</th><th>最低启用价格</th><th>SKU</th><th>查看</th></template><template v-else><template v-if="tab === 'categories'"><th>父级分类</th><th>排序</th></template><th>状态</th><th>版本</th><th v-if="canWrite">操作</th></template></tr></thead>
              <tbody v-if="tab === 'products'"><tr v-for="item in products" :key="item.id"><td>{{ item.id }}</td><td class="catalog-admin-product"><img v-if="item.images[0]" :src="item.images[0].url" :alt="item.name" loading="lazy"><span>{{ item.name }}</span></td><td>{{ categories.find(c => c.id === item.categoryId)?.name ?? item.categoryId }} / {{ brands.find(b => b.id === item.brandId)?.name ?? item.brandId }}</td><td>{{ item.state === 'ON_SALE' ? '已上架' : item.state === 'DRAFT' ? '草稿' : '已下架' }}</td><td>{{ price(item) }}</td><td>{{ item.skus.length }}</td><td><button class="ghost-btn" @click="open(item.id)">详情</button></td></tr></tbody>
              <tbody v-else><tr v-for="item in dictionaries" :key="item.id"><td>{{ item.id }}</td><td>{{ item.name }}</td><template v-if="tab === 'categories'"><td>{{ (item as Category).parentId ? categories.find(c => c.id === (item as Category).parentId)?.name ?? (item as Category).parentId : '一级分类' }}</td><td>{{ (item as Category).sort }}</td></template><td>{{ item.enabled ? '启用' : '停用' }}</td><td>{{ item.version }}</td><td v-if="canWrite"><button v-if="tab === 'brands'" class="ghost-btn" @click="brandEditor = { brand: item }">编辑品牌</button><button v-else class="ghost-btn" :disabled="dictionaryLoading || !!dictionaryError" @click="openCategory(item)">编辑分类</button></td></tr></tbody>
            </table></div>
            <p v-if="!total">没有匹配的记录。</p>
            <div class="catalog-admin-pagination"><button class="ghost-btn" :disabled="page <= 1" @click="navigate({ page: String(page - 1) })">上一页</button><span>{{ page }} / {{ Math.max(1, pages) }}</span><button class="ghost-btn" :disabled="page >= pages" @click="navigate({ page: String(page + 1) })">下一页</button></div>
          </template>
        </div>
        <dialog v-if="detailLoading || detailError || detail" ref="detailElement" class="admin-card catalog-detail" aria-label="商品详情" aria-live="polite" @close="closeDetail">
          <button class="ghost-btn" autofocus @click="detailElement?.close()">关闭详情</button>
          <p v-if="detailLoading">正在加载详情…</p><p v-else-if="detailError" role="alert">{{ detailError }}</p>
          <template v-else-if="detail"><h2>{{ detail.name }}</h2><p>{{ detail.description }}</p><p>商品 ID {{ detail.id }} · {{ detail.state }} · 版本 {{ detail.version }}</p>
            <div v-if="canWrite" class="catalog-editor-actions"><button class="ghost-btn" :disabled="dictionaryLoading || !!dictionaryError" @click="productEditor = { product: detail }">编辑商品</button><button class="ghost-btn" @click="skuEditor = { productId: detail.id, sku: null }">新增SKU</button><button class="admin-primary" @click="stateEditor = { product: detail, state: detail.state === 'ON_SALE' ? 'OFF_SALE' : 'ON_SALE' }">{{ detail.state === 'ON_SALE' ? '下架商品' : '上架商品' }}</button></div>
            <h3>全部 SKU（含禁用）</h3><ul><li v-for="sku in detail.skus" :key="sku.id">{{ sku.code }} · {{ sku.specs.map(s => `${s.name}：${s.value}`).join(' / ') }} · {{ formatMinor(sku.priceMinor) }} · {{ sku.enabled ? '启用' : '禁用' }} · 版本 {{ sku.version }} <button v-if="canWrite" class="ghost-btn" @click="skuEditor = { productId: detail.id, sku }">编辑SKU</button></li></ul><p v-if="!detail.skus.length">尚无 SKU。添加启用规格后才能上架。</p></template>
        </dialog>
        <BrandEditor v-if="brandEditor" :brand="brandEditor.brand" @close="closeBrandEditor" @saved="brandSaved" />
        <CategoryEditor v-if="categoryEditor" :category="categoryEditor.category" :categories="categories" @close="closeCategoryEditor" @saved="categorySaved" />
        <ProductEditor v-if="productEditor" :product="productEditor.product" :categories="categories" :brands="brands" @close="closeProductEditor" @saved="productSaved" />
        <SkuEditor v-if="skuEditor" :product-id="skuEditor.productId" :sku="skuEditor.sku" @close="closeSkuEditor" @saved="skuSaved" />
        <ProductStateEditor v-if="stateEditor" :product="stateEditor.product" :state="stateEditor.state" @close="closeStateEditor" @saved="productSaved" />
      </template>
    </section></div>
  </main>
</template>

<style scoped>
.catalog-admin{padding:28px 0 60px}.catalog-table-panel,.catalog-detail{padding:24px}.catalog-admin-tabs,.catalog-admin-search,.catalog-admin-pagination{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.catalog-admin-tabs button{padding:12px 20px;background:#eff3f7;color:#42576d;border-radius:6px}.catalog-admin-tabs .active{background:#163f56;color:white}.catalog-admin-search{margin:22px 0}.catalog-admin-search input{padding:12px;border:1px solid #cbd5df;border-radius:6px;color:#263c50;background:white;min-width:200px}.catalog-admin-table-wrap{overflow-x:auto}.catalog-admin-table{border-collapse:collapse;width:100%;min-width:760px;color:#30485e;font-size:14px}.catalog-admin-table th,.catalog-admin-table td{padding:13px 10px;text-align:left;border-bottom:1px solid #e3e9ef}.catalog-admin-table th{background:#f2f5f8;white-space:nowrap}.catalog-admin-product{display:flex;align-items:center;gap:12px;min-width:260px}.catalog-admin-product img{width:60px;height:60px;object-fit:contain;background:#f4f6f8}.catalog-admin-pagination{justify-content:flex-end;margin-top:24px}.catalog-admin-count{color:#5f7386}.catalog-detail{margin-top:24px;color:#30485e;overflow-wrap:anywhere}.catalog-detail li{line-height:2}.catalog-admin button:disabled{opacity:.5;cursor:default}.catalog-admin input:focus-visible,.catalog-admin button:focus-visible{outline:3px solid #216f8f;outline-offset:3px}@media(max-width:600px){.catalog-table-panel,.catalog-detail{padding:16px}.catalog-admin-search input{width:100%}}
</style>
<style scoped>.catalog-detail{width:min(800px,90vw);max-height:85svh;overflow:auto;margin:auto;border:1px solid #dbe4ed;border-radius:12px;box-sizing:border-box}.catalog-detail::backdrop{background:rgba(15,35,50,.5)}</style>
