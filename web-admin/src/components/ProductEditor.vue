<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../session'
import { useCatalogWrite } from '../catalog-write'
import { productInput } from '@zhicai/shared/catalog-write.ts'
import type { Asset, Brand, Category, PageData, Product } from '@zhicai/shared/catalog-api.ts'
import AssetUploader from './AssetUploader.vue'

const props = defineProps<{ product: Product | null; categories: Category[]; brands: Brand[] }>()
const emit = defineEmits<{ close: []; saved: [product: Product] }>()
const dialog = ref<HTMLDialogElement | null>(null)
const name = ref(props.product?.name ?? ''), categoryId = ref(props.product?.categoryId ?? ''), brandId = ref(props.product?.brandId ?? '')
const description = ref(props.product?.description ?? ''), featured = ref(props.product?.featured ?? false)
const selected = ref<Asset[]>([...(props.product?.images ?? [])])
const sourceQuery = ref(''), sourcePage = ref(1), sourceTotal = ref(0), sourceImages = ref<Asset[]>([])
const sourceLoading = ref(false), sourceError = ref(''); let serial = 0
const { busy, uncertain, conflict, frozen, error, canLeave, submit } = useCatalogWrite()
const uploader=ref<InstanceType<typeof AssetUploader>|null>(null),uploadState=ref({busy:false,uncertain:false})
const fieldsFrozen=computed(()=>frozen.value||uploadState.value.busy||uploadState.value.uncertain)
const sourcePages = computed(() => Math.max(1, Math.ceil(sourceTotal.value / 8)))
onMounted(() => { dialog.value?.showModal(); void loadImages(1) })
function close() { if (canLeave() && uploader.value?.canLeave()!==false) dialog.value?.close() }
async function loadImages(page: number) {
  const current = ++serial; sourceLoading.value = true; sourceError.value = ''
  try {
    const result = await api.request<PageData<Product>>(`/admin/products?${new URLSearchParams({ page: String(page), size: '8', keyword: sourceQuery.value.trim() })}`)
    if (current !== serial) return
    sourcePage.value = page; sourceTotal.value = result.total
    sourceImages.value = [...new Map(result.items.flatMap(p => p.images).map(image => [image.id, image])).values()]
  } catch (reason) { if (current === serial) sourceError.value = reason instanceof Error ? reason.message : '图片加载失败' }
  finally { if (current === serial) sourceLoading.value = false }
}
function choose(image: Asset) {
  if (fieldsFrozen.value) return
  if (selected.value.some(item => item.id === image.id)) selected.value = selected.value.filter(item => item.id !== image.id)
  else if (selected.value.length < 10) selected.value.push(image)
}
function move(index: number, delta: number) {
  if (fieldsFrozen.value) return
  const copy = [...selected.value]; [copy[index], copy[index + delta]] = [copy[index + delta]!, copy[index]!]; selected.value = copy
}
async function save() {
  if (busy.value || conflict.value || uploadState.value.busy || uploadState.value.uncertain) return
  try {
    const input = productInput({ name: name.value, categoryId: categoryId.value, brandId: brandId.value, imageAssetIds: selected.value.map(image => image.id), description: description.value, featured: featured.value })
    await submit<Product>(props.product ? 'PUT' : 'POST', props.product ? `/admin/products/${props.product.id}` : '/admin/products',
      { ...input, ...(props.product ? { version: props.product.version } : {}) }, result => emit('saved', result))
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '请检查商品资料' }
}
</script>

