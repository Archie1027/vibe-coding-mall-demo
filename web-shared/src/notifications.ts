export function notify(message: string) {
  window.dispatchEvent(new CustomEvent('zhicai-notice', { detail: message }))
}
