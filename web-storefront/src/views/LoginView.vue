<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import LoginPanel from '@zhicai/shared/LoginPanel.vue'
import { api, account } from '../session'
import type { Account } from '@zhicai/shared/api.ts'
const route=useRoute(),router=useRouter()
function authenticated(value: Account) { account.value=value; const redirect=String(route.query.redirect||'/account'); void router.replace(redirect.startsWith('/')&&!redirect.startsWith('//') ? redirect : '/account') }
</script>
<template>
  <div class="customer-login-shell">
    <section class="customer-login-story" aria-label="智采商城">
      <div><h2>好装备，随时接着挑。</h2><p>登录智采商城，继续探索电脑、手机和更多数码好物。</p></div>
      <span>原型极光摄影 · 智采商城视觉素材</span>
    </section>
    <LoginPanel kind="CUSTOMER" :client="api" @authenticated="authenticated" />
  </div>
</template>
