<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { Product } from '@zhicai/shared/catalog-api.ts'
import { useCatalogWrite } from '../catalog-write'
const props = defineProps<{ product: Product; state: 'ON_SALE' | 'OFF_SALE' }>()
const emit = defineEmits<{ close: []; saved: [product: Product] }>()
const dialog = ref<HTMLDialogElement | null>(null)
const { busy, uncertain, conflict, error, canLeave, submit } = useCatalogWrite()
onMounted(() => dialog.value?.showModal())
function close() { if (canLeave()) dialog.value?.close() }
function save() { void submit<Product>('POST', `/admin/products/${props.product.id}/state`, { state: props.state, version: props.product.version }, product => emit('saved', product)) }
</script>
<template>
  <dialog ref="dialog" class="catalog-editor admin-card" aria-label="商品上下架" @cancel.prevent="close" @close="emit('close')">
    <h2>{{ state === 'ON_SALE' ? '确认上架商品' : '确认下架商品' }}</h2><p>{{ product.name }}</p><p>商品 {{ product.id }} · 当前版本 {{ product.version }}</p>
    <p v-if="state === 'ON_SALE'">系统将检查分类、品牌、主图和启用 SKU。上架后可在商城搜索与查看；库存和交易功能尚未开放。</p><p v-else>下架后商品将不再出现在商城目录，管理端仍可编辑资料。</p>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="uncertain" role="status">结果尚未确认，请使用原请求重试。</p>
    <div class="catalog-editor-actions"><button autofocus class="ghost-btn" :disabled="busy" @click="close">取消</button><button class="admin-primary" :disabled="busy || conflict" @click="save">{{ busy ? '正在提交…' : uncertain ? '确认原请求结果' : state === 'ON_SALE' ? '确认上架' : '确认下架' }}</button></div>
  </dialog>
</template>
