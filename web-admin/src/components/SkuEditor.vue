<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useCatalogWrite } from '../catalog-write'
import { skuInput } from '@zhicai/shared/catalog-write.ts'
import type { Sku } from '@zhicai/shared/catalog-api.ts'
const props = defineProps<{ productId: string; sku: Sku | null }>()
const emit = defineEmits<{ close: []; saved: [sku: Sku] }>()
const dialog = ref<HTMLDialogElement | null>(null), code = ref(props.sku?.code ?? '')
const specs = ref(props.sku?.specs.map(spec => ({ ...spec })) ?? [{ name: '', value: '' }])
const minor = BigInt(props.sku?.priceMinor ?? '0')
const priceYuan = ref(props.sku ? `${minor / 100n}.${(minor % 100n).toString().padStart(2,'0')}` : ''), enabled = ref(props.sku?.enabled ?? true)
const { busy, uncertain, conflict, frozen, error, canLeave, submit } = useCatalogWrite()
onMounted(() => dialog.value?.showModal())
function close() { if (canLeave()) dialog.value?.close() }
async function save() {
  if (busy.value || conflict.value) return
  try {
    const input = skuInput({ code: code.value, specs: specs.value, priceYuan: priceYuan.value, enabled: enabled.value })
    await submit<Sku>(props.sku ? 'PUT' : 'POST', props.sku ? `/admin/skus/${props.sku.id}` : `/admin/products/${props.productId}/skus`,
      { ...input, ...(props.sku ? { version: props.sku.version } : {}) }, result => emit('saved', result))
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '请检查SKU资料' }
}
</script>
<template>
  <dialog ref="dialog" class="catalog-editor admin-card" :aria-label="sku ? '编辑SKU' : '新增SKU'" @cancel.prevent="close" @close="emit('close')">
    <form @submit.prevent="save"><h2>{{ sku ? '编辑 SKU' : '新增 SKU' }}</h2><p>所属商品 {{ productId }}<template v-if="sku"> · SKU {{ sku.id }} · 版本 {{ sku.version }}</template></p>
      <fieldset :disabled="frozen"><legend>规格与价格</legend><label for="sku-code">SKU编码</label><input id="sku-code" v-model="code" required autofocus autocomplete="off">
        <div v-for="(spec,index) in specs" :key="index" class="spec-input"><div><label :for="`spec-name-${index}`">规格名称 {{ index + 1 }}</label><input :id="`spec-name-${index}`" v-model="spec.name" required placeholder="如：容量"></div><div><label :for="`spec-value-${index}`">规格值 {{ index + 1 }}</label><input :id="`spec-value-${index}`" v-model="spec.value" required placeholder="如：1TB"></div><button type="button" class="ghost-btn" :disabled="specs.length <= 1" :aria-label="`删除规格${index + 1}`" @click="specs.splice(index,1)">删除</button></div>
        <button type="button" class="ghost-btn" :disabled="specs.length >= 10" @click="specs.push({ name:'',value:'' })">添加规格</button>
        <label for="sku-price">售价（元）</label><input id="sku-price" v-model="priceYuan" inputmode="decimal" required placeholder="如：1299.00"><label class="catalog-editor-check"><input v-model="enabled" type="checkbox">启用 SKU</label>
      </fieldset><p>同商品的规格组合不能重复；上架商品需先下架才能停用最后一个可售 SKU。</p>
      <p v-if="error" role="alert">{{ error }}</p><p v-if="uncertain" role="status">结果尚未确认，请保持原内容重试；原请求编号会继续使用。</p>
      <div class="catalog-editor-actions"><button type="button" class="ghost-btn" :disabled="busy" @click="close">关闭</button><button class="admin-primary" :disabled="busy || conflict">{{ busy ? '正在保存…' : uncertain ? '确认原请求结果' : '保存SKU' }}</button></div>
    </form>
  </dialog>
</template>
<style scoped>.spec-input{display:grid;grid-template-columns:1fr 1fr auto;gap:10px;align-items:end;margin-bottom:16px}@media(max-width:600px){.spec-input{grid-template-columns:1fr}.spec-input button{justify-self:start}}</style>
