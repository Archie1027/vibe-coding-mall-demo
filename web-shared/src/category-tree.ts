import type { Category } from './catalog-api.ts'
export function categoryParentCandidates(nodes: Category[], currentId?: string): Category[] {
  const byId = new Map(nodes.map(node => [node.id, node]))
  const descendants = new Set(currentId ? [currentId] : [])
  let changed = true
  while (changed) {
    changed = false
    for (const node of nodes) if (node.parentId && descendants.has(node.parentId) && !descendants.has(node.id)) { descendants.add(node.id); changed = true }
  }
  function depth(id: string, stop?: string): number {
    let cursor: string | null | undefined = id, count = 0
    const seen = new Set<string>()
    while (cursor) {
      if (seen.has(cursor) || !byId.has(cursor)) return Infinity
      seen.add(cursor); count++
      if (cursor === stop) return count
      cursor = byId.get(cursor)!.parentId
    }
    return stop ? Infinity : count
  }
  const height = currentId ? Math.max(1, ...[...descendants].map(id => depth(id, currentId))) : 1
  return nodes.filter(node => !descendants.has(node.id) && depth(node.id) + height <= 3)
}
