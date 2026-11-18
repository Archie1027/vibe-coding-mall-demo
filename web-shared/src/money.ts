/** API amounts are integer strings in CNY fen. Never pass through Number. */
export function formatMinor(value: string): string {
  if (!/^-?\d+$/.test(value)) throw new TypeError('金额必须是人民币分整数字符串')
  const amount = BigInt(value)
  const absolute = amount < 0n ? -amount : amount
  return `${amount < 0n ? '-' : ''}¥ ${(absolute / 100n).toLocaleString('zh-CN')}.${(absolute % 100n).toString().padStart(2, '0')}`
}

export function yuanToMinor(value: string): string {
  if (!/^\d+(?:\.\d{1,2})?$/.test(value)) throw new TypeError('金额最多保留两位小数')
  const [whole = '0', cents = ''] = value.split('.')
  return (BigInt(whole) * 100n + BigInt(cents.padEnd(2, '0'))).toString()
}

export function sumLines(lines: { priceMinor: string; quantity: number }[]): string {
  return lines.reduce((sum, line) => {
    if (!Number.isSafeInteger(line.quantity) || line.quantity <= 0 || !/^\d+$/.test(line.priceMinor)) {
      throw new TypeError('请输入正整数数量和有效金额')
    }
    return sum + BigInt(line.priceMinor) * BigInt(line.quantity)
  }, 0n).toString()
}
