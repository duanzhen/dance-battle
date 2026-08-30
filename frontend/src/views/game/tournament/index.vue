<template>
  <div
    class="flex flex-col h-screen w-screen bg-neutral-950 text-neutral-100 overflow-hidden font-sans select-none selection:bg-amber-500 selection:text-white"
    tabindex="0"
    @keydown.tab.prevent="handleTabKey"
  >
    <header class="h-12 flex-none bg-neutral-900 border-b border-neutral-800 flex items-center justify-between px-4 z-20">
      <div class="flex items-center gap-2">
        <div
          @click="openHome"
          title="返回赛事首页"
          class="w-6 h-6 bg-amber-500 rounded flex items-center justify-center shadow-lg shadow-amber-500/20 cursor-pointer hover:bg-amber-400 transition-colors"
        >
          <img
            :src="logoFlat"
            alt="无败"
            class="w-4 h-4 object-contain"
            style="filter: brightness(0) invert(1);"
          />
        </div>
        <span class="font-bold text-sm tracking-wide text-neutral-300">赛事大屏</span>
      </div>
      <div class="flex items-center gap-2">
        <button
          @click="activeTab = 'director'"
          :class="[
            'flex items-center gap-1.5',
            'px-3 py-1.5 text-xs rounded transition-all duration-200',
            activeTab === 'director'
              ? 'bg-amber-500 text-neutral-900 font-medium shadow-lg shadow-amber-500/20'
              : 'bg-neutral-800 text-neutral-400 hover:bg-neutral-700 hover:text-neutral-200'
          ]"
        >
          <MonitorPlay class="w-3.5 h-3.5" />
          屏幕控制
        </button>
        <button
          @click="activeTab = 'stage'"
          :class="[
            'flex items-center gap-1.5',
            'px-3 py-1.5 text-xs rounded transition-all duration-200',
            activeTab === 'stage'
              ? 'bg-amber-500 text-neutral-900 font-medium shadow-lg shadow-amber-500/20'
              : 'bg-neutral-800 text-neutral-400 hover:bg-neutral-700 hover:text-neutral-200'
          ]"
        >
          <ListTree class="w-3.5 h-3.5" />
          赛段流程
        </button>
        <MobileDirectorEntry :tournament-id="tournamentId" />
        <button
          @click="activeTab = 'config'"
          :class="[
            'flex items-center gap-1.5',
            'px-3 py-1.5 text-xs rounded transition-all duration-200',
            activeTab === 'config'
              ? 'bg-amber-500 text-neutral-900 font-medium shadow-lg shadow-amber-500/20'
              : 'bg-neutral-800 text-neutral-400 hover:bg-neutral-700 hover:text-neutral-200'
          ]"
        >
          <Settings2 class="w-3.5 h-3.5" />
          赛事配置
        </button>
        <!-- <div class="w-8 h-8 rounded-full bg-neutral-800 border border-neutral-700 hover:border-amber-500 transition-colors cursor-pointer"></div> -->
      </div>
    </header>

    <!-- 内容区域 -->
    <div class="flex-1 overflow-hidden">
      <KeepAlive>
        <DirectorLayout v-if="activeTab === 'director'" />
        <StageFlow v-else-if="activeTab === 'stage'" />
        <ConfigPanel v-else-if="activeTab === 'config'" :tournament-id="tournamentId" />
      </KeepAlive>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { MonitorPlay, ListTree, Settings2 } from 'lucide-vue-next';
import { useDirectorStore } from '@/store/modules/directorStore';
import DirectorLayout from './DirectorLayout.vue';
import StageFlow from './StageFlow.vue';
import ConfigPanel from './ConfigPanel.vue';
import logoFlat from '@/assets/logo/logo_flat.png';
import MobileDirectorEntry from './MobileDirectorEntry.vue';

const route = useRoute();
const router = useRouter();
const directorStore = useDirectorStore();

// 当前激活的 tab
const activeTab = ref('director');

// 从 URL 获取 tournamentId
const tournamentId = ref<string | number | null>(null);

// 点击 logo 切换到赛事首页标签:
// 列表页(/game/list)加载时会把所在标签命名为 gameListTab,
// 这里用同名 window.open 复用该标签并切换过去;没开过则新开标签。
// URL 追加 #gameList 片段:目标标签已在 /game/list 时属于同文档导航,
// 只切换焦点不刷新页面;列表页收到该 hash 后会自动清理。
const openHome = () => {
  const url = router.resolve({ path: '/game/list' }).href;
  window.open(url + '#gameList', 'gameListTab');
};

onMounted(async () => {
  // 从 query.id 中获取 tournamentId
  const id = route.query.id;

  if (id) {
    tournamentId.value = id;
    // 从后端加载场景数据
    await directorStore.loadScenes(id);
    console.log(`📂 已加载赛事场景数据: tournamentId = ${id}`);
  } else {
    console.warn('⚠️ URL 中未找到 id 参数');
  }
});

// 处理 Tab 键切换
const handleTabKey = () => {
  activeTab.value = activeTab.value === 'director' ? 'stage' : activeTab.value === 'stage' ? 'config' : 'director';
};
</script>

<style scoped lang="scss">
// 全屏展示，不需要额外样式
</style>
