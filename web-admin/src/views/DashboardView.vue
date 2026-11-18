<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDemo } from '../demo'
import AdminSidebar from '../components/AdminSidebar.vue'
const { showPage, toast, addCart, searchQuery } = useDemo()
import { account } from '../session'
</script>
<template>
<div id="adminView" class="">
<main class="shell" style="padding-top:28px;padding-bottom:45px">
<div class="announcement">
<span>
<strong>智采商城 · 企业管理端</strong>　多仓履约 / 采购 / 订单 / 资金</span>
<span>开发演示 · 商品与业务单据均为示例</span>
</div>
<div class="admin-layout">
  <admin-sidebar>
</admin-sidebar>
  <section class="admin-main" id="adminTop">
<div class="admin-top">
<div>
<h1>工作台总览</h1>
<p>你好，{{ account?.displayName || "员工" }}。这里是业务工作台演示。</p>
</div>
<div class="admin-actions">
<button class="ghost-btn" v-on:click.prevent="toast('此功能尚未开放，当前只提供页面演示')">◷ 最近 7 天　⌄</button>
<button class="admin-primary" v-on:click.prevent="toast('此功能尚未开放，当前只提供页面演示')">↻ 刷新数据</button>
</div>
</div>
    <div class="kpi-grid">
<div class="kpi">
<div class="kpi-label">今日销售额 <span>↗</span>
</div>
<div class="kpi-value">¥126,890</div>
<div class="kpi-foot">
<span class="positive">↑ 12.8%</span>　较上周同期</div>
</div>
<div class="kpi">
<div class="kpi-label">待处理订单 <span>▤</span>
</div>
<div class="kpi-value">128</div>
<div class="kpi-foot">
<span class="positive">↓ 4.2%</span>　较昨日</div>
</div>
<div class="kpi">
<div class="kpi-label">库存预警 SKU <span>△</span>
</div>
<div class="kpi-value">12</div>
<div class="kpi-foot">
<span class="negative">4 项紧急</span>　需要跟进</div>
</div>
<div class="kpi">
<div class="kpi-label">待审批采购单 <span>⇄</span>
</div>
<div class="kpi-value">7</div>
<div class="kpi-foot">
<span style="color:#f2c16d">3 项超过 24 小时</span>
</div>
</div>
</div>
    <div class="admin-grid">
<article class="admin-card">
<div class="card-head">
<h3>销售趋势</h3>
<span>近 7 天　⌄</span>
</div>
<div class="chart">
<svg viewBox="0 0 560 160" preserveAspectRatio="none" aria-label="销售趋势示意图">
<defs>
<linearGradient id="chartFill" x1="0" x2="0" y1="0" y2="1">
<stop offset="0" stop-color="#35d5b6" stop-opacity=".25">
</stop>
<stop offset="1" stop-color="#35d5b6" stop-opacity="0">
</stop>
</linearGradient>
</defs>
<g stroke="rgba(140,174,194,.12)" stroke-dasharray="3 5">
<path d="M0 20H560M0 60H560M0 100H560M0 140H560">
</path>
</g>
<path d="M0 128 C30 118 45 126 75 95 S125 110 160 80 S210 96 245 59 S300 83 335 47 S390 76 420 39 S475 67 510 28 S540 42 560 15 L560 160 L0 160Z" fill="url(#chartFill)">
</path>
<path d="M0 128 C30 118 45 126 75 95 S125 110 160 80 S210 96 245 59 S300 83 335 47 S390 76 420 39 S475 67 510 28 S540 42 560 15" fill="none" stroke="#4ce0c0" stroke-width="2.5" stroke-linecap="round">
</path>
<circle cx="420" cy="39" r="4" fill="#e4fff7" stroke="#45dfc0" stroke-width="3">
</circle>
</svg>
<div class="chart-labels">
<span>10/14</span>
<span>10/15</span>
<span>10/16</span>
<span>10/17</span>
<span>10/18</span>
<span>10/19</span>
<span>10/20</span>
</div>
</div>
</article>
      <article class="admin-card" id="inventoryCard">
