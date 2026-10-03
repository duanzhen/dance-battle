import AutoImport from 'unplugin-auto-import/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';

export default (path: any) => {
  return AutoImport({
    // 自动导入 Vue 相关函数;notifyError 是本项目的统一错误提示(见 utils/request.ts)
    imports: ['vue', 'vue-router', '@vueuse/core', 'pinia', { '@/utils/request': ['notifyError'] }],
    eslintrc: {
      enabled: true,
      filepath: './.eslintrc-auto-import.json',
      globalsPropValue: true
    },
    resolvers: [
      // 自动导入 Element Plus 相关函数 ElMessage, ElMessageBox...
      // 样式统一走全局引入的 element-plus/dist/index.css(见 assets/styles/index.scss),
      // 这里不再按组件重复注入,避免样式加载顺序把自定义深色覆盖压掉。
      ElementPlusResolver({ importStyle: false })
    ],
    vueTemplate: true, // 是否在 vue 模板中自动导入
    dts: path.resolve(path.resolve(__dirname, '../../src'), 'types', 'auto-imports.d.ts')
  });
};
