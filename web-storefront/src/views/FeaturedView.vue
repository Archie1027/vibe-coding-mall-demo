<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { api } from '../session'
import ProductCard from '../components/ProductCard.vue'
import { createCatalogApi, toCatalogCard, type CatalogCard, type Category, type Product } from '@zhicai/shared/catalog-api.ts'
import '../styles/featured.css'

interface FeaturedCategory { category: Category; total: number; cards: CatalogCard[] }
const catalog = createCatalogApi(api.request)
const sections = ref<FeaturedCategory[]>([]), loading = ref(true), error = ref('')
const showBackToTop = ref(false)
function updateScrollPosition() { showBackToTop.value = window.scrollY > 600 }
function backToTop() {
  window.scrollTo({ top: 0, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' })
}
const heroCards = computed(() => ['101', '110', '115'].map(id => sections.value.find(section => section.category.id === id)?.cards[0]).filter((card): card is CatalogCard => Boolean(card?.image)))

function chooseProducts(products: Product[]): Product[] {
  const regular = products.filter(item => !item.name.includes('（开发测试 '))
  const testData = products.filter(item => item.name.includes('（开发测试 '))
  const selected: Product[] = [], brands = new Set<string>()
  for (const item of [...regular.filter(item => item.featured), ...regular]) {
    if (selected.length === 3) break
    if (!brands.has(item.brandId) && !selected.some(picked => picked.id === item.id)) {
      selected.push(item); brands.add(item.brandId)
    }
  }
  for (const item of [...regular, ...testData]) {
    if (selected.length === 3) break
    if (!selected.some(picked => picked.id === item.id)) selected.push(item)
  }
  return selected
}

async function load() {
  loading.value = true; error.value = ''
  try {
    const [categories, brands] = await Promise.all([catalog.allCategories(), catalog.allBrands()])
    const topLevel = categories.filter(item => !item.parentId && item.enabled)
    sections.value = await Promise.all(topLevel.map(async category => {
      const page = await catalog.products({ page: 1, size: 20, categoryId: category.id, sort: 'NEWEST' })
      return { category, total: page.total, cards: chooseProducts(page.items).map(item => toCatalogCard(item, categories, brands)) }
    }))
  } catch (reason) {
    sections.value = []
    error.value = reason instanceof Error ? reason.message : '精选商品加载失败'
  } finally {
    loading.value = false
    await nextTick()
    window.dispatchEvent(new CustomEvent('zhicai-content-ready', { detail: '/featured' }))
  }
}
onMounted(() => {
  updateScrollPosition()
  window.addEventListener('scroll', updateScrollPosition, { passive: true })
  void load()
})
onUnmounted(() => window.removeEventListener('scroll', updateScrollPosition))
</script>

<template>
  <main class="featured-page">
    <header class="featured-intro">
      <div class="featured-intro-copy">
        <p class="featured-eyebrow">ZHICAI / CURATED CATALOG</p>
        <h1>每个品类，<br>先看这三件。</h1>
        <p>从显卡、外设到手机和平板，按品类挑选商品。图片、型号与价格来自当前开发库；标有“开发测试”的商品用于目录测试。</p>
        <a href="#featured-categories">按品类浏览 <span aria-hidden="true">↓</span></a>
      </div>
      <div v-if="heroCards.length" class="featured-hero-art" aria-label="精选商品图片">
        <RouterLink v-for="card in heroCards" :key="card.id" :to="`/products/${card.id}`" :aria-label="`查看${card.name}`"><img :src="card.image" :alt="card.name" decoding="async"></RouterLink>
      </div>
    </header>

    <div v-if="loading" class="featured-state" role="status">正在整理各品类商品…</div>
    <div v-else-if="error" class="featured-state" role="alert">{{ error }} <button type="button" @click="load">重试</button></div>
    <template v-else>
      <nav id="featured-categories" class="featured-index" aria-label="精选品类">
        <span>快速跳转</span>
        <a v-for="section in sections" :key="section.category.id" :href="`#featured-${section.category.id}`">{{ section.category.name }}</a>
      </nav>
      <section v-for="(section, index) in sections" :id="`featured-${section.category.id}`" :key="section.category.id" class="featured-section" :aria-labelledby="`featured-title-${section.category.id}`">
        <div class="featured-section-heading">
          <div><span class="featured-number">{{ String(index + 1).padStart(2, '0') }} / {{ String(sections.length).padStart(2, '0') }}</span><h2 :id="`featured-title-${section.category.id}`">{{ section.category.name }}</h2><p>从这个品类的 {{ section.total }} 件商品中继续挑选。</p></div>
          <RouterLink :to="{ path: '/products', query: { category: section.category.id } }">查看全部{{ section.category.name }} <span aria-hidden="true">→</span></RouterLink>
        </div>
        <div class="featured-grid"><ProductCard v-for="product in section.cards" :key="product.id" :product="product" /></div>
        <p v-if="section.cards.length === 0" class="featured-empty">这个品类暂无可展示的商品。</p>
      </section>
    </template>
    <button v-show="showBackToTop" class="featured-back-to-top" type="button" @click="backToTop"><span aria-hidden="true">↑</span>返回顶部</button>
  </main>
</template>
