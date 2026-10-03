import Components from 'unplugin-vue-components/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';
import IconsResolver from 'unplugin-icons/resolver';

export default (path: any) => {
  return Components({
    resolvers: [
      // 自动导入 Element Plus 组件。
      // importStyle: false —— 完整样式已由 assets/styles/index.scss 全局引入,
      // 关闭按组件重复注入,避免其在运行时晚于全局样式加载、把深色覆盖压回默认白底。
      ElementPlusResolver({ importStyle: false }),
      // 自动注册图标组件
      IconsResolver({
        enabledCollections: ['ep']
      })
    ],
    dts: path.resolve(path.resolve(__dirname, '../../src'), 'types', 'components.d.ts')
  });
};
