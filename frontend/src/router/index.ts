import { createWebHistory, createRouter, RouteRecordRaw } from 'vue-router';
/* Layout */
import Layout from '@/layout/index.vue';

/**
 * Note: 路由配置项
 *
 * hidden: true                     // 当设置 true 的时候该路由不会再侧边栏出现 如401，login等页面，或者如一些编辑页面/edit/1
 * alwaysShow: true                 // 当你一个路由下面的 children 声明的路由大于1个时，自动会变成嵌套的模式--如组件页面
 *                                  // 只有一个时，会将那个子路由当做根路由显示在侧边栏--如引导页面
 *                                  // 若你想不管路由下面的 children 声明的个数都显示你的根路由
 *                                  // 你可以设置 alwaysShow: true，这样它就会忽略之前定义的规则，一直显示根路由
 * redirect: noRedirect             // 当设置 noRedirect 的时候该路由在面包屑导航中不可被点击
 * name:'router-name'               // 设定路由的名字，一定要填写不然使用<keep-alive>时会出现各种问题
 * query: '{"id": 1, "name": "ry"}' // 访问路由的默认传递参数
 * roles: ['admin', 'common']       // 访问路由的角色权限
 * permissions: ['a:a:a', 'b:b:b']  // 访问路由的菜单权限
 * meta : {
    noCache: true                   // 如果设置为true，则不会被 <keep-alive> 缓存(默认 false)
    title: 'title'                  // 设置该路由在侧边栏和面包屑中展示的名字
    icon: 'svg-name'                // 设置该路由的图标，对应路径src/assets/icons/svg
    breadcrumb: false               // 如果设置为false，则不会在breadcrumb面包屑中显示
    activeMenu: '/system/user'      // 当路由设置了该属性，则会高亮相对应的侧边栏。
  }
 */

// 公共路由
export const constantRoutes: RouteRecordRaw[] = [
  {
    path: '/redirect',
    component: Layout,
    hidden: true,
    children: [
      {
        path: '/redirect/:path(.*)',
        component: () => import('@/views/redirect/index.vue')
      }
    ]
  },
  {
    path: '/login',
    component: () => import('@/views/login.vue'),
    hidden: true
  },
  {
    path: '/:pathMatch(.*)*',
    component: () => import('@/views/error/404.vue'),
    hidden: true
  },
  {
    path: '/401',
    component: () => import('@/views/error/401.vue'),
    hidden: true
  },
  {
    path: '',
    redirect: '/game/list',
    hidden: true
  },
  {
    path: '/game/list',
    component: () => import('@/views/game/list/index.vue'),
    name: 'GameList',
    meta: { title: '赛事管理', icon: 'user', affix: true },
    hidden: true
  },
  {
    path: '/tournament',
    hidden: true,
    redirect: 'noredirect',
    children: [
      {
        path: 'config',
        component: () => import('@/views/game/tournament/index.vue'),
        name: 'TournamentConfig',
        meta: { title: '赛事配置', icon: 'user' }
      },
      {
        path: 'projection',
        component: () => import('@/views/game/tournament/ProjectionView.vue'),
        name: 'ProjectionView',
        meta: { title: '赛事大屏幕', icon: 'user' }
      },
      {
        path: 'referee',
        component: () => import('@/views/game/referee/index.vue'),
        name: 'Referee',
        meta: { title: '裁判', icon: 'user' }
      },
      {
        path: 'mobile-director',
        component: () => import('@/views/game/mobile-director/index.vue'),
        name: 'MobileDirector',
        meta: { title: '手机导播台', icon: 'user' }
      },
      {
        path: 'referee-scoring',
        component: () => import('@/views/game/referee-scoring/index.vue'),
        name: 'RefereeScoring',
        meta: { title: '裁判判罚', icon: 'user' }
      }
    ]
  },
  {
    path: '/game-detail',
    component: () => import('@/views/game/list/GameConfig.vue'),
    name: 'GameDetail',
    hidden: true,
    meta: { title: '赛事详情' }
  }
];

// 动态路由，基于用户权限动态去加载
export const dynamicRoutes: RouteRecordRaw[] = [];

/**
 * 创建路由
 */
const router = createRouter({
  history: createWebHistory(import.meta.env.VITE_APP_CONTEXT_PATH),
  routes: constantRoutes,
  // 刷新时，滚动条位置还原
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) {
      return savedPosition;
    }
    return { top: 0 };
  }
});

export default router;
