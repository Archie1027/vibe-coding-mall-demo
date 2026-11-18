<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { api, account } from './session'
import { notify } from '@zhicai/shared/notifications.ts'
const router=useRouter(),notice=ref(''),loggingOut=ref(false)
let timer: ReturnType<typeof setTimeout>
function display(event: Event){notice.value=(event as CustomEvent<string>).detail;clearTimeout(timer);timer=setTimeout(()=>notice.value='',4000)}
onMounted(()=>{document.body.classList.add('admin-mode');window.addEventListener('zhicai-notice',display)})
onUnmounted(()=>{clearTimeout(timer);window.removeEventListener('zhicai-notice',display)})
async function logout(){if(loggingOut.value)return;loggingOut.value=true;try{await api.logout()}catch{notify('本地已退出，但服务端撤销结果尚未确认')}finally{account.value=null;loggingOut.value=false;void router.push('/admin/login')}}
</script>
<template>
  <header class="admin-app-header"><RouterLink to="/admin" class="brand-label"><span class="logo">Z</span>智采商城 · 企业管理端</RouterLink><div class="session-actions"><ElTag v-if="account" class="account-label" type="success">{{ account.displayName }}</ElTag><ElButton v-if="account" :loading="loggingOut" @click="logout">退出登录</ElButton><a href="http://127.0.0.1:5173/">前往商城 ↗</a></div></header>
  <div class="demo-ribbon">订单、采购与报表内容为演示数据，相关业务操作尚未开放</div>
  <nav v-if="account" class="admin-mobile-nav" aria-label="管理端导航"><RouterLink to="/admin">工作台</RouterLink><RouterLink to="/admin/products">商品目录</RouterLink><RouterLink to="/admin/orders">订单管理</RouterLink><RouterLink to="/admin/purchases/new">新增采购单</RouterLink></nav>
  <RouterView /><div v-if="notice" class="app-notice" role="status" aria-live="polite">{{ notice }}</div>
</template>
