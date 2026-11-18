<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDemo } from '../demo'
import AdminSidebar from '../components/AdminSidebar.vue'
const { showPage, toast, addCart, searchQuery } = useDemo()
import { onBeforeRouteLeave } from 'vue-router'
import { formatMinor, yuanToMinor, sumLines } from '@zhicai/shared/money.ts'
import { api, account } from '../session'
const supplier=ref(''),warehouse=ref(''),expected=ref(''),reference=ref(''),remark=ref('')
let nextKey=2
const lines=ref([{key:1,sku:'GPU-4080S',quantity:1,price:'8999.00',remark:''}])
type Line=typeof lines.value[number]
function lineTotal(line:Line){try{return formatMinor(sumLines([{priceMinor:yuanToMinor(line.price),quantity:line.quantity}]))}catch{return '请检查数量或单价'}}
const valid=computed(()=>lines.value.length>0&&lines.value.every(l=>l.sku&&Number.isSafeInteger(l.quantity)&&l.quantity>0&&l.quantity<=100000&&/^\d+(?:\.\d{1,2})?$/.test(l.price)&&BigInt(yuanToMinor(l.price))>0n))
const grandTotal=computed(()=>{if(!valid.value)return '金额待确认';return formatMinor(sumLines(lines.value.map(l=>({priceMinor:yuanToMinor(l.price),quantity:l.quantity}))))})
const dirty=computed(()=>Boolean(supplier.value||warehouse.value||expected.value||reference.value||remark.value||lines.value.length!==1||lines.value[0]?.sku!=='GPU-4080S'||lines.value[0]?.quantity!==1||lines.value[0]?.price!=='8999.00'||lines.value[0]?.remark))
function addPurchaseLine(){lines.value.push({key:nextKey++,sku:'GPU-4080S',quantity:1,price:'100.00',remark:''})}
function removeLine(index:number){if(lines.value.length===1){toast('至少保留一条采购明细');return}lines.value.splice(index,1)}
function savePurchaseDemo(_action:string){if(!supplier.value||!warehouse.value||!valid.value){toast('请先选择供应商、仓库，并检查明细');return}toast('字段检查通过；当前为演示页面，尚未保存采购单或提交审批')}
onBeforeRouteLeave(()=>!api.hasSession()||!account.value||!dirty.value||window.confirm('采购单尚未保存，确认离开？'))
</script>
<template>
<div id="purchaseFormView" class="">
  <main class="shell" style="padding-top:28px;padding-bottom:45px">
<div class="announcement">
<span>
<strong>智采商城 · 企业管理端</strong>　采购与库存协同</span>
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
<a href="#" v-on:click.prevent="showPage('admin')">采购管理</a>
<span>›</span>
<strong>采购单新增/编辑</strong>
</div>
<h1>采购单新增 / 编辑</h1>
<p>填写供应商、收货仓库与采购明细；审批、入库和付款是独立业务步骤。</p>
</div>
<div class="admin-actions">
<button class="ghost-btn" v-on:click.prevent="showPage('admin')">取消返回</button>
<button class="admin-primary" v-on:click.prevent="savePurchaseDemo('draft')">保存草稿</button>
</div>
</div>
      <div class="admin-status-banner" style="background:#eef8ff;border-color:#d7eaf9;color:#315e7d">
<div>
<span>当前模式</span>
<br>
<strong>新建采购单 · 草稿</strong>
</div>
<span>单号将在后端创建时生成</span>
</div>
      <div class="admin-detail-grid">
<div class="admin-stack">
        <article class="admin-white-panel">
<h2>采购基本信息</h2>
<div class="form-grid">
<div class="form-group">
<label class="form-label" for="purchaseSupplier">供应商 <span style="color:#d9505d">*</span>
</label>
<select class="form-field" id="purchaseSupplier" required v-model="supplier">
<option value="">请选择供应商</option>
<option value="sup1">星河硬件供应链（演示）</option>
<option value="sup2">北辰电子贸易（演示）</option>
<option value="sup3">远航数码配件（演示）</option>
</select>
</div>
<div class="form-group">
<label class="form-label" for="purchaseWarehouse">目标仓库 <span style="color:#d9505d">*</span>
</label>
<select class="form-field" id="purchaseWarehouse" required v-model="warehouse">
<option value="">请选择入库仓库</option>
<option value="wh1">华东一号仓</option>
<option value="wh2">华南二号仓</option>
<option value="wh3">华北三号仓</option>
</select>
<small>员工可选择的仓库范围需由后端权限决定。</small>
</div>
<div class="form-group">
<label class="form-label" for="purchaseExpected">预计到货日期</label>
<input class="form-field" id="purchaseExpected" type="date" value="2026-11-03" v-model="expected">
</div>
<div class="form-group">
<label class="form-label" for="purchaseReference">供应商报价/合同编号</label>
<input class="form-field" id="purchaseReference" placeholder="例如：SUP-Q-2026-1020" v-model="reference">
</div>
<div class="form-group full">
<label class="form-label" for="purchaseRemark">采购备注</label>
<textarea class="form-field" id="purchaseRemark" rows="2" placeholder="采购原因、交期要求等" v-model="remark">
</textarea>
</div>
</div>
</article>
        <article class="admin-white-panel">
