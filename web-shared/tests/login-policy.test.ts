import test from 'node:test'
import assert from 'node:assert/strict'
import { validLoginPassword, validLoginUsername } from '../src/login-policy.ts'

test('usernames support Chinese, English, mixed names and spaces without an ASCII whitelist', () => {
  for (const value of ['张', '张三', 'Z', '张三Alice123', 'Alice Smith', '甲'.repeat(50), '😀'.repeat(50)]) assert.equal(validLoginUsername(value), true)
})
test('only blank usernames and names exceeding the 50 character storage capacity are rejected', () => {
  for (const value of ['', '  ', '\t', 'a'.repeat(51), '😀'.repeat(51)]) assert.equal(validLoginUsername(value), false)
})

test('simple digits, either letter case and English symbols are accepted without complexity requirements', () => {
  for (const value of ['1', '123456', 'abc', 'ABC', '!@#$%^&*()_+-=[]{};:\'",.<>/?`~\\|', 'a b', ' ']) assert.equal(validLoginPassword(value), true)
})
test('password boundaries are one through 72 printable ASCII characters', () => {
  assert.equal(validLoginPassword(''), false)
  assert.equal(validLoginPassword('a'.repeat(72)), true)
  assert.equal(validLoginPassword('a'.repeat(73)), false)
})
test('Chinese and other characters outside the supported English keyboard range are rejected', () => {
  for (const value of ['中文密码测试', 'abc中123', '１２３', 'é', '😀']) assert.equal(validLoginPassword(value), false)
})
test('control characters cannot enter the login request', () => {
  for (const value of ['abc\n123', 'abc\r123', 'abc\n', 'abc\r', '\t', '\0', '\x7f']) assert.equal(validLoginPassword(value), false)
})
