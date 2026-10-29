// The first slice has one protected destination. Do not trust a URL from the query string.
export function safeReturnPath(value: unknown): string {
  return value === '/app' ? value : '/app'
}
