<script setup lang="ts">
import { account } from '../session'
</script>
<template>
<main v-if="account" class="customer-page shell">
  <nav class="customer-breadcrumb" aria-label="当前位置"><RouterLink to="/">首页</RouterLink><span>/</span><span>我的账户</span></nav>
  <div class="customer-heading"><div><h1>我的账户</h1><p>{{ account.displayName }}，在这里管理收货信息、购物车和订单记录。</p></div><RouterLink class="customer-link" to="/products">继续选购 →</RouterLink></div>
  <nav class="customer-tabs" aria-label="账户导航"><RouterLink class="active" to="/account">账户信息</RouterLink><RouterLink to="/account/addresses">收货地址</RouterLink><RouterLink to="/cart">购物车</RouterLink><RouterLink to="/account/orders">我的订单</RouterLink></nav>
  <section class="customer-sheet account-info"><h2>账户信息</h2><dl><div><dt>用户名</dt><dd>{{ account.username }}</dd></div><div><dt>显示名称</dt><dd>{{ account.displayName }}</dd></div><div><dt>账户类型</dt><dd>顾客</dd></div><div><dt>账户状态</dt><dd>{{ account.enabled ? '正常' : '已停用' }}</dd></div></dl></section>
  <div class="account-destinations"><RouterLink to="/account/addresses"><h2>收货地址</h2><p>新增、编辑常用地址，设置默认地址。</p><span>管理地址 →</span></RouterLink><RouterLink to="/cart"><h2>购物车</h2><p>查看已保存的商品规格、数量与当前价格。</p><span>查看购物车 →</span></RouterLink><RouterLink to="/account/orders"><h2>我的订单</h2><p>查看订单状态、历史商品和收货快照。</p><span>查看订单 →</span></RouterLink></div>
</main>
</template>
