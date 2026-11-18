<script setup lang="ts">
import { notify } from '@zhicai/shared/notifications.ts'
import { formatMinor } from '@zhicai/shared/money.ts'
import type { CatalogCard } from '@zhicai/shared/catalog-api.ts'

defineProps<{ product: CatalogCard; showRating?: boolean }>()
</script>
<template>
  <article class="product catalog-product">
    <div class="product-art">
      <span v-if="product.featured" class="tag">精选</span>
      <button class="wish" :aria-label="`收藏${product.name}`" @click="notify('收藏功能尚未开放')">♡</button>
      <img v-if="product.image" class="product-photo" :src="product.image" :alt="product.name" loading="lazy" decoding="async">
      <span v-else class="subtle-note">暂无商品图片</span>
    </div>
    <div class="product-body">
      <span class="product-brand">{{ product.brandName }}</span><h3>{{ product.name }}</h3>
      <div v-if="showRating" class="listing-rating"><span>{{ product.ratingCount ? `★ ${product.ratingAverage.toFixed(2)} · ${product.ratingCount} 条评价` : '暂无公开评价' }}</span></div>
      <RouterLink class="detail-open" :to="`/products/${product.id}`">查看商品详情 ↗</RouterLink>
      <div class="price-row"><div class="price">{{ formatMinor(product.priceMinor) }}<small> 起</small></div><button class="add" :aria-label="`将${product.name}加入购物车`" @click="notify('请查看商品详情，选择规格并登录后加入购物车')">＋</button></div>
    </div>
  </article>
</template>
