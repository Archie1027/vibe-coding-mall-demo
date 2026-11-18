import type { Spec } from './catalog-api.ts'
import { yuanToMinor } from './money.ts'

interface ProductForm { name: string; categoryId: string; brandId: string; imageAssetIds: string[]; description: string; featured: boolean }
interface SkuForm { code: string; specs: Spec[]; priceYuan: string; enabled: boolean }
const id = (value: string) => /^[1-9][0-9]{0,18}$/.test(value)
const text = (value: string, max: number) => value.trim().length > 0 && [...value].length <= max
export function productInput(form: ProductForm) {
  if (!text(form.name, 120) || !id(form.categoryId) || !id(form.brandId) || [...form.description].length > 10000)
    throw new Error('请填写1–120字商品名称、分类和品牌，描述最多10000字')
  if (!form.imageAssetIds.length || form.imageAssetIds.length > 10 || !form.imageAssetIds.every(id) || new Set(form.imageAssetIds).size !== form.imageAssetIds.length)
    throw new Error('请选择1–10张不同的商品图片')
  return { ...form, imageAssetIds: [...form.imageAssetIds] }
}
export function skuInput(form: SkuForm) {
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(form.code)) throw new Error('SKU编码须为1–64位英文字母、数字、下划线或连字符')
  if (!form.specs.length || form.specs.length > 10 || form.specs.some(s => !text(s.name, 120) || !text(s.value, 100))
    || new Set(form.specs.map(s => s.name)).size !== form.specs.length) throw new Error('请填写1–10组规格，名称不可重复')
  const priceMinor = yuanToMinor(form.priceYuan)
  if (!/^[1-9][0-9]{0,17}$/.test(priceMinor)) throw new Error('价格需大于0，分金额最多18位')
  return { code: form.code, specs: form.specs.map(s => ({ ...s })), priceMinor, enabled: form.enabled }
}
