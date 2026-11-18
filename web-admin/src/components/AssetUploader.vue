<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { api } from '../session'
import { ApiError } from '@zhicai/shared/api.ts'
import type { Asset } from '@zhicai/shared/catalog-api.ts'

const props=defineProps<{ disabled: boolean }>()
const emit=defineEmits<{ uploaded: [asset: Asset]; state: [state: { busy: boolean; uncertain: boolean }] }>()
const input=ref<HTMLInputElement|null>(null),file=ref<File|null>(null)
const busy=ref(false),uncertain=ref(false),error=ref(''),message=ref('');let key:string|null=null
watch([busy,uncertain],()=>emit('state',{busy:busy.value,uncertain:uncertain.value}),{immediate:true})
function select(event: Event){
  if(busy.value||uncertain.value)return
  file.value=(event.target as HTMLInputElement).files?.[0]??null;key=null;error.value='';message.value=''
  if(file.value && (file.value.size===0 || file.value.size>5*1024*1024)){error.value='图片需大于0且不超过5MiB';file.value=null}
}
function canLeave(){return !busy.value && (!uncertain.value||window.confirm('图片上传结果尚未确认。离开会丢失原请求编号，重传可能产生重复图片，确认离开？'))}
function beforeUnload(event: BeforeUnloadEvent){if(busy.value||uncertain.value){event.preventDefault();event.returnValue=''}}
onMounted(()=>window.addEventListener('beforeunload',beforeUnload));onUnmounted(()=>window.removeEventListener('beforeunload',beforeUnload));onBeforeRouteLeave(canLeave)
defineExpose({canLeave})
async function upload(){
 if(props.disabled||busy.value||!file.value)return
 const body=new FormData();body.append('file',file.value);key??=crypto.randomUUID();busy.value=true;error.value=''
 try{
  const asset=await api.request<Asset>('/admin/assets/images',{method:'POST',body,headers:{'Idempotency-Key':key}})
  uncertain.value=false;key=null;file.value=null;if(input.value)input.value.value='';emit('uploaded',asset);message.value='图片已上传并加入已选图片；保存商品后才会关联到商品。'
 }catch(reason){
  uncertain.value=!(reason instanceof ApiError)||reason.status===0||reason.status>=500||reason.code==='REQUEST_IN_PROGRESS'||reason.code==='INVALID_RESPONSE'
  error.value=reason instanceof Error?reason.message:'上传失败';if(!uncertain.value)key=null
 }finally{busy.value=false}
}
</script>
<template>
 <section class="asset-uploader" aria-label="上传商品图片">
  <h3>上传新图片</h3><p>JPEG、PNG、WebP，单张不超过5MiB、1600万像素。服务端会检查真实图片内容。</p>
  <label for="asset-file">选择本地图片</label><input id="asset-file" ref="input" type="file" accept="image/jpeg,image/png,image/webp" :disabled="disabled||busy||uncertain" @change="select">
  <p v-if="file">{{ file.name }} · {{ (file.size/1024).toFixed(1) }} KiB</p>
  <p v-if="error" role="alert">{{ error }}</p><p v-if="message" role="status">{{ message }}</p>
  <p v-if="uncertain" role="status">上传结果尚未确认，请保留原文件重试，将使用相同请求编号。</p>
  <button type="button" class="admin-primary" :disabled="disabled||busy||!file" @click="upload">{{ busy?'正在上传…':uncertain?'确认原上传结果':'上传并选用' }}</button>
 </section>
</template>
<style scoped>.asset-uploader{border-top:1px solid #dbe4ed;margin:24px 0;padding-top:10px}.asset-uploader input{overflow:hidden}.asset-uploader p{overflow-wrap:anywhere}</style>
