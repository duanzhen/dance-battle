<template>
  <div class="min-h-screen bg-neutral-950 text-neutral-100 font-sans selection:bg-amber-500 selection:text-white">
    <header class="bg-neutral-900/80 backdrop-blur-md border-b border-neutral-800 sticky top-0 z-50">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
        <div class="flex items-center gap-3">
          <div class="w-8 h-8 bg-amber-500 rounded-lg flex items-center justify-center shadow-lg shadow-amber-500/20">
            <img
              :src="logoFlat"
              alt="无败"
              class="w-5 h-5 object-contain"
              style="filter: brightness(0) invert(1);"
            />
          </div>
          <span class="font-bold text-xl tracking-tight text-white">赛事管理</span>
        </div>

        <div class="flex items-center gap-2">
          <!-- 用户菜单:修改密码 / 退出登录(暂无消息提醒,通知图标已移除) -->
          <el-dropdown trigger="click" popper-class="user-menu-popper" @command="handleUserCommand">
            <div
              class="w-9 h-9 rounded-full bg-neutral-800 border-2 border-neutral-700 shadow-sm overflow-hidden hover:border-amber-500/60 hover:shadow-amber-500/20 cursor-pointer flex items-center justify-center"
            >
              <img :src="userStore.avatar" alt="User" class="w-full h-full object-cover" />
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="changePassword">
                  <span class="flex items-center gap-2">
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z"
                      />
                    </svg>
                    修改密码
                  </span>
                </el-dropdown-item>
                <el-dropdown-item divided command="logout">
                  <span class="flex items-center gap-2 text-red-400">
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1"
                      />
                    </svg>
                    退出登录
                  </span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </header>

    <main class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <div class="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
        <div>
          <h1 class="text-3xl font-bold text-white tracking-tight">赛事大厅</h1>
          <p class="text-neutral-400 text-sm mt-1">发现并参与最热门的街舞赛事</p>
        </div>
        <button
          @click="showForm = true"
          class="relative bg-gradient-to-b from-amber-500 to-amber-600 hover:from-amber-400 hover:to-amber-500 text-white px-5 py-2.5 rounded-lg font-bold transition-all shadow-lg shadow-amber-900/40 hover:shadow-amber-600/40 hover:-translate-y-0.5 active:translate-y-0 active:scale-[0.98] flex items-center gap-2 group overflow-hidden"
        >
          <span class="absolute inset-0 bg-gradient-to-r from-transparent via-white/20 to-transparent -translate-x-full group-hover:translate-x-full transition-transform duration-700"></span>
          <svg class="w-4 h-4 group-hover:rotate-90 transition-transform duration-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M12 4v16m8-8H4" />
          </svg>
          <span class="relative">创建赛事</span>
        </button>
      </div>

      <div class="bg-neutral-900 rounded-xl border border-neutral-800 p-4 shadow-xl shadow-black/20 mb-8">
        <div class="flex flex-col lg:flex-row gap-4 justify-between">
          <!-- 注意:overflow-x-auto 会裁掉四周溢出的阴影/光圈,需留上下左右内边距 -->
          <div class="flex overflow-x-auto py-2 px-2 gap-1 no-scrollbar">
            <button
              v-for="tab in tabs"
              :key="tab.value"
              @click="currentTab = tab.value"
              :class="[
                'px-4 py-2 rounded-lg text-sm font-medium whitespace-nowrap transition-all duration-200',
                currentTab === tab.value
                  ? // 选中态：深色背景 + Amber 文字 + Amber 边框
                    'bg-neutral-800 text-amber-500 ring-1 ring-amber-500/50 shadow-lg shadow-amber-900/20'
                  : // 未选中：灰色文字 + Hover 变亮
                    'text-neutral-400 hover:text-neutral-200 hover:bg-neutral-800'
              ]"
            >
              {{ tab.label }}
            </button>
          </div>

          <div class="relative w-full lg:w-80">
            <div class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
              <svg class="h-5 w-5 text-neutral-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
              </svg>
            </div>
            <input
              v-model="searchQuery"
              type="text"
              class="block w-full pl-10 pr-3 py-2 border border-neutral-700 rounded-lg leading-5 bg-neutral-950 text-neutral-200 placeholder-neutral-600 focus:outline-none focus:bg-neutral-900 focus:ring-1 focus:ring-amber-500 focus:border-amber-500 transition-all sm:text-sm"
              placeholder="搜索赛事名称"
            />
          </div>
        </div>
      </div>

      <div v-if="filteredTournaments.length > 0" class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-4">
        <div
          v-for="item in filteredTournaments"
          :key="item.id"
          @click="goToDetail(item.id)"
          class="group relative bg-neutral-900 rounded-xl border border-neutral-800 overflow-hidden hover:shadow-2xl hover:shadow-amber-900/20 hover:border-amber-500/50 transition-all duration-300 cursor-pointer aspect-[3/4]"
        >
          <!-- 背景图 -->
          <div class="absolute inset-0 bg-gradient-to-br from-neutral-800 to-neutral-700 group-hover:scale-105 transition-transform duration-500">
            <div
              class="absolute inset-0 opacity-10"
              style="background-image: radial-gradient(#ffffff 1px, transparent 1px); background-size: 16px 16px"
            ></div>
          </div>

          <!-- 状态标签 -->
          <div class="absolute top-3 right-3 z-10">
            <span
              :class="[
                'px-2 py-0.5 rounded-full text-xs font-bold uppercase tracking-wider shadow-lg backdrop-blur-md border',
                statusConfig[item.status].class
              ]"
            >
              {{ statusConfig[item.status].label }}
            </span>
          </div>

          <!-- 内容 -->
          <div class="absolute inset-0 flex flex-col p-4 z-10">
            <!-- 游戏类型 -->
            <div class="mb-2">
              <span class="text-xs font-bold text-amber-500 bg-amber-500/10 border border-amber-500/20 px-2 py-0.5 rounded">
                {{ item.gameType }}
              </span>
            </div>

            <!-- 赛事标题 -->
            <h3 class="text-lg font-bold text-white mb-2 line-clamp-2 group-hover:text-amber-500 transition-colors flex-1">
              {{ item.title }}
            </h3>

            <!-- 描述 -->
            <p class="text-neutral-400 text-xs line-clamp-2 mb-3 leading-relaxed">
              {{ item.description }}
            </p>

            <!-- 底部信息 -->
            <div class="mt-auto space-y-2">
              <div class="flex items-center text-neutral-500 text-xs font-medium">
                <svg class="w-3 h-3 mr-1.5 text-neutral-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z"
                  />
                </svg>
                {{ item.startDate }}
              </div>

              <div class="flex items-center text-neutral-500 text-xs">
                <svg class="w-3 h-3 mr-1.5 text-amber-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z"
                  />
                </svg>
                {{ item.participants }} <span class="text-neutral-600 mx-1">/</span> {{ item.maxParticipants }}
              </div>
            </div>
          </div>

          <!-- 进度条（进行中的赛事） -->
          <div v-if="item.status === 'LIVE'" class="absolute bottom-0 left-0 right-0 h-1 bg-neutral-800">
            <div class="h-full bg-amber-500 shadow-[0_0_10px_rgba(245,158,11,0.5)]" :style="{ width: item.progress + '%' }"></div>
          </div>

          <!-- 悬停遮罩 -->
          <div class="absolute inset-0 bg-amber-500/0 group-hover:bg-amber-500/5 transition-colors pointer-events-none"></div>
        </div>
      </div>

      <div v-else class="flex flex-col items-center justify-center py-20 text-center">
        <div class="w-24 h-24 rounded-full flex items-center justify-center mb-5 border border-neutral-800 bg-gradient-to-b from-neutral-900 to-neutral-950 shadow-xl shadow-black/40">
          <svg class="w-10 h-10 text-neutral-700" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M9.172 16.172a4 4 0 015.656 0M9 10h.01M15 10h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
            />
          </svg>
        </div>
        <h3 class="text-lg font-bold text-white tracking-tight">未找到相关赛事</h3>
        <p class="text-neutral-500 mt-1 max-w-sm text-sm">尝试调整搜索关键词或更改筛选状态</p>
        <button
          @click="resetFilters"
          class="mt-6 px-5 py-2 rounded-lg text-sm font-medium text-amber-400 bg-amber-500/10 border border-amber-500/30 hover:bg-amber-500/20 hover:border-amber-500/50 hover:text-amber-300 active:scale-95 transition-all flex items-center gap-1.5"
        >
          <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
          </svg>
          清除筛选条件
        </button>
      </div>
    </main>

    <!-- 表单对话框 -->
    <TournamentForm v-model="showForm" @submit-success="handleFormSuccess" />
    <!-- 修改密码对话框 -->
    <PasswordDialog ref="passwordDialogRef" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessageBox } from 'element-plus';