<div class="card-head">
<h3>待办与风险提醒</h3>
<span>查看全部 ↗</span>
</div>
<div class="status-list">
<div class="status-row">
<span>采购单待审批</span>
<span class="pill warn">7 项待办</span>
</div>
<div class="status-row">
<span>低库存 SKU</span>
<span class="pill danger">4 项紧急</span>
</div>
<div class="status-row">
<span>待发货订单</span>
<span class="pill">32 项</span>
</div>
<div class="status-row">
<span>待处理对账差异</span>
<span class="pill warn">3 项待处理</span>
</div>
<div class="status-row">
<span>退款执行异常</span>
<span class="pill danger">1 项需关注</span>
</div>
</div>
</article>
</div>
    <article class="admin-card table-card" id="ordersTable">
<div class="card-head">
<h3>近期订单</h3>
<span>
<a href="#admin-orders" v-on:click.prevent="showPage('adminOrders')">进入订单管理 ↗</a>
</span>
</div>
<div class="table-wrap">
<table>
<thead>
<tr>
<th>订单编号</th>
<th>客户</th>
<th>下单时间</th>
<th>订单金额</th>
<th>履约仓库</th>
<th>状态</th>
<th>操作</th>
</tr>
</thead>
<tbody>
<tr>
<td>
<strong>ZC202610200031</strong>
</td>
<td>林先生</td>
<td>10/20 14:32</td>
<td>¥ 12,499</td>
<td>华东一号仓</td>
<td>
<span class="pill warn">待发货</span>
</td>
<td>
<a class="textlink" href="#ordersTable" v-on:click.prevent="showPage('adminOrders')">查看订单</a>
</td>
</tr>
<tr>
<td>
<strong>ZC202610200030</strong>
</td>
<td>陈女士</td>
<td>10/20 14:18</td>
<td>¥ 8,999</td>
<td>华南二号仓</td>
<td>
<span class="pill">已付款</span>
</td>
<td>
<RouterLink class="textlink" to="/admin/orders/30">查看详情</RouterLink>
</td>
</tr>
<tr>
<td>
<strong>ZC202610200029</strong>
</td>
<td>星河科技</td>
<td>10/20 13:56</td>
<td>¥ 26,800</td>
<td>华东一号仓</td>
<td>
<span class="pill">处理中</span>
</td>
<td>
<RouterLink class="textlink" to="/admin/orders/29">查看详情</RouterLink>
</td>
</tr>
<tr>
<td>
<strong>ZC202610200028</strong>
</td>
<td>赵先生</td>
<td>10/20 13:21</td>
<td>¥ 2,899</td>
<td>华北三号仓</td>
<td>
<span class="pill danger">库存确认</span>
</td>
<td>
<RouterLink class="textlink" to="/admin/orders/28">查看详情</RouterLink>
</td>
</tr>
</tbody>
</table>
</div>
<div class="table-foot">
<span>显示 4 条演示记录 · 业务数据尚未接入后端</span>
<span>1 / 32　‹　›</span>
</div>
</article>
    <div class="admin-grid">
<article class="admin-card" id="procurementCard">
<div class="card-head">
<h3>采购审批</h3>
<span>
<a href="#" v-on:click.prevent="showPage('purchaseForm')">新建采购单 ↗</a>
</span>
</div>
<div class="status-list">
<div class="status-row">
<span>PO-20261020-008　显卡补货</span>
<span class="pill warn">待审批</span>
</div>
<div class="status-row">
<span>PO-20261020-007　SSD 采购</span>
<span class="pill warn">待审批</span>
</div>
<div class="status-row">
<span>PO-20261019-021　内存采购</span>
<span class="pill">已批准</span>
</div>
</div>
</article>
<article class="admin-card" id="financeCard">
<div class="card-head">
<h3>资金与对账</h3>
<span>资金中心 ↗</span>
</div>
<div class="status-list">
<div class="status-row">
<span>今日待核对交易</span>
<strong>128 笔</strong>
</div>
<div class="status-row">
<span>对账差异金额</span>
<strong style="color:#f3c16f">¥ 1,280.00</strong>
</div>
<div class="status-row">
<span>待执行退款</span>
<strong>6 笔</strong>
</div>
</div>
</article>
</div>
    <p id="permissionNote" style="color:#607e93;font-size:12px;margin-top:18px">演示说明：此工作台为静态样例数据。员工、角色、仓库范围、审计日志和后端权限校验将在对应模块实现。</p>
  </section>
</div>
</main>
</div>
</template>
