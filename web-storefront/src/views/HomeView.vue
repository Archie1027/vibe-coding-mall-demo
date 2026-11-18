<script setup lang="ts">
import { computed, nextTick, ref, watch, onMounted } from 'vue'
import { useDemo } from '../demo'
import { api } from '../session'
import ProductCard from '../components/ProductCard.vue'
import { formatMinor } from '@zhicai/shared/money.ts'
import '../styles/home.css'
import { createCatalogApi, toCatalogCard, latestRequest, type Category, type Brand, type CatalogCard } from '@zhicai/shared/catalog-api.ts'

const { searchQuery } = useDemo()
const catalog = createCatalogApi(api.request)
const categories = ref<Category[]>([]), brands = ref<Brand[]>([]), categoryId = ref('')
const cards = ref<CatalogCard[]>([]), loading = ref(true), error = ref('')
const gallery = ref<CatalogCard[]>([]), selectedId = ref('')
const selectedProduct = computed(() => gallery.value.find(item => item.id === selectedId.value) ?? gallery.value[0])
const showcase = computed(() => gallery.value.length > 3 ? gallery.value.slice(3, 6) : gallery.value.slice(0, 3))
const productsElement = ref<HTMLElement | null>(null), productsMinHeight = ref(0)
const loadCards = latestRequest<{ cards: CatalogCard[]; categories: Category[]; brands: Brand[] }>(value => {
  cards.value = value.cards; categories.value = value.categories; brands.value = value.brands
  error.value = ''; loading.value = false
}, reason => { cards.value = []; error.value = reason instanceof Error ? reason.message : '商品加载失败'; loading.value = false })
async function load() {
  productsMinHeight.value = Math.max(0, window.innerHeight - (productsElement.value?.getBoundingClientRect().top ?? 0))
  loading.value = true
  await loadCards(async () => {
    const [nextCategories, nextBrands] = await Promise.all([catalog.allCategories(), catalog.allBrands()])
    const [page, latest] = await Promise.all([
      catalog.products({ page: 1, size: 4, featured: true, keyword: searchQuery.value.trim() || undefined, categoryId: categoryId.value || undefined }),
      catalog.products({ page: 1, size: 2 }),
    ])
    if (!gallery.value.length) {
      gallery.value = [...new Map([...page.items, ...latest.items].map(item => [item.id, toCatalogCard(item, nextCategories, nextBrands)])).values()]
    }
    return { cards: page.items.map(item => toCatalogCard(item, nextCategories, nextBrands)), categories: nextCategories, brands: nextBrands }
  })
  await nextTick()
  window.dispatchEvent(new CustomEvent('zhicai-content-ready', { detail: '/' }))
}
onMounted(load)
watch([searchQuery, categoryId], () => { void load() })
</script>
<template>
  <div id="storeView" class="zhicai-home">
    <main>
      <section id="home" class="home-hero" aria-label="智采商城">
        <img class="home-aurora" src="/assets/aurora-hero.png" alt="夜空中延展的绿色极光" fetchpriority="high">
        <div class="home-hero-content">
          <h1>挑数码装备，<br>来智采商城。</h1>
          <p>电脑、手机、外设与更多数码好物。看清图片、规格和价格，再挑你真正想要的那一件。</p>
          <RouterLink class="home-primary-link" to="/products">浏览全部商品 <span aria-hidden="true">→</span></RouterLink>
        </div>
        <p class="home-photo-credit">原型极光摄影 · 智采商城视觉素材</p>
      </section>

      <section class="home-gallery" aria-labelledby="gallery-title">
        <div class="home-section-heading">
          <h2 id="gallery-title">真实商品，放在眼前。</h2>
        </div>
        <div v-if="selectedProduct" class="home-gallery-layout">
          <div class="home-gallery-stage">
            <Transition name="home-image" mode="out-in"><img v-if="selectedProduct.image" :key="selectedProduct.id" :src="selectedProduct.image" :alt="selectedProduct.name" decoding="async"><div v-else class="home-image-empty">暂无商品图片</div></Transition>
          </div>
          <div class="home-gallery-aside">
            <div class="home-gallery-current">
              <h3>{{ selectedProduct.name }}</h3>
              <p>{{ selectedProduct.brandName }} · {{ selectedProduct.categoryName }}</p>
              <span>{{ formatMinor(selectedProduct.priceMinor) }} 起</span>
              <RouterLink :to="'/products/' + selectedProduct.id">了解这件商品 <span aria-hidden="true">→</span></RouterLink>
            </div>
            <div class="home-gallery-choices" role="group" aria-label="切换展示商品">
              <button v-for="item in gallery" :key="item.id" type="button" :aria-label="'展示' + item.name" :aria-pressed="selectedProduct.id === item.id" @click="selectedId = item.id">
                <img v-if="item.image" :src="item.image" :alt="item.name" loading="lazy" decoding="async">
                <span v-else>暂无图片</span>
              </button>
            </div>
          </div>
        </div>
        <p v-else-if="loading" class="home-data-state" role="status">正在加载商品图片…</p>
        <p v-else-if="error" class="home-data-state" role="alert">商品暂时无法展示。{{ error }}</p>
      </section>

      <section class="home-collections" aria-labelledby="collections-title">
        <div class="home-section-heading">
          <h2 id="collections-title">换个角度，看看装备。</h2>
          <p>无论从性能、工作还是日常使用出发，都能在目录里找到对应商品。</p>
        </div>
        <div class="home-collection-images">
          <RouterLink v-for="(item, index) in showcase" :key="item.id" :to="'/products/' + item.id" class="home-collection-item" :class="'home-collection-item-' + index">
            <span class="home-collection-photo"><img v-if="item.image" :src="item.image" :alt="item.name" loading="lazy" decoding="async"><span v-else>暂无商品图片</span></span>
            <span class="home-collection-name">{{ item.name }}</span>
            <span class="home-collection-link">查看详情 <span aria-hidden="true">→</span></span>
          </RouterLink>
        </div>
      </section>

      <section id="products" ref="productsElement" class="home-products" :style="{ minHeight: productsMinHeight + 'px' }" aria-labelledby="products-title">
        <div class="home-products-heading">
          <div>
            <h2 id="products-title">按你的兴趣继续找。</h2>
            <p>精选商品来自当前开发库。筛选结果会在这里更新。</p>
          </div>
          <RouterLink to="/products">前往完整商品列表 <span aria-hidden="true">→</span></RouterLink>
        </div>
        <div class="home-category-filter" role="group" aria-label="精选商品分类">
          <button type="button" :aria-pressed="!categoryId" @click="categoryId = ''">全部</button>
          <button v-for="item in categories" :key="item.id" type="button" :aria-pressed="categoryId === item.id" @click="categoryId = item.id">{{ item.name }}</button>
        </div>
        <div v-if="!loading && !error && cards.length" id="productGrid" class="home-product-grid">
          <ProductCard v-for="product in cards" :key="product.id" :product="product" />
        </div>
        <p v-if="loading" class="home-data-state" role="status">正在加载精选商品…</p>
        <div v-else-if="error" class="home-data-state" role="alert">{{ error }} <button type="button" @click="load">重试</button></div>
        <p v-else-if="cards.length === 0" id="emptySearch" class="home-data-state">没有找到匹配的精选商品。试试其他分类，或查看完整商品列表。</p>
      </section>

      <section id="categories" class="home-categories" aria-labelledby="categories-title">
        <div class="home-section-heading">
          <h2 id="categories-title">不知道选什么？先选品类。</h2>
          <p>从电脑硬件到手机、平板，沿着用途继续探索。</p>
        </div>
        <div class="home-category-links">
          <RouterLink v-for="item in categories" :key="item.id" :to="{ path: '/products', query: { category: item.id } }">{{ item.name }} <span aria-hidden="true">→</span></RouterLink>
        </div>
      </section>
    </main>
    <footer class="home-footer">
      <div class="home-footer-inner">
        <strong>智采商城</strong>
        <p>看见喜欢的装备，也看清它的资料。</p>
        <div><RouterLink to="/products">全部商品</RouterLink><RouterLink to="/login">顾客登录</RouterLink></div>
        <small>登录后可管理地址与购物车；库存查询与交易功能尚未开放。© 2026 智采商城</small>
      </div>
    </footer>
  </div>
</template>
