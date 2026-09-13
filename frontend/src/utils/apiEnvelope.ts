/**
 * 后端响应信封统一处理。
 *
 * <p>本项目所有接口返回同一结构:
 * {@code { code, msg, data }}(列表接口是 TableDataInfo,同样是 {@code data} 承载数组,另带 total)。
 * 但前端有三套 axios 实例:管理端 {@code utils/request}、导播台 {@code api/game/director}、
 * 裁判端 {@code api/game/referee/scoring}。前两者过去一个做了响应拦截(拿到信封)、
 * 一个没做(拿到 axios 原始响应),导致同一份数据在两处要用不同方式拆包,容易写错。</p>
 *
 * <p>这里统一约定:所有实例都挂 {@link attachEnvelope},请求结果就是信封对象;
 * 业务数据一律通过 {@link payloadOf} / {@link listOf} 读取,不再各写各的。</p>
 */
import type { AxiosInstance } from 'axios';

/** 给自建 axios 实例挂上与管理端一致的响应拦截:请求结果 = 信封对象 */
export function attachEnvelope<T extends AxiosInstance>(instance: T): T {
  instance.interceptors.response.use((response) => response.data);
  return instance;
}

/**
 * 判断是不是后端响应信封 { code, msg, data(, total) }
 */
function isEnvelope(value: any): boolean {
  return value !== null && typeof value === 'object' && 'code' in value && 'data' in value;
}

/**
 * 取信封里的业务数据,兼容三种形态:
 * 1) 已挂响应拦截的实例 → 结果就是信封 { code, msg, data };
 * 2) 未挂拦截的实例 → 结果是 axios 原始响应 { data: 信封 };
 * 3) 直接传入业务数据(数组/对象/数字)时原样返回。
 */
export function payloadOf<T = any>(resp: any): T | null {
  if (resp === null || resp === undefined) {
    return null;
  }
  if (isEnvelope(resp)) {
    return (resp.data ?? null) as T | null;
  }
  if (isEnvelope(resp.data)) {
    return (resp.data.data ?? null) as T | null;
  }
  return resp as T;
}

/** 取列表数据:兼容 R<List>、TableDataInfo({data:[]}) 与未拆包的信封 */
export function listOf<T = any>(resp: any): T[] {
  const payload: any = payloadOf(resp);
  if (Array.isArray(payload)) return payload as T[];
  if (Array.isArray(payload?.data)) return payload.data as T[];
  if (Array.isArray(payload?.rows)) return payload.rows as T[];
  return [];
}
