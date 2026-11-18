export function validLoginUsername(value: string): boolean {
  return value.trim().length > 0 && Array.from(value).length <= 50
}

export function validLoginPassword(value: string): boolean {
  return value.length >= 1 && value.length <= 72 && !/[^\x20-\x7E]/.test(value)
}