<div style="display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:14px;flex-wrap:wrap">
<h2 style="margin:0">采购商品明细</h2>
<button class="ghost-btn" v-on:click.prevent="addPurchaseLine()">＋ 添加商品行</button>
</div>
<div class="form-section-note">采购成本仅对具备相应权限的员工可见。此表单中的单价与金额均为演示值，正式版本由服务端校验精度、供应商关系和采购规则。</div>
<div class="table-wrap">
<table class="purchase-lines">
<thead>
<tr>
<th style="width:28%">商品 SKU</th>
<th style="width:17%">采购数量</th>
<th style="width:18%">含税单价（元）</th>
<th style="width:18%">小计（元）</th>
<th style="width:12%">备注</th>
<th>操作</th>
</tr>
</thead>
<tbody id="purchaseLineRows">
<tr v-for="(line,index) in lines" :key="line.key">
<td>
<select v-model="line.sku">
<option value="GPU-4080S">GPU-4080S · RTX 4080 SUPER</option>
<option value="GPU-4070S">GPU-4070S · RTX 4070 SUPER</option>
<option value="SSD-990P">SSD-990P · 990 PRO 2TB</option>
</select>
</td>
<td>
<input v-model.number="line.quantity" class="purchase-qty" type="number" min="1" max="100000" aria-label="采购数量">
</td>
<td>
<input v-model="line.price" class="purchase-price" inputmode="decimal" aria-label="采购单价（元）">
</td>
<td class="purchase-line-total">{{ lineTotal(line) }}</td>
<td>
<input v-model="line.remark" aria-label="明细备注" placeholder="可选">
</td>
<td>
<button class="remove-line" @click="removeLine(index)">移除</button>
</td>
</tr>
</tbody>
</table>
</div>
<div class="purchase-bottom">
<div class="admin-muted">采购单提交审批后，明细是否允许修改取决于单据状态。分批入库通过独立入库单记录；不得直接将采购数量一次性全部记为库存。</div>
<div class="purchase-total">
<span>采购预估总额（演示）</span>
<strong id="purchaseGrandTotal">{{ grandTotal }}</strong>
<span>正式金额以服务端计算与保存结果为准</span>
</div>
</div>
<div class="purchase-form-actions">
<button class="ghost-btn" v-on:click.prevent="showPage('admin')">取消</button>
<button class="ghost-btn" v-on:click.prevent="savePurchaseDemo('draft')">保存草稿</button>
<button class="admin-primary" v-on:click.prevent="savePurchaseDemo('submit')">提交审批</button>
</div>
</article>
      </div>
<aside class="admin-stack">
<article class="admin-white-panel">
<h2>提交前检查</h2>
<div class="status-list" style="padding:0">
<div class="status-row">
<span>供应商已选择</span>
<span id="checkSupplier" class="pill warn">{{ supplier ? "已选择" : "待确认" }}</span>
</div>
<div class="status-row">
<span>目标仓库已选择</span>
<span id="checkWarehouse" class="pill warn">{{ warehouse ? "已选择" : "待确认" }}</span>
</div>
<div class="status-row">
<span>至少一条有效明细</span>
<span id="checkLines" class="pill">{{ lines.length }} 条</span>
</div>
<div class="status-row">
<span>数量与成本合法</span>
<span id="checkAmounts" class="pill ">{{ valid ? "基础校验通过" : "请检查" }}</span>
</div>
</div>
<p class="admin-muted" style="margin-top:13px">此处只做浏览器端基础校验。审批权限、供应商状态、仓库授权、幂等和版本冲突必须由 Spring Boot 后端执行。</p>
</article>
<article class="admin-white-panel">
<h2>采购流程</h2>
<div class="admin-timeline">
<div class="timeline-step">
<span class="timeline-dot">1</span>
<div>
<strong>新建草稿</strong>
<small>录入采购基本信息与商品明细</small>
</div>
</div>
<div class="timeline-step pending">
<span class="timeline-dot">2</span>
<div>
<strong>提交审批</strong>
<small>由有权限的审批人处理，禁止自我审批</small>
</div>
</div>
<div class="timeline-step pending">
<span class="timeline-dot">3</span>
<div>
<strong>分批入库</strong>
<small>每批通过独立采购入库记录入账</small>
</div>
</div>
<div class="timeline-step pending">
<span class="timeline-dot">4</span>
<div>
<strong>采购付款</strong>
<small>财务单独登记付款与凭据</small>
</div>
</div>
</div>
</article>
</aside>
</div>
    </section>
  </div>
</main>
</div>
</template>
