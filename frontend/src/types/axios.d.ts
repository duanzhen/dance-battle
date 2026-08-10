export {};
declare module 'axios' {
  interface AxiosResponse<T = any> {
    code: number;
    msg: string;
    data: T;
    total: number;
  }
}
