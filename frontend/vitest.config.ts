import { defineConfig } from 'vitest/config';

/**
 * 单测专用配置:刻意不加载 vite.config.ts 的应用插件(unocss / auto-import / icons 等)。
 *
 * <p>原因:unplugin-auto-import 的 dts 生成会按「本次参与构建的文件集合」重写
 * `.eslintrc-auto-import.json`;vitest 只跑单测文件时会把这个提交进仓库的生成物改小,
 * 留下无关 diff。单测只覆盖纯 TS 逻辑(SSE 通道),用最小配置即可,别名用相对路径导入。</p>
 */
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts']
  }
});
