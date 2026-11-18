<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import LoginPanel from '@zhicai/shared/LoginPanel.vue'
import { api, account } from '../session'
import type { Account } from '@zhicai/shared/api.ts'
const route=useRoute(),router=useRouter()
function authenticated(value: Account) { account.value=value; const redirect=String(route.query.redirect||'/admin'); void router.replace(redirect==='/admin'||redirect.startsWith('/admin/') ? redirect : '/admin') }
</script>
<template><LoginPanel kind="STAFF" :client="api" @authenticated="authenticated" /></template>
