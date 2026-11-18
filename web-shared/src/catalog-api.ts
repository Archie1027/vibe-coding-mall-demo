export interface Category { id: string; name: string; parentId?: string | null; sort: number; enabled: boolean; version: number }
export interface Brand { id: string; name: string; enabled: boolean; version: number }
export interface Asset { id: string; url: string; contentType: string; sizeBytes: number }
export interface Spec { name: string; value: string }
export interface Sku { id: string; productId: string; code: string; specs: Spec[]; priceMinor: string; enabled: boolean; version: number }
export interface Product {
  id: string; name: string; categoryId: string; brandId: string; imageAssetIds: string[]
  description: string; featured: boolean; ratingAverage: number; ratingCount: number
  state: 'DRAFT' | 'ON_SALE' | 'OFF_SALE'; images: Asset[]; skus: Sku[]
  version: number; createdAt: string
}
export interface PageData<T> { items: T[]; page: number; size: number; total: number }
export type ProductSort = 'NEWEST' | 'PRICE_ASC' | 'PRICE_DESC'
export interface ProductQuery {
  page: number; size: number; keyword?: string; categoryId?: string; brandId?: string
  minPriceMinor?: string; maxPriceMinor?: string; featured?: boolean; sort?: ProductSort
}
export interface CatalogCard {
  id: string; name: string; categoryName: string; brandName: string; image: string
  priceMinor: string; ratingAverage: number; ratingCount: number; featured: boolean
}

export function catalogPageSize(value: unknown): number {
  if (typeof value !== 'string' || !/^\d+$/.test(value)) return 12
  const size = Number(value)
  return Number.isSafeInteger(size) && size >= 1 && size <= 100 ? size : 12
}

type RouteValue = string | (string | null)[] | null | undefined
export function catalogRouteUpdate(query: Record<string, RouteValue>, key: string, value: string): Record<string, RouteValue> {
  const next = { ...query, [key]: value || undefined, page: undefined }
  if (key === 'q') next.keyword = undefined
  if (key === 'category') next.categoryId = undefined
  if (key === 'brand') next.brandId = undefined
  if (key === 'price') { next.minPriceMinor = undefined; next.maxPriceMinor = undefined }
  return next
}

export function catalogResultLabel(loading: boolean, error: string, total: number): string {
  return loading ? '正在查询…' : error ? '查询失败，数量不可用' : `共 ${total} 件商品`
}

export function productPath(query: ProductQuery): string {
  const params = new URLSearchParams({ page: String(query.page), size: String(query.size) })
  for (const key of ['keyword', 'categoryId', 'brandId', 'minPriceMinor', 'maxPriceMinor', 'featured', 'sort'] as const) {
    const value = query[key]
    if (value !== undefined && value !== '') params.set(key, String(value))
  }
  return `/catalog/products?${params}`
}

type Request = <T>(path: string) => Promise<T>
export function createCatalogApi(request: Request) {
  async function allPages<T>(path: string, size: number): Promise<T[]> {
    const items: T[] = []
    for (let page = 1; ; page++) {
      const result = await request<PageData<T>>(`${path}?page=${page}&size=${size}`)
      items.push(...result.items)
      if (result.items.length === 0 && items.length < result.total) throw new Error('分类或品牌分页响应不完整，请重试')
      if (result.page * result.size >= result.total) {
        if (items.length < result.total) throw new Error('分类或品牌分页响应不完整，请重试')
        return items
      }
    }
  }
  return {
    allCategories: (size = 100) => allPages<Category>('/catalog/categories', size),
    allBrands: (size = 100) => allPages<Brand>('/catalog/brands', size),
    products: (query: ProductQuery) => request<PageData<Product>>(productPath(query)),
    product: (id: string) => request<Product>(`/catalog/products/${encodeURIComponent(id)}`),
  }
}

export function toCatalogCard(product: Product, categories: Category[], brands: Brand[]): CatalogCard {
  const enabled = product.skus.filter(sku => sku.enabled)
  if (enabled.length === 0) throw new TypeError(`商品 ${product.id} 没有可展示的SKU`)
  const priceMinor = enabled.reduce((lowest, sku) => BigInt(sku.priceMinor) < BigInt(lowest) ? sku.priceMinor : lowest, enabled[0]!.priceMinor)
  return {
    id: product.id, name: product.name,
    categoryName: categories.find(item => item.id === product.categoryId)?.name ?? '未分类',
    brandName: brands.find(item => item.id === product.brandId)?.name ?? '品牌信息加载中',
    image: product.images[0]?.url ?? '', priceMinor,
    ratingAverage: product.ratingAverage, ratingCount: product.ratingCount, featured: product.featured,
  }
}

/** Only the newest completion may publish data or an error. */
export function latestRequest<T>(publish: (value: T) => void, fail?: (error: unknown) => void) {
  let serial = 0
  return async (load: () => Promise<T>): Promise<void> => {
    const current = ++serial
    try { const result = await load(); if (current === serial) publish(result) }
    catch (error) { if (current === serial) fail?.(error) }
  }
}
