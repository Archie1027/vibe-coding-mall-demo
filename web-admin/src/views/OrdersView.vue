<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDemo } from '../demo'
import AdminSidebar from '../components/AdminSidebar.vue'
const { showPage, toast, addCart, searchQuery } = useDemo()
import { demoOrders } from '@zhicai/shared/demo-products.ts'
import { matchesOrder as matchesPreview } from '@zhicai/shared/orders.ts'
const route=useRoute(),router=useRouter()
const keyword=ref(String(route.query.q||'')),status=ref(String(route.query.status||'全部')),warehouse=ref(String(route.query.warehouse||'全部仓库')),source=ref(String(route.query.source||'全部来源')),period=ref(String(route.query.period||'all'))
function matchesOrder(i:number){const row=demoOrders[i];return Boolean(row && matchesPreview(row,{status:status.value,keyword:keyword.value,warehouse:warehouse.value,source:source.value,period:period.value}))}
function filterOrders(){keyword.value=keyword.value.trim()}
function resetFilters(){keyword.value='';status.value='全部';warehouse.value='全部仓库';source.value='全部来源';period.value='all'}
function openOrder(id: string){void router.push({path:`/admin/orders/${id}`,query:route.query})}
const visibleCount=computed(()=>demoOrders.filter((_,i)=>matchesOrder(i)).length)
watch([keyword,status,warehouse,source,period],()=>router.replace({query:{q:keyword.value||undefined,status:status.value==='全部'?undefined:status.value,warehouse:warehouse.value==='全部仓库'?undefined:warehouse.value,source:source.value==='全部来源'?undefined:source.value,period:period.value==='all'?undefined:period.value}}))
watch(()=>route.query,q=>{keyword.value=String(q.q||'');status.value=String(q.status||'全部');warehouse.value=String(q.warehouse||'全部仓库');source.value=String(q.source||'全部来源');period.value=String(q.period||'all')})
</script>
<template>
<div id="adminOrdersView" class="">
  <main class="shell" style="padding-top:28px;padding-bottom:45px">
<div class="announcement">
<span>
<strong>智采商城 · 企业管理端</strong>　订单履约与售后管理</span>
<span>开发演示 · 商品与业务单据均为示例</span>
</div>
<div class="admin-layout">
    <admin-sidebar>
</admin-sidebar>
    <section class="admin-main" id="admin-orders">
<div class="admin-top">
<div>
<div class="breadcrumbs admin-breadcrumbs">
<a href="#adminTop" v-on:click.prevent="showPage('admin')">工作台</a>
<span>›</span>
<strong>订单管理</strong>
</div>
<h1>订单管理</h1>
<p>按订单状态、客户、时间与履约信息检索订单。</p>
</div>
<div class="admin-actions">
<button class="ghost-btn" v-on:click.prevent="toast('此功能尚未开放，当前只提供页面演示')">导出订单</button>
<button class="admin-primary" v-on:click.prevent="toast('此功能尚未开放，当前只提供页面演示')">＋ 新建订单</button>
</div>
</div>
      <div class="order-summary-grid">
<div class="order-summary">
<span>全部订单</span>
<strong>1,286</strong>
<small>当前筛选周期 · 演示</small>
</div>
<div class="order-summary">
<span>待付款</span>
<strong>32</strong>
<small>等待买家完成支付</small>
</div>
<div class="order-summary">
<span>待发货</span>
<strong>86</strong>
<small>等待仓库履约</small>
</div>
<div class="order-summary">
<span>售后处理中</span>
<strong>12</strong>
<small>需要人工跟进</small>
</div>
</div>
      <article class="admin-card order-list-card">
<div class="order-toolbar">
<div class="order-tabs">
<button class="order-tab" data-order-filter="全部" v-bind:class="{ active: status === '全部' }" v-on:click="status = '全部'">全部订单</button>
<button class="order-tab" data-order-filter="待付款" v-bind:class="{ active: status === '待付款' }" v-on:click="status = '待付款'">待付款</button>
<button class="order-tab" data-order-filter="待发货" v-bind:class="{ active: status === '待发货' }" v-on:click="status = '待发货'">待发货</button>
<button class="order-tab" data-order-filter="已发货" v-bind:class="{ active: status === '已发货' }" v-on:click="status = '已发货'">已发货</button>
<button class="order-tab" data-order-filter="已完成" v-bind:class="{ active: status === '已完成' }" v-on:click="status = '已完成'">已完成</button>
<button class="order-tab" data-order-filter="售后中" v-bind:class="{ active: status === '售后中' }" v-on:click="status = '售后中'">售后中</button>
</div>
<div class="order-search">
<input id="orderSearch" placeholder="搜索订单号 / 客户 / 仓库" aria-label="搜索订单" v-model="keyword">
<button class="ghost-btn" v-on:click.prevent="filterOrders()">搜索</button>
</div>
</div>
        <div class="order-advanced-filters">
