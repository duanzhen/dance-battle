<template>
  <div
    class="flex flex-col h-screen w-screen bg-neutral-950 text-neutral-100 overflow-hidden font-sans select-none selection:bg-amber-500 selection:text-white"
    tabindex="0"
    @keydown.tab.prevent="handleTabKey"
    @keydown="handleRootKeydown"
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
        <span class="font-bold text-sm tracking-wide text-neutral-300 hidden sm:block">赛事大屏</span>
      </div>
      <div class="flex items-center gap-1.5 sm:gap-2 overflow-x-auto no-scrollbar max-w-full">
        <button
          @click="activeTab = 'director'"
          :class="[
            'flex items-center gap-1.5',
            'px-2 sm:px-3 py-1.5 text-xs rounded transition-all duration-200 flex-none',
            activeTab === 'director'
              ? 'bg-amber-500 text-neutral-900 font-medium shadow-lg shadow-amber-500/20'
              : 'bg-neutral-800 text-neutral-400 hover:bg-neutral-700 hover:text-neutral-200'
          ]"
        >
          <MonitorPlay class="w-3.5 h-3.5 hidden sm:block" />
          <span>屏幕控制</span>
        </button>
        <button
          @click="activeTab = 'stage'"
          :class="[
            'flex items-center gap-1.5',
            'px-2 sm:px-3 py-1.5 text-xs rounded transition-all duration-200 flex-none',
            activeTab === 'stage'
              ? 'bg-amber-500 text-neutral-900 font-medium shadow-lg shadow-amber-500/20'
              : 'bg-neutral-800 text-neutral-400 hover:bg-neutral-700 hover:text-neutral-200'
          ]"
        >
          <ListTree class="w-3.5 h-3.5 hidden sm:block" />
          <span>赛段流程</span>
        </button>
        <MobileDirectorEntry :tournament-id="tournamentId" />
        <button
          @click="activeTab = 'config'"
          :class="[
            'flex items-center gap-1.5',
            'px-2 sm:px-3 py-1.5 text-xs rounded transition-all duration-200 flex-none',
            activeTab === 'config'
              ? 'bg-amber-500 text-neutral-900 font-medium shadow-lg shadow-amber-500/20'
              : 'bg-neutral-800 text-neutral-400 hover:bg-neutral-700 hover:text-neutral-200'
          ]"
        >
          <Settings2 class="w-3.5 h-3.5 hidden sm:block" />
          <span>赛事配置</span>
        </button>
        <!-- <div class="w-8 h-8 rounded-full bg-neutral-800 border border-neutral-700 hover:border-amber-500 transition-colors cursor-pointer"></div> -->
      </div>
    </header>

    <!-- 内容区域 -->
    <div class="flex-1 overflow-hidden">
      <KeepAlive>
        <DirectorLayout v-if="activeTab === 'director'" />
        <StageFlow v-else-if="activeTab === 'stage'" @stages-changed="handleStagesChanged" />
        <ConfigPanel v-else-if="activeTab === 'config'" :tournament-id="tournamentId" />
      </KeepAlive>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { MonitorPlay, ListTree, Settings2 } from 'lucide-vue-next';
import { ElMessage } from 'element-plus';
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
// 移动端默认进入「赛事配置」;有历史记录时优先恢复上次的 tab
const activeTab = ref(
  localStorage.getItem('tournamentActiveTab') || (window.innerWidth < 640 ? 'config' : 'director')
);
// 刷新/切换后记住当前 tab
watch(activeTab, (v) => {
  localStorage.setItem('tournamentActiveTab', v);
});

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

// 屏幕控制页的 Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y;输入框内保留浏览器原生撤销
const runUndoOrRedo = async (isRedo) => {
  try {
    const ok = isRedo ? await directorStore.redo() : await directorStore.undo();
    if (ok) ElMessage.success(isRedo ? '已重做' : '已撤销');
  } catch (e) {
    ElMessage.error(e?.message || (isRedo ? '重做失败' : '撤销失败'));
  }
};

const handleRootKeydown = (e) => {
  if (activeTab.value !== 'director') return;
  const target = e.target;
  if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) return;
  const mod = e.ctrlKey || e.metaKey;
  if (!mod) return;
  const key = e.key.toLowerCase();
  if (key === 'z') {
    e.preventDefault();
    runUndoOrRedo(e.shiftKey);
  } else if (key === 'y') {
    e.preventDefault();
    runUndoOrRedo(true);
  }
};

// 赛段链变化(如删除赛段)后,重载大屏场景组件,清除对被删赛段的旧绑定
const handleStagesChanged = async () => {
  if (tournamentId.value != null) {
    await directorStore.loadScenes(String(tournamentId.value));
  }
};
</script>

<style scoped lang="scss">
.no-scrollbar {
  -ms-overflow-style: none;
  scrollbar-width: none;
}
.no-scrollbar::-webkit-scrollbar {
  display: none;
}
</style>