import { useUserStore } from '@/store/modules/user';
import PasswordDialog from '@/components/PasswordDialog/index.vue';
import TournamentForm from './TournamentForm.vue';
import { listTournament } from '@/api/game/tournament';
import logoFlat from '@/assets/logo/logo_flat.png';
import { TournamentVO } from '@/api/game/tournament/types';

const router = useRouter();
const userStore = useUserStore();
const passwordDialogRef = ref<InstanceType<typeof PasswordDialog>>();

// 右上角用户菜单:修改密码 / 退出登录
const handleUserCommand = async (command: string) => {
  if (command === 'changePassword') {
    passwordDialogRef.value?.open();
    return;
  }
  if (command === 'logout') {
    await ElMessageBox.confirm('确定退出登录吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    });
    await userStore.logout();
    router.replace({
      path: '/login',
      query: { redirect: encodeURIComponent(router.currentRoute.value.fullPath || '/') }
    });
  }
};

// --- 表单显示控制 ---
const showForm = ref(false);

// --- 定义页面使用的赛事数据结构 ---
interface TournamentItem {
  id: string | number;
  title: string;
  description: string;
  status: string;
  gameType: string;
  format: string;
  participants: number;
  maxParticipants: number;
  startDate: string;
  progress: number;
}

// --- 数据状态 ---
const tournaments = ref<TournamentItem[]>([]);
const loading = ref(false);

// --- 数据适配器: 将API返回的数据转换为页面需要的格式 ---
const adaptTournamentData = (apiData: TournamentVO[]): TournamentItem[] => {
  return apiData.map((item) => {
    // 根据status数字转换为对应的字符串状态
    let statusStr = 'REGISTERING';
    if (item.status === 1) statusStr = 'LIVE';
    if (item.status === 2) statusStr = 'ENDED';

    return {
      id: item.id,
      title: item.name,
      description: item.remark || '暂无描述',
      status: statusStr,
      gameType: '街舞赛事',
      format: '积分赛',
      participants: 0,
      maxParticipants: 100,
      startDate: '2024-01-01',
      progress: item.status === 1 ? 50 : item.status === 2 ? 100 : 0
    };
  });
};

// --- 加载赛事数据 ---
const loadTournaments = async () => {
  try {
    loading.value = true;
    const response = await listTournament();
    const adaptedData = adaptTournamentData(response.data || []);
    tournaments.value = adaptedData;
  } catch (error) {
    console.error('加载赛事数据失败:', error);
    tournaments.value = [];
  } finally {
    loading.value = false;
  }
};

// --- 组件挂载时加载数据 ---
onMounted(() => {
  loadTournaments();
});

// --- 表单提交成功回调 ---
const handleFormSuccess = () => {
  // 刷新赛事列表
  loadTournaments();
};

// --- 状态配置 (针对深色背景优化) ---
const statusConfig = {
  'REGISTERING': {
    label: '报名中',
    class: 'bg-amber-500/10 text-amber-500 border-amber-500/20'
  },
  'LIVE': {
    label: '进行中',
    // 进行中增加一点红色或绿色，这里用绿色形成对比，或者继续用 Amber 但更亮
    class: 'bg-green-500/10 text-green-400 border-green-500/20 animate-pulse'
  },
  'ENDED': {
    label: '已结束',
    class: 'bg-neutral-800 text-neutral-500 border-neutral-700'
  }
};

const tabs = [
  { label: '全部赛事', value: 'ALL' },
  { label: '正在报名', value: 'REGISTERING' },
  { label: '比赛进行中', value: 'LIVE' },
  { label: '历史回顾', value: 'ENDED' }
];

const currentTab = ref('ALL');
const searchQuery = ref('');

const filteredTournaments = computed(() => {
  return tournaments.value.filter((t: TournamentItem) => {
    const statusMatch = currentTab.value === 'ALL' || t.status === currentTab.value;
    const query = searchQuery.value.toLowerCase();
    const searchMatch = t.title.toLowerCase().includes(query) || t.gameType.toLowerCase().includes(query) || t.format.toLowerCase().includes(query);
    return statusMatch && searchMatch;
  });
});

const resetFilters = () => {
  currentTab.value = 'ALL';
  searchQuery.value = '';
};

// 点击赛事:直接新窗口打开赛事大屏(配置入口在赛事大屏右上角「赛事配置」)
const goToDetail = (id: string | number) => {
  console.log('点击详情按钮，赛事 ID:', id);
  const url = router.resolve({
    path: '/tournament/config',
    query: { id: String(id) }
  }).href;
  window.open(url, '_blank');
};
</script>

<style>
/* 注意：这里去掉了 scoped，目的是为了让 body 的滚动条也生效。
  如果你只想让当前组件内的某个容器生效，可以加 scoped 并指定类名。
*/

/* --- WebKit 浏览器 (Chrome, Safari, Edge) --- */

/* 1. 滚动条整体宽度 */
::-webkit-scrollbar {
  width: 8px; /* 纵向滚动条宽度 */
  height: 8px; /* 横向滚动条高度 */
}

/* 2. 滚动条轨道 (背景) */
::-webkit-scrollbar-track {
  background: #0a0a0a; /* 对应 Tailwind neutral-950 */
  border-radius: 4px;
}

/* 3. 滚动条滑块 (句柄) */
::-webkit-scrollbar-thumb {
  background: #404040; /* 对应 Tailwind neutral-700 */
  border-radius: 4px;
  border: 2px solid #0a0a0a; /* 加个边框让它看起来像悬浮的 */
}

/* 4. 滑块悬停状态 - 变成点缀色 Amber */
::-webkit-scrollbar-thumb:hover {
  background: #d97706; /* 对应 Tailwind amber-600 */
}

/* --- Firefox 浏览器 --- */
html {
  /* 颜色: 滑块 轨道 */
  scrollbar-color: #404040 #0a0a0a;
  scrollbar-width: thin; /* 变细 */
}

/* 右上角用户下拉菜单:深色主题 */
.user-menu-popper.el-popper {
  background: #171717;
  border: 1px solid #262626;
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.45);
  /* 覆盖 Element Plus 默认悬停变量,统一为深色底 + 琥珀色悬停 */
  --el-dropdown-menuItem-hover-fill: rgba(245, 158, 11, 0.12);
  --el-dropdown-menuItem-hover-color: #fbbf24;
}
.user-menu-popper .el-dropdown-menu {
  background: #171717;
}
.user-menu-popper .el-dropdown-menu__item {
  color: #d4d4d4;
  font-size: 13px;
  /* 关闭默认过渡动画,避免悬停时背景/颜色来回切换导致闪烁 */
  transition: none !important;
}
.user-menu-popper .el-dropdown-menu__item:not(.is-disabled):hover {
  background-color: rgba(245, 158, 11, 0.12) !important;
  color: #fbbf24 !important;
}
.user-menu-popper .el-dropdown-menu__item--divided {
  border-top-color: #262626;
}
.user-menu-popper .el-popper__arrow::before {
  background: #171717;
  border-color: #262626;
}
</style>
