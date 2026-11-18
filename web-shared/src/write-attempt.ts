/** A form's in-memory idempotency identity; never auto-retry a business write. */
export function createWriteAttempt(newKey: () => string = () => crypto.randomUUID()) {
  let signature: string | undefined, key: string | undefined
  return {
    prepare(method: string, path: string, body: string): string {
      const next = JSON.stringify([method, path, body])
      if (next !== signature || !key) { signature = next; key = newKey() }
      return key
    },
    clear() { signature = undefined; key = undefined },
  }
}
