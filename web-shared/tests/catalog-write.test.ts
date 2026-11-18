import test from 'node:test'
import assert from 'node:assert/strict'
import { productInput, skuInput } from '../src/catalog-write.ts'

const product = { name: '商品', categoryId: '101', brandId: '201', imageAssetIds: ['9007199254740993'], description: '<script>纯文本</script>', featured: false }
test('product payload preserves string IDs and plain description', () => {
  assert.deepEqual(productInput(product), product)
})
test('product requires nonblank name, real ID shape and unique ordered images', () => {
  for (const invalid of [{ ...product, name: ' ' }, { ...product, categoryId: '01' }, { ...product, imageAssetIds: [] },
    { ...product, imageAssetIds: ['1', '1'] }, { ...product, description: 'x'.repeat(10001) }])
    assert.throws(() => productInput(invalid))
})
test('SKU price from yuan retains cents above JS integer precision', () => {
  const body = skuInput({ code: 'CODE-1', specs: [{ name: '容量', value: '1TB' }], priceYuan: '90071992547409.93', enabled: true })
  assert.equal(body.priceMinor, '9007199254740993')
})
test('SKU rejects zero, overflow, excess decimals and exponent notation', () => {
  for (const priceYuan of ['0', '10000000000000000', '1.001', '1e3', '-1'])
    assert.throws(() => skuInput({ code: 'CODE', specs: [{ name: '容量', value: '1TB' }], priceYuan, enabled: true }))
})
test('SKU cannot contain duplicate dimension names or an invalid code', () => {
  for (const code of ['中文', 'CODE SPACE', 'a'.repeat(65)])
    assert.throws(() => skuInput({ code, specs: [{ name: '容量', value: '1TB' }], priceYuan: '1', enabled: true }))
  assert.throws(() => skuInput({ code: 'CODE', specs: [{ name: '容量', value: '1TB' }, { name: '容量', value: '2TB' }], priceYuan: '1', enabled: true }))
})
