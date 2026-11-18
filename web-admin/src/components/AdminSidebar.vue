<template>
  <aside class="sidebar">
    <div class="sidebrand"><span class="logo">Z</span><span>智采商城<small style="display:block;color:#8295a7;font-size:12px;font-weight:400">管理工作台</small></span></div>
    <div class="side-label">工作空间</div>
    <RouterLink class="side-item" exact-active-class="active" to="/admin">◫　工作台总览</RouterLink>
    <RouterLink class="side-item" active-class="active" to="/admin/products">商品目录</RouterLink>
    <RouterLink class="side-item" active-class="active" to="/admin/orders">▤　订单管理</RouterLink>
    <RouterLink class="side-item" active-class="active" to="/admin/purchases/new">⇄　采购与入库</RouterLink>
    <div class="side-label">后续开放</div>
    <span class="side-item" aria-disabled="true">▦　库存管理</span><span class="side-item" aria-disabled="true">◇　资金与对账</span><span class="side-item" aria-disabled="true">♙　员工与角色</span>
  </aside>
</template>
