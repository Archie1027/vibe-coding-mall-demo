export interface OrderPreview { status: string; text: string; date: string; warehouse: string; source: string }
export interface OrderFilters { status?: string; keyword?: string; period?: string; warehouse?: string; source?: string }
export function matchesOrder(row: OrderPreview, filters: OrderFilters, today = new Date().toISOString().slice(0, 10)): boolean {
  const days = (Date.parse(`${today}T00:00:00Z`) - Date.parse(`${row.date}T00:00:00Z`)) / 86400000
  const dateMatch = !filters.period || filters.period === 'all' || (filters.period === 'today' ? days === 0 : days >= 0 && days < Number(filters.period))
  return dateMatch && (!filters.status || filters.status === '全部' || row.status === filters.status)
    && (!filters.keyword?.trim() || row.text.toLowerCase().includes(filters.keyword.trim().toLowerCase()))
    && (!filters.warehouse || filters.warehouse === '全部仓库' || row.warehouse === filters.warehouse)
    && (!filters.source || filters.source === '全部来源' || row.source === filters.source)
}
