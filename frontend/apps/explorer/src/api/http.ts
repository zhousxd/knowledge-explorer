/**
 * fetch 封装:统一 baseURL /api、Bearer 注入、envelope 解包与错误归一。
 * 从工作台 http 封装复制改造:401 通过注入的 onUnauthorized 回调上报(由 auth store
 * 注册),避免与 router 直接耦合;storage 键改用 ke_ex_* 前缀与工作台隔离。
 */
import type { ApiResponse } from './types';

/** 业务/网络错误的统一载体:code=-1 网络异常,401 会话失效,其余透传后端 code */
export class ApiError extends Error {
  readonly code: number;
  readonly traceId: string;

  constructor(code: number, message: string, traceId = '') {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.traceId = traceId;
  }
}

type UnauthorizedHandler = () => void;
let onUnauthorized: UnauthorizedHandler = () => {};

/** 注册 401 统一处理回调(应用启动时由 auth store 调用) */
export function setUnauthorizedHandler(handler: UnauthorizedHandler): void {
  onUnauthorized = handler;
}

/** 与 stores/auth.ts 共用的 token 存储键(探索端独立前缀) */
export const TOKEN_KEY = 'ke_ex_token';

const BASE_URL = '/api';

function buildHeaders(isForm: boolean): Record<string, string> {
  // FormData(文件上传)不能手动设 Content-Type:由浏览器自动生成含 boundary 的 multipart 头
  const headers: Record<string, string> = isForm ? {} : { 'Content-Type': 'application/json' };
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) headers.Authorization = `Bearer ${token}`;
  return headers;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const isForm = body instanceof FormData;
  let resp: Response;
  try {
    resp = await fetch(`${BASE_URL}${path}`, {
      method,
      headers: buildHeaders(isForm),
      body: body === undefined ? undefined : isForm ? body : JSON.stringify(body)
    });
  } catch {
    throw new ApiError(-1, '网络异常');
  }

  // 非 JSON 响应(如网关 5xx 页面)按状态码归一
  let envelope: Partial<ApiResponse<T>> = {};
  try {
    envelope = (await resp.json()) as ApiResponse<T>;
  } catch {
    // 保留空信封,走下方兜底分支
  }

  if (resp.status === 401) {
    onUnauthorized();
    throw new ApiError(401, envelope.message ?? '登录已失效', envelope.traceId ?? '');
  }
  if (envelope.code !== 0) {
    throw new ApiError(envelope.code ?? resp.status, envelope.message ?? '服务异常', envelope.traceId ?? '');
  }
  return envelope.data as T;
}

export const http = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
  del: <T>(path: string) => request<T>('DELETE', path)
};
