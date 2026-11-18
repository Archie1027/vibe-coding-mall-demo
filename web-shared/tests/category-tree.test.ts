import test from 'node:test'
import assert from 'node:assert/strict'
import { categoryParentCandidates } from '../src/category-tree.ts'
import type { Category } from '../src/catalog-api.ts'
const node = (id: string, parentId?: string): Category => ({id, parentId, name:id, sort:0, enabled:true, version:0})
const nodes = [node('1'), node('2','1'), node('3','2'), node('4'), node('5','4')]
test('new categories cannot select a third-level parent',()=>{
  assert.deepEqual(categoryParentCandidates(nodes).map(n=>n.id),['1','2','4','5'])
})
test('editing excludes self, descendants, and parents that push subtree beyond level three',()=>{
  assert.deepEqual(categoryParentCandidates(nodes,'1').map(n=>n.id),[])
  assert.deepEqual(categoryParentCandidates(nodes,'2').map(n=>n.id),['1','4'])
  assert.deepEqual(categoryParentCandidates(nodes,'3').map(n=>n.id),['1','2','4','5'])
})
test('malformed cycles and missing parents never become candidates',()=>{
  assert.deepEqual(categoryParentCandidates([node('1','2'),node('2','1'),node('3','99'),node('4')]).map(n=>n.id),['4'])
})
