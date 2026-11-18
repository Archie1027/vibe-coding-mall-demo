import test from 'node:test'
import assert from 'node:assert/strict'
import * as catalog from '../src/catalog-api.ts'
const { createCatalogApi, productPath, toCatalogCard, latestRequest } = catalog

const product = {
  id: '2002', name: '真实商品', categoryId: '2', brandId: '3', imageAssetIds: ['10'],
  description: '真实描述', featured: true, ratingAverage: 0, ratingCount: 0,
  state: 'ON_SALE' as const, images: [{ id: '10', url: 'http://127.0.0.1:8080/assets/products/2.png', contentType: 'image/png', sizeBytes: 100 }],
  skus: [
    { id: '22', productId: '2002', code: 'EXPENSIVE', specs: [{ name: '容量', value: '2TB' }], priceMinor: '9007199254740993', enabled: true, version: 0 },
    { id: '21', productId: '2002', code: 'CHEAP', specs: [{ name: '容量', value: '1TB' }], priceMinor: '9007199254740992', enabled: true, version: 0 },
    { id: '20', productId: '2002', code: 'DISABLED', specs: [], priceMinor: '1', enabled: false, version: 0 },
  ], version: 0, createdAt: '2026-10-22T00:00:00Z',
}

test('product query uses the public API parameter names and string amounts', () => {
  assert.equal(productPath({ page: 2, size: 12, keyword: '显卡', categoryId: '2', brandId: '3', minPriceMinor: '100000', maxPriceMinor: '500000', featured: true, sort: 'PRICE_ASC' }),
    '/catalog/products?page=2&size=12&keyword=%E6%98%BE%E5%8D%A1&categoryId=2&brandId=3&minPriceMinor=100000&maxPriceMinor=500000&featured=true&sort=PRICE_ASC')
})

test('card derives exact lowest enabled SKU price and real zero reviews', () => {
  assert.deepEqual(toCatalogCard(product, [{ id: '2', name: '存储', parentId: null, sort: 1, enabled: true, version: 0 }], [{ id: '3', name: 'Samsung', enabled: true, version: 0 }]), {
    id: '2002', name: '真实商品', categoryName: '存储', brandName: 'Samsung', image: product.images[0]!.url,
    priceMinor: '9007199254740992', ratingAverage: 0, ratingCount: 0, featured: true,
  })
})

test('category dictionary loads every page through total', async () => {
  const paths: string[] = []
  const api = createCatalogApi(async <T>(path: string): Promise<T> => {
    paths.push(path)
    const page = Number(new URLSearchParams(path.split('?')[1]).get('page'))
    return { items: [{ id: String(page), name: String(page), sort: page, enabled: true, version: 0 }], page, size: 1, total: 3 } as T
  })
  assert.equal((await api.allCategories(1)).length, 3)
  assert.deepEqual(paths, ['/catalog/categories?page=1&size=1', '/catalog/categories?page=2&size=1', '/catalog/categories?page=3&size=1'])
})

test('incomplete dictionary response fails instead of silently dropping later pages', async () => {
  const api = createCatalogApi(async <T>(): Promise<T> => ({ items: [], page: 1, size: 100, total: 101 }) as T)
  await assert.rejects(api.allCategories(), /分页响应不完整/)
})

test('request failure propagates and never substitutes demo products', async () => {
  const api = createCatalogApi(async () => { throw new Error('offline') })
  await assert.rejects(api.products({ page: 1, size: 12 }), /offline/)
})

test('late response cannot overwrite newer request', async () => {
  let finishOld!: (value: string) => void
  const old = new Promise<string>(resolve => { finishOld = resolve })
  const seen: string[] = []
  const run = latestRequest<string>(value => seen.push(value))
  const pending = run(() => old)
  await run(async () => 'new')
  finishOld('old')
  await pending
  assert.deepEqual(seen, ['new'])
})

test('valid URL size controls page size and invalid values use 12', () => {
  const parse = (catalog as Record<string, unknown>).catalogPageSize as ((value: unknown) => number) | undefined
  assert.equal(parse?.('2'), 2)
  for (const value of ['0', '101', '1.5', 'abc', undefined]) assert.equal(parse?.(value), 12)
})

test('editing search clears old keyword alias so an empty query stays empty', () => {
  const update = (catalog as Record<string, unknown>).catalogRouteUpdate as ((query: Record<string, unknown>, key: string, value: string) => Record<string, unknown>) | undefined
  const result = update?.({ keyword: '旧搜索', categoryId: '2', page: '3' }, 'q', '')
  assert.equal(result?.keyword, undefined)
  assert.equal(result?.q, undefined)
  assert.equal(result?.page, undefined)
  assert.equal(result?.categoryId, '2')
})

test('failed search does not report the previous successful total', () => {
  const label = (catalog as Record<string, unknown>).catalogResultLabel as ((loading: boolean, error: string, total: number) => string) | undefined
  assert.equal(label?.(false, 'offline', 6), '查询失败，数量不可用')
})
