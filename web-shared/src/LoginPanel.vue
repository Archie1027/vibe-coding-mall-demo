<script setup lang="ts">
import { ref } from 'vue'
import type { Account, createApiClient } from './api'
import { ApiError } from './api'
import { validLoginPassword, validLoginUsername } from './login-policy'
const props = defineProps<{ kind: Account['accountType']; client: ReturnType<typeof createApiClient> }>()
const emit = defineEmits<{ authenticated: [Account] }>()
const username = ref(''), password = ref(''), busy = ref(false), error = ref('')
async function login() {
  if (busy.value) return
  error.value = ''
  if (!validLoginUsername(username.value)) {
    error.value = '请输入用户名（最多50个字符，支持中文、英文或混合使用）'
    return
  }
  if (!validLoginPassword(password.value)) {
    error.value = '密码须为1–72位英文字母、数字或英文符号，不支持中文'
    return
  }
  busy.value = true
  try {
    await props.client.login(username.value, password.value)
    const account = await props.client.request<Account>('/me')
    if (!account.enabled || account.accountType !== props.kind) {
      props.client.clearSession()
      throw new Error('账户类型不匹配，请使用对应的登录入口')
    }
    password.value = ''
    emit('authenticated', account)
  } catch (e) {
    props.client.clearSession()
    const message = e instanceof ApiError && e.status === 401 && e.code === 'UNAUTHENTICATED'
      ? '用户名或密码不正确，请确认账号及登录入口。'
      : e instanceof Error ? e.message : '登录未完成，请重试'
    error.value = `${message}${e instanceof ApiError && e.traceId ? `（请求编号：${e.traceId}）` : ''}`
  } finally { busy.value = false }
}
</script>
<template>
  <main class="login-page">
    <section class="login-panel" aria-labelledby="login-title">
      <div class="login-brand"><span class="logo">Z</span><span>智采商城</span></div>
      <p class="eyebrow">{{ kind === 'STAFF' ? 'ENTERPRISE WORKSPACE' : 'SMART SUPPLY · DIGITAL COMMERCE' }}</p>
      <h1 id="login-title">{{ kind === 'STAFF' ? '登录管理工作台' : '欢迎回到智采商城' }}</h1>
      <p class="login-intro">{{ kind === 'STAFF' ? '请使用管理员创建的员工账户。' : '请使用顾客账户登录。注册功能将在后续开放。' }}</p>
      <form @submit.prevent="login">
        <label for="username">用户名</label>
        <input id="username" v-model="username" name="username" autocomplete="username" placeholder="中文、英文或中英文混合" required :disabled="busy">
        <label for="password">密码</label>
        <input id="password" v-model="password" name="password" type="password" autocomplete="current-password" required :disabled="busy">
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <button class="btn btn-primary" type="submit" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</button>
      </form>
      <p class="login-note">请使用已创建的账号，输入新用户名不会自动注册。刷新页面后需要重新登录。请勿在共享电脑上保存密码。</p>
      <RouterLink v-if="kind === 'CUSTOMER'" to="/">暂不登录，继续浏览 →</RouterLink>
    </section>
  </main>
</template>