<template>
  <dialog ref="dialog" class="catalog-editor admin-card" :aria-label="product ? '编辑商品' : '新增商品'" @cancel.prevent="close" @close="emit('close')">
    <form @submit.prevent="save">
      <h2>{{ product ? '编辑商品' : '新增商品草稿' }}</h2>
      <p v-if="product">商品 ID {{ product.id }} · 版本 {{ product.version }} · {{ product.state }}</p>
      <p v-else>先保存草稿，再添加规格和价格，通过校验后上架。</p>
      <fieldset :disabled="fieldsFrozen"><legend>商品资料</legend>
        <label for="product-name">商品名称</label><input id="product-name" v-model="name" autofocus required>
        <div class="catalog-editor-pair"><div><label for="product-category">分类</label><select id="product-category" v-model="categoryId" required><option disabled value="">请选择分类</option><option v-for="item in categories" :key="item.id" :value="item.id">{{ item.name }}{{ item.enabled ? '' : '（停用）' }}</option></select></div>
          <div><label for="product-brand">品牌</label><select id="product-brand" v-model="brandId" required><option disabled value="">请选择品牌</option><option v-for="item in brands" :key="item.id" :value="item.id">{{ item.name }}{{ item.enabled ? '' : '（停用）' }}</option></select></div></div>
        <label for="product-description">商品描述（纯文本）</label><textarea id="product-description" v-model="description" rows="4"></textarea>
        <label class="catalog-editor-check"><input v-model="featured" type="checkbox">列为精选商品</label>
        <h3>商品图片 · {{ selected.length }} / 10</h3><p>第一张为主图，可调整顺序。可选择已有图片，或上传新图片，最多10张。</p>
        <ol class="selected-images"><li v-for="(image, index) in selected" :key="image.id"><img :src="image.url" :alt="`已选图片 ${index + 1}`"><span>{{ index === 0 ? '主图' : `图片 ${index + 1}` }}</span><div><button type="button" :disabled="index === 0" class="ghost-btn" :aria-label="`图片${index + 1}前移`" @click="move(index,-1)">前移</button><button type="button" :disabled="index === selected.length - 1" class="ghost-btn" :aria-label="`图片${index + 1}后移`" @click="move(index,1)">后移</button><button type="button" class="ghost-btn" :aria-label="`移除图片${index + 1}`" @click="choose(image)">移除</button></div></li></ol>
      </fieldset>
      <AssetUploader ref="uploader" :disabled="frozen||selected.length>=10" @state="uploadState=$event" @uploaded="selected.push($event)" />
      <section aria-label="选择已有商品图片" class="image-picker">
        <label for="image-source">按来源商品名称找图片</label><div class="catalog-editor-actions"><input id="image-source" v-model="sourceQuery" :disabled="fieldsFrozen" @keydown.enter.prevent="loadImages(1)"><button type="button" class="ghost-btn" :disabled="fieldsFrozen || sourceLoading" @click="loadImages(1)">查找图片</button></div>
        <p v-if="sourceLoading" role="status">正在加载图片…</p><p v-else-if="sourceError" role="alert">{{ sourceError }}</p>
        <div v-else class="image-options"><button v-for="image in sourceImages" :key="image.id" type="button" :aria-label="`选择图片 ${image.id}`" :aria-pressed="selected.some(item => item.id === image.id)" :disabled="fieldsFrozen || selected.length >= 10 && !selected.some(item => item.id === image.id)" @click="choose(image)"><img :src="image.url" alt="商品图片"><span>{{ selected.some(item => item.id === image.id) ? '已选择' : '选择' }} · {{ image.id }}</span></button></div>
        <p v-if="!sourceLoading && !sourceError && !sourceImages.length">没有找到图片，试试其他商品名称。</p>
        <div class="catalog-editor-actions"><button type="button" class="ghost-btn" :disabled="frozen || sourceLoading || sourcePage <= 1" @click="loadImages(sourcePage-1)">上一组图片</button><span>{{ sourcePage }} / {{ sourcePages }}</span><button type="button" class="ghost-btn" :disabled="frozen || sourceLoading || sourcePage >= sourcePages" @click="loadImages(sourcePage+1)">下一组图片</button></div>
      </section>
      <p v-if="error" role="alert">{{ error }}</p><p v-if="uncertain" role="status">结果尚未确认，请保持原内容重试；原请求编号会继续使用。</p>
      <div class="catalog-editor-actions"><button type="button" class="ghost-btn" :disabled="busy||uploadState.busy" @click="close">关闭</button><button class="admin-primary" :disabled="busy || conflict || uploadState.busy || uploadState.uncertain">{{ busy ? '正在保存…' : uncertain ? '确认原请求结果' : '保存商品' }}</button></div>
    </form>
  </dialog>
</template>

<style>
.catalog-editor{width:min(820px,92vw);max-height:85svh;overflow:auto;margin:auto;padding:28px;box-sizing:border-box;color:#30485e;border:1px solid #dbe4ed;border-radius:12px}.catalog-editor::backdrop{background:rgba(15,35,50,.5)}.catalog-editor h2{margin-top:0}.catalog-editor p{line-height:1.7}.catalog-editor fieldset{border:0;padding:0;margin:0}.catalog-editor label{display:block;margin:18px 0 8px}.catalog-editor input:not([type=checkbox]),.catalog-editor select,.catalog-editor textarea{width:100%;box-sizing:border-box;padding:12px;border:1px solid #bbcbd7;border-radius:6px;background:white;color:#263c50;font:inherit;min-width:0}.catalog-editor textarea{resize:vertical}.catalog-editor-check{display:flex!important;gap:10px;align-items:center}.catalog-editor-check input{width:20px;height:20px}.catalog-editor-pair{display:grid;grid-template-columns:1fr 1fr;gap:16px}.catalog-editor-actions{display:flex;gap:12px;align-items:center;justify-content:flex-end;flex-wrap:wrap;margin-top:20px}.catalog-editor-actions>input{flex:1;min-width:140px!important}.selected-images{padding:0;list-style:none;display:grid;grid-template-columns:1fr 1fr;gap:12px}.selected-images li{border:1px solid #dbe4ed;padding:12px;display:flex;flex-wrap:wrap;gap:10px;align-items:center}.selected-images img{width:70px;height:70px;object-fit:contain;background:white}.selected-images li>div{width:100%;display:flex;gap:8px;flex-wrap:wrap}.image-picker{border-top:1px solid #dbe4ed;margin-top:20px}.image-options{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-top:16px}.image-options button{padding:10px;background:white;color:#30485e;border:1px solid #dbe4ed;overflow-wrap:anywhere}.image-options button[aria-pressed=true]{outline:3px solid #216f8f;outline-offset:-3px}.image-options img{display:block;width:100%;height:90px;object-fit:contain;margin-bottom:8px}.catalog-editor button:disabled{opacity:.5;cursor:default}.catalog-editor :is(button,input,select,textarea):focus-visible{outline:3px solid #216f8f;outline-offset:3px}@media(max-width:600px){.catalog-editor{padding:18px}.catalog-editor-pair{grid-template-columns:1fr}.image-options{grid-template-columns:1fr 1fr}.selected-images{grid-template-columns:1fr}}
</style>