<label>下单时间 <select v-model="period">
<option value="all">全部时间</option>
<option value="30">最近 30 天</option>
<option value="7">最近 7 天</option>
<option value="today">今天</option>
</select>
</label>
<label>履约仓库 <select v-model="warehouse">
<option>全部仓库</option>
<option>华东一号仓</option>
<option>华南二号仓</option>
<option>华北三号仓</option>
</select>
</label>
<label>订单来源 <select v-model="source">
<option>全部来源</option>
<option>商城前台</option>
<option>后台录入</option>
</select>
</label>
<button class="filter" v-on:click.prevent="resetFilters()">重置筛选</button>
</div>
        <div class="table-wrap">
<table class="order-table">
<thead>
<tr>
<th>
<input type="checkbox" aria-label="全选订单">
</th>
<th>订单编号 / 下单时间</th>
<th>客户</th>
<th>订单金额</th>
<th>履约仓库</th>
<th>订单状态</th>
<th>支付状态</th>
<th>操作</th>
</tr>
</thead>
<tbody id="orderRows">
          <tr data-status="待发货" v-show="matchesOrder(0)">
<td>
<input type="checkbox" aria-label="选择订单 0031">
</td>
<td>
<strong>ZC202610200031</strong>
<small class="table-sub">2026-10-20 14:32</small>
</td>
<td>林先生</td>
<td>
<strong>¥ 12,499.00</strong>
</td>
<td>华东一号仓</td>
<td>
<span class="pill warn">待发货</span>
</td>
<td>
<span class="pill">已支付</span>
</td>
<td>
<button class="table-action" v-on:click.prevent="openOrder('31')">查看详情</button>
</td>
</tr>
          <tr data-status="待付款" v-show="matchesOrder(1)">
<td>
<input type="checkbox" aria-label="选择订单 0030">
</td>
<td>
<strong>ZC202610200030</strong>
<small class="table-sub">2026-10-20 14:18</small>
</td>
<td>陈女士</td>
<td>
<strong>¥ 8,999.00</strong>
</td>
<td>华南二号仓</td>
<td>
<span class="pill warn">待付款</span>
</td>
<td>
<span class="pill warn">未支付</span>
</td>
<td>
<button class="table-action" v-on:click.prevent="openOrder('30')">查看详情</button>
</td>
</tr>
          <tr data-status="已发货" v-show="matchesOrder(2)">
<td>
<input type="checkbox" aria-label="选择订单 0029">
</td>
<td>
<strong>ZC202610200029</strong>
<small class="table-sub">2026-10-20 13:56</small>
</td>
<td>星河科技</td>
<td>
<strong>¥ 26,800.00</strong>
</td>
<td>华东一号仓</td>
<td>
<span class="pill">已发货</span>
</td>
<td>
<span class="pill">已支付</span>
</td>
<td>
<button class="table-action" v-on:click.prevent="openOrder('29')">查看详情</button>
</td>
</tr>
          <tr data-status="已完成" v-show="matchesOrder(3)">
<td>
<input type="checkbox" aria-label="选择订单 0028">
</td>
<td>
<strong>ZC202610200028</strong>
<small class="table-sub">2026-10-19 18:21</small>
</td>
<td>赵先生</td>
<td>
<strong>¥ 2,899.00</strong>
</td>
<td>华北三号仓</td>
<td>
<span class="pill">已完成</span>
</td>
<td>
<span class="pill">已支付</span>
</td>
<td>
<button class="table-action" v-on:click.prevent="openOrder('28')">查看详情</button>
</td>
</tr>
          <tr data-status="售后中" v-show="matchesOrder(4)">
<td>
<input type="checkbox" aria-label="选择订单 0027">
</td>
<td>
<strong>ZC202610200027</strong>
<small class="table-sub">2026-10-19 16:03</small>
</td>
<td>陈女士</td>
<td>
<strong>¥ 1,199.00</strong>
</td>
<td>华南二号仓</td>
<td>
<span class="pill danger">售后中</span>
</td>
<td>
<span class="pill">已支付</span>
</td>
<td>
<button class="table-action" v-on:click.prevent="openOrder('27')">查看详情</button>
</td>
</tr>
<tr v-if="visibleCount === 0"><td colspan="8" class="order-empty" role="status">没有匹配的演示订单，请调整条件或重置筛选。</td></tr>
        </tbody>
</table>
</div>
<div class="table-foot">
<span id="orderResultCount">当前显示 {{ visibleCount }} 条演示记录</span>
<span>本页共 5 条演示数据</span>
</div>
</article>
      <p class="admin-page-note">业务说明：列表状态、支付状态和履约状态是不同维度。实际权限、状态流转、发货、取消与退款必须由 Spring Boot 后端校验。</p>
    </section>
  </div>
</main>
</div>
</template>
