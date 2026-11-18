<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted, watch } from 'vue'
import { isNavigationFailure, useRoute, useRouter } from 'vue-router'
import { api, account } from './session'
import { useDemo } from './demo'
import { notify } from '@zhicai/shared/notifications.ts'
import './styles/storefront.css'
import './styles/customer.css'
const { searchQuery } = useDemo(), router=useRouter(),route=useRoute(),notice=ref(''),loggingOut=ref(false)
const refreshed = computed(() => route.path === '/' || route.path === '/products' || route.path === '/featured' || route.path.startsWith('/products/') || route.path === '/login' || route.path.startsWith('/account') || route.path === '/cart' || route.path === '/checkout')
let timer: ReturnType<typeof setTimeout>
watch(() => [route.path, route.query.q ?? route.query.keyword] as const, ([path, raw], previous) => {
  if (path !== '/products') return
  const keyword = raw === undefined ? undefined : String(raw)
  const previousKeyword = previous?.[1] === undefined ? undefined : String(previous[1])
  if (keyword === previousKeyword && (previous?.[0] === '/products' || keyword === undefined)) return
  searchQuery.value = keyword ?? ''
}, { immediate: true })
function display(event: Event) { notice.value=(event as CustomEvent<string>).detail; clearTimeout(timer); timer=setTimeout(()=>notice.value='',4000) }
onMounted(()=>{document.body.classList.remove('admin-mode');window.addEventListener('zhicai-notice',display)})
onUnmounted(()=>{clearTimeout(timer);window.removeEventListener('zhicai-notice',display)})
async function logout() {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    if (route.path !== '/' && isNavigationFailure(await router.push('/'))) return
    try { await api.logout() } catch { notify('本地已退出，但未能确认服务端撤销，请稍后重试或等待会话到期') }
    account.value = null
  } finally { loggingOut.value = false }
}
</script>
<template>
  <div class="storefront-app" :class="{ 'fresh-storefront': refreshed }">
  <div v-if="!refreshed" class="topline"></div>
  <header><div class="shell nav">
    <RouterLink class="brand" to="/"><span class="logo">Z</span><span>智采商城<small v-if="!refreshed">SMART SUPPLY · DIGITAL COMMERCE</small></span></RouterLink>
    <nav class="navlinks" aria-label="商城导航"><RouterLink to="/" exact-active-class="active">首页</RouterLink><RouterLink to="/products" active-class="active">全部商品</RouterLink><RouterLink to="/featured" active-class="active">硬件精选</RouterLink></nav>
    <label class="search"><span>⌕</span><input v-model="searchQuery" aria-label="搜索商品" placeholder="搜索显卡、处理器、外设…" @keydown.enter="router.push({path:'/products',query:{q:searchQuery||undefined}})"><span>↵</span></label>
    <div class="session-actions"><RouterLink to="/cart">购物车</RouterLink><template v-if="account"><RouterLink class="account-label" to="/account"><span class="account-label-desktop">{{ account.displayName }}</span><span class="account-label-mobile">我的账户</span></RouterLink><button :disabled="loggingOut" @click="logout">退出</button></template><RouterLink v-else to="/login">登录</RouterLink><a href="http://127.0.0.1:5174/admin">管理端 ↗</a></div>
  </div></header>
  <div v-if="!refreshed" class="demo-ribbon">商品目录、地址与购物车已接入；结算写入默认关闭，支付尚未接入</div>
  <RouterView />
  <div v-if="notice" class="app-notice" role="status" aria-live="polite">{{ notice }}</div>
  </div>
</template>
