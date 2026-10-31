// 仅允许本站明确的资料路径，不能将登录返回地址当作任意跳转授权。
export function safeReturnPath(value: unknown): string {
  return typeof value === 'string' &&
    (value === '/app' ||
      /^\/app\/resumes\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value))
    ? value
    : '/app'
}
