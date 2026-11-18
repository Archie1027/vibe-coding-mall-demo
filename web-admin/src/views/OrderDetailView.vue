<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDemo } from '../demo'
import AdminSidebar from '../components/AdminSidebar.vue'
import { demoProducts } from '@zhicai/shared/demo-products.ts'
const gpu = demoProducts.find(item => item.id === '2001')!
const keyboard = demoProducts.find(item => item.id === '2004')!
const { showPage, toast, addCart, searchQuery } = useDemo()
const route = useRoute()
const returnToOrders = computed(() => ({ path: '/admin/orders', query: route.query }))

</script>
<template>
<div v-if="route.params.id === '31'" id="adminOrderDetailView" class="">
  <main class="shell" style="padding-top:28px;padding-bottom:45px">
<div class="announcement">
<span>
<strong>智采商城 · 企业管理端</strong>　订单履约与售后管理</span>
<span>开发演示 · 商品与业务单据均为示例</span>
</div>
<div class="admin-layout">
    <admin-sidebar>
</admin-sidebar>
    <section class="admin-main">
<div class="admin-top">
<div>
<div class="breadcrumbs admin-breadcrumbs">
<a href="#" v-on:click.prevent="showPage('admin')">工作台</a>
<span>›</span>
<RouterLink :to="returnToOrders">订单管理</RouterLink>
<span>›</span>
<strong>订单详情</strong>
</div>
<h1>订单详情</h1>
<p>核对支付、库存分配与履约状态；每一步都应由后端状态机校验。</p>
</div>
<div class="admin-actions">
<RouterLink class="ghost-btn" :to="returnToOrders">返回订单列表</RouterLink>
<button class="admin-primary" v-on:click.prevent="toast('此功能尚未开放，当前只提供页面演示')">创建发货单</button>
</div>
</div>
      <div class="admin-status-banner">
<div>
<span>订单状态</span>
<br>
<strong>待发货 · 已支付</strong>
</div>
<span>订单编号：ZC202610200031</span>
<span>下单时间：2026-10-20 14:32:18</span>
</div>
      <div class="admin-detail-grid">
<div class="admin-stack">
        <article class="admin-white-panel">
<h2>订单商品</h2>
<div class="table-wrap">
<table class="order-table">
<thead>
<tr>
<th>商品 / SKU</th>
<th>规格</th>
<th>单价</th>
<th>数量</th>
<th>小计</th>
</tr>
</thead>
<tbody>
<tr>
<td>
<div class="order-product-cell">
<img :src="gpu.image" :alt="gpu.imageAlt" decoding="async">
<div>
<strong>GeForce RTX 4080 SUPER</strong>
<div class="admin-muted">SKU-GPU-4080S-16</div>
</div>
</div>
</td>
<td>16GB 标准版</td>
<td>¥8,999.00</td>
<td>1</td>
<td>
<strong>¥8,999.00</strong>
</td>
</tr>
<tr>
<td>
<div class="order-product-cell">
<img :src="keyboard.image" :alt="keyboard.imageAlt" decoding="async">
<div>
<strong>ROG 电竞机械键盘</strong>
<div class="admin-muted">SKU-KB-ROG-01</div>
</div>
</div>
</td>
<td>青轴 / 黑色</td>
<td>¥899.00</td>
<td>1</td>
<td>
<strong>¥899.00</strong>
</td>
</tr>
</tbody>
</table>
</div>
<div class="checkout-total-row" style="margin-top:15px">
<span>商品总额</span>
<strong>¥9,898.00</strong>
</div>
<div class="checkout-total-row">
<span>优惠金额</span>
<strong>- ¥500.00</strong>
</div>
<div class="checkout-total-row total">
<span>实付金额</span>
<strong>¥9,398.00</strong>
</div>
</article>
        <article class="admin-white-panel">
<h2>收货与履约信息</h2>
<div class="detail-meta-grid">
<div>
<span>收货人</span>
<strong>林先生</strong>
</div>
<div>
<span>联系电话</span>
<strong>138****8821</strong>
</div>
<div>
<span>履约仓库</span>
<strong>华东一号仓</strong>
</div>
<div>
<span>收货地址</span>
<strong>上海市浦东新区张江高科技园区</strong>
</div>
<div>
<span>配送方式</span>
<strong>普通快递</strong>
</div>
<div>
<span>订单来源</span>
<strong>商城前台</strong>
</div>
</div>
<div style="margin-top:15px">
<span class="form-label">买家备注</span>
<div class="form-section-note" style="margin:0">工作日配送，送达前请电话联系。</div>
</div>
</article>
        <article class="admin-white-panel">
<h2>操作记录</h2>
<div class="admin-timeline">
<div class="timeline-step">
<span class="timeline-dot">✓</span>
<div>
<strong>订单已创建</strong>
<small>2026-10-20 14:32:18 · 系统 · 订单创建成功</small>
</div>
</div>
<div class="timeline-step">
<span class="timeline-dot">✓</span>
<div>
<strong>支付成功</strong>
<small>2026-10-20 14:34:02 · 模拟支付渠道 · 支付状态已确认</small>
</div>
</div>
<div class="timeline-step pending">
<span class="timeline-dot">3</span>
<div>
<strong>等待仓库发货</strong>
<small>未执行 · 创建发货单后记录实际操作人与物流信息</small>
</div>
</div>
</div>
</article>
      </div>
<aside class="admin-stack">
<article class="admin-white-panel">
<h2>支付信息</h2>
<div class="detail-meta-grid" style="grid-template-columns:1fr">
<div>
<span>支付状态</span>
<strong style="color:#0b9679">已支付</strong>
</div>
<div>
<span>支付方式</span>
<strong>模拟支付</strong>
</div>
<div>
<span>支付流水号</span>
<strong>PAY-DEMO-202610200031</strong>
</div>
<div>
<span>支付金额</span>
<strong>¥9,398.00</strong>
</div>
</div>
</article>
<article class="admin-white-panel">
<h2>履约操作</h2>
<p class="admin-muted">当前订单由华东一号仓履约。正式发货前，后端必须检查员工仓库授权、订单支付状态、库存预留和重复发货约束。</p>
<div style="display:grid;gap:9px;margin-top:14px">
<button class="admin-primary" v-on:click.prevent="toast('此功能尚未开放，当前只提供页面演示')">创建发货单</button>
<button class="ghost-btn" v-on:click.prevent="toast('此功能尚未开放，当前只提供页面演示')">申请取消订单</button>
<button class="ghost-btn" v-on:click.prevent="toast('此功能尚未开放，当前只提供页面演示')">查看/创建售后</button>
</div>
</article>
<article class="admin-white-panel">
<h2>状态说明</h2>
<p class="admin-muted">订单状态、支付状态与履约状态分别维护。不能通过前端直接修改订单状态，也不能把支付成功等同于已发货。</p>
</article>
</aside>
</div>
    </section>
  </div>
</main>
</div>
<main v-else class="shell demo-empty"><h1>暂无该订单的详情演示</h1><p>当前仅提供订单 0031 的代表详情页，订单接口将在 M1 接入。</p><RouterLink class="ghost-btn" :to="returnToOrders">返回订单列表</RouterLink></main>
</template>
