export interface CatalogItem { id: string; name: string; brand: string; category: string; priceMinor: string; rating: string }
export interface CatalogFilter { keyword?: string; category?: string; brand?: string; priceRange?: string; sort?: string }

export function filterProducts<T extends CatalogItem>(products: readonly T[], filter: CatalogFilter): T[] {
  const keyword = (filter.keyword ?? '').trim().toLocaleLowerCase()
  const items = products.filter(item => {
    const price = BigInt(item.priceMinor)
    return (!keyword || `${item.name} ${item.brand} ${item.category}`.toLocaleLowerCase().includes(keyword))
      && (!filter.category || filter.category === '全部' || item.category === filter.category)
      && (!filter.brand || filter.brand === '全部' || item.brand === filter.brand)
      && (!filter.priceRange || filter.priceRange === '全部'
        || filter.priceRange === 'low' && price < 100000n
        || filter.priceRange === 'mid' && price >= 100000n && price <= 500000n
        || filter.priceRange === 'high' && price > 500000n)
  })
  return items.sort((a, b) => {
    if (filter.sort === 'rating') return Number(b.rating) - Number(a.rating)
    if (filter.sort !== 'priceAsc' && filter.sort !== 'priceDesc') return 0
    const x = BigInt(a.priceMinor), y = BigInt(b.priceMinor)
    return (x < y ? -1 : x > y ? 1 : 0) * (filter.sort === 'priceDesc' ? -1 : 1)
  })
}
