<script setup lang="ts">
import { reactive, ref } from 'vue'
import { addressInput, type AddressInput } from '@zhicai/shared/customer-data.ts'
const props = defineProps<{ initial?: AddressInput; disabled: boolean; editing: boolean }>()
const emit = defineEmits<{ save: [value: AddressInput]; cancel: [] }>()
const form = reactive<AddressInput>({ recipient: '', phone: '', province: '', city: '', district: '', detail: '', isDefault: false, ...props.initial })
const error = ref('')
function submit() { if (props.disabled) return; try { error.value = ''; emit('save', addressInput(form)) } catch (reason) { error.value = reason instanceof Error ? reason.message : '请检查地址' } }
</script>
<template>
<form class="address-editor" @submit.prevent="submit">
  <h2>{{ editing ? '编辑地址' : '新增地址' }}</h2>
  <fieldset :disabled="disabled">
    <div class="address-fields">
      <label for="recipient">收件人<input id="recipient" v-model="form.recipient" maxlength="50" autocomplete="name" required></label>
      <label for="phone">手机号<input id="phone" v-model="form.phone" type="tel" inputmode="tel" maxlength="11" pattern="1[0-9]{10}" autocomplete="tel" required></label>
      <label for="province">省 / 自治区 / 直辖市<input id="province" v-model="form.province" maxlength="120" autocomplete="address-level1" required></label>
      <label for="city">市<input id="city" v-model="form.city" maxlength="120" autocomplete="address-level2" required></label>
      <label for="district">区 / 县<input id="district" v-model="form.district" maxlength="120" autocomplete="address-level3" required></label>
      <label for="detail" class="field-wide">详细地址<textarea id="detail" v-model="form.detail" rows="3" maxlength="200" autocomplete="street-address" required></textarea></label>
    </div>
    <label class="customer-check"><input v-model="form.isDefault" type="checkbox">设为默认地址</label>
    <p v-if="error" class="customer-error" role="alert">{{ error }}</p>
    <div class="customer-actions"><button class="customer-primary" type="submit">{{ disabled ? '正在处理…' : '保存地址' }}</button><button type="button" @click="emit('cancel')">取消</button></div>
  </fieldset>
</form>
</template>
