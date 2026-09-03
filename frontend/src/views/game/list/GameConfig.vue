<template>
  <div class="min-h-screen bg-[#0a0a0a] text-neutral-200 flex flex-col font-sans">
    <header class="h-16 border-b border-neutral-800 bg-neutral-900/50 backdrop-blur flex items-center justify-between px-6 sticky top-0 z-20">
      <div class="flex items-center gap-3">
        <button
          @click="goBackToLobby"
          class="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm text-neutral-400 bg-neutral-800 hover:text-amber-400 hover:bg-neutral-700/60 border border-neutral-700 hover:border-amber-500/40 transition-all cursor-pointer"
          title="返回赛事大厅"
        >
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 19l-7-7 7-7" />
          </svg>
          <span>返回赛事大厅</span>
        </button>
        <div class="p-2 bg-amber-500/10 rounded-lg text-amber-500">
          <Settings2 class="w-5 h-5" />
        </div>
        <h1 class="text-lg font-bold tracking-wide">赛事配置中心</h1>
      </div>
      <div class="flex items-center gap-2">
        <button
          @click="openProjection"
          class="px-4 py-2 bg-amber-600 hover:bg-amber-500 text-white text-sm font-bold rounded-lg flex items-center gap-2 shadow-lg shadow-amber-900/20 transition-all"
        >
          <Tv class="w-4 h-4" /> 赛事大屏
        </button>
        <button
          @click="openDirectorQr"
          class="px-4 py-2 bg-neutral-800 hover:bg-neutral-700 text-neutral-200 text-sm font-bold rounded-lg flex items-center gap-2 border border-neutral-700 hover:border-neutral-600 transition-all"
        >
          <Smartphone class="w-4 h-4" /> 手机导播台
        </button>
      </div>
    </header>

    <main class="flex-1 max-w-7xl mx-auto w-full p-6">
      <div class="flex items-center gap-1 mb-8 bg-neutral-900 p-1 rounded-lg border border-neutral-800 w-fit">
        <button
          v-for="tab in tabs"
          :key="tab.id"
          @click="currentTab = tab.id"
          class="px-5 py-2 text-sm font-medium rounded-md transition-all duration-200 flex items-center gap-2"
          :class="currentTab === tab.id ? 'bg-amber-500 text-neutral-900 shadow-md' : 'text-neutral-500 bg-neutral-950 hover:text-neutral-200'"
        >
          <component :is="tab.icon" class="w-4 h-4" />
          {{ tab.label }}
        </button>
      </div>

      <div class="animate-fade-in">
        <section v-if="currentTab === 'info'">
          <GameInfo v-model="matchInfo" />
        </section>

        <section v-else-if="currentTab === 'players'">
          <PlayerEditor :tournament-id="tournamentId"></PlayerEditor>
        </section>

        <section v-else-if="currentTab === 'referees'">
          <RefereeEditor :tournament-id="tournamentId"></RefereeEditor>
        </section>
      </div>
    </main>

    <!-- 手机导播台扫码弹窗 -->
    <Teleport to="body">
      <div
        v-if="showDirectorQr"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
        @click.self="closeDirectorQr"
      >
        <div class="bg-neutral-900 border border-neutral-700 rounded-xl w-full max-w-sm mx-4 shadow-2xl" @click.stop>
          <div class="px-5 py-4 border-b border-neutral-800 flex items-center justify-between">
            <h3 class="text-sm font-bold text-neutral-100">手机导播台扫码入口</h3>
            <button @click="closeDirectorQr" class="dialog-close-btn">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
          <div class="p-6 flex flex-col items-center gap-4">
            <p class="text-xs text-neutral-400">
              赛事 <span class="text-amber-400 font-bold">{{ matchInfo.title || '未命名' }}</span> 扫码进入手机导播台
            </p>
            <div class="bg-white p-3 rounded-lg">
              <img
                v-if="directorQrUrl"
                :src="directorQrUrl"
                alt="QR Code"
                class="w-48 h-48 cursor-pointer transition-transform hover:scale-105"
                title="点击在浏览器新窗口打开"
                @click="openQrInNewWindow"
              />
              <div v-else class="w-48 h-48 flex items-center justify-center text-neutral-500 text-xs">二维码加载中...</div>
            </div>
            <p class="text-[10px] text-neutral-500">点击二维码在浏览器新窗口打开；手机扫码可直接进入导播台</p>
          </div>
          <div class="px-5 py-4 border-t border-neutral-800 flex justify-between items-center">
            <button
              @click="resetDirectorQr"
              :disabled="resettingQr"
              class="px-4 py-2 text-xs font-bold text-red-400 hover:text-red-300 bg-red-500/10 hover:bg-red-500/20 rounded-lg border border-red-500/20 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {{ resettingQr ? '重置中...' : '重置二维码' }}
            </button>
            <button
              @click="closeDirectorQr"
              class="px-4 py-2 text-xs text-neutral-400 hover:text-neutral-200 bg-neutral-800 hover:bg-neutral-700 rounded-lg border border-neutral-700 transition-colors"
            >
              关闭
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Settings2, Users, Gavel, FileText, Tv, Smartphone } from 'lucide-vue-next';
import { ElMessage } from 'element-plus';

// 引入子组件
import MatchInfoEditor, { type MatchInfo } from './MatchInfoEditor.vue';
import PlayerEditor from './Player.vue';
import RefereeEditor from './Referee.vue';
import GameInfo from './GameInfo.vue';

// 引入 API
import { getTournament, updateTournament } from '@/api/game/tournament';
import { getTournamentAuthKey, regenerateTournamentAuthKey } from '@/api/game/tournament';
import { TournamentVO } from '@/api/game/tournament/types';
import QRCode from 'qrcode';

const route = useRoute();
const router = useRouter();

// 从 URL query 获取赛事 ID
const tournamentId = computed(() => route.query.id as string);

// 返回赛事大厅
const goBackToLobby = () => {
  router.push('/game/list');
};

const currentTab = ref('info');
const loading = ref(false);

// --- 手机导播台扫码弹窗状态 ---
const showDirectorQr = ref(false);
const directorQrUrl = ref('');
const directorQrLink = ref('');
const resettingQr = ref(false);

// Tab 配置
const tabs = [
  { id: 'info', label: '基础信息', icon: FileText },
  { id: 'players', label: '参赛选手', icon: Users },
  { id: 'referees', label: '裁判组', icon: Gavel }
];

// --- 数据状态 ---

// 1. 赛事基础信息
const matchInfo = ref<MatchInfo>({
  title: '',
  startTime: '',
  location: '',
  stats: {
    prizePool: '0',
    attendance: 0,
    onlineHeat: '0',
    progress: 0
  }
});

// --- 加载赛事数据 ---
const loadTournamentData = async () => {
  if (!tournamentId.value) {
    ElMessage.warning('缺少赛事 ID');
    return;
  }

  try {
    loading.value = true;
    const response = await getTournament(tournamentId.value);
    const data: TournamentVO = response.data;

    // 将 API 数据转换为页面格式
    matchInfo.value = {
      title: data.name,
      startTime: '', // API 中暂无此字段
      location: '', // API 中暂无此字段
      stats: {
        prizePool: '0',
        attendance: 0,
        onlineHeat: '0',
        progress: data.status === 0 ? 0 : data.status === 1 ? 50 : 100
      }
    };
  } catch (error) {
    console.error('加载赛事数据失败:', error);
    ElMessage.error('加载赛事数据失败');
  } finally {
    loading.value = false;
  }
};

// --- 保存配置 ---
const saveConfig = async () => {
  if (!tournamentId.value) return;

  try {
    loading.value = true;
    await updateTournament({
      id: Number(tournamentId.value),
      name: matchInfo.value.title,
      status: matchInfo.value.stats.progress === 0 ? 0 : matchInfo.value.stats.progress === 50 ? 1 : 2,
      remark: ''
    });
    ElMessage.success('保存成功');
  } catch (error) {
    console.error('保存失败:', error);
    ElMessage.error('保存失败');
  } finally {
    loading.value = false;
  }
};

// --- 打开赛事大屏 ---
const openProjection = () => {
  if (!tournamentId.value) {
    ElMessage.warning('缺少赛事 ID');
    return;
  }
  // 新页面打开赛事大屏，通过query传递id
  const url = router.resolve({
    path: '/tournament/config',
    query: { id: tournamentId.value }
  }).href;
  window.open(url, '_blank');
};

// --- 打开手机导播台二维码弹窗 ---
const openDirectorQr = async () => {
  if (!tournamentId.value) {
    ElMessage.warning('缺少赛事 ID');
    return;
  }
  try {
    // 旧赛事可能没有 auth_key,为空时自动生成一个
    const resp = await getTournamentAuthKey(tournamentId.value);
    let authKey = resp?.data?.authKey ?? resp?.data ?? '';
    if (!authKey) {
      const regResp = await regenerateTournamentAuthKey(tournamentId.value);
      authKey = regResp?.data?.authKey ?? regResp?.data ?? '';
    }
    if (!authKey) {
      ElMessage.error('获取赛事凭证失败');
      return;
    }
    renderDirectorQr(authKey);
    showDirectorQr.value = true;
  } catch (error) {
    console.error('获取赛事凭证失败:', error);
    ElMessage.error('获取赛事凭证失败');
  }
};

const renderDirectorQr = (authKey: string) => {
  directorQrLink.value = `${window.location.origin}/tournament/mobile-director?authKey=${encodeURIComponent(authKey)}&id=${tournamentId.value}`;
  QRCode.toDataURL(directorQrLink.value, { width: 240, margin: 2, color: { dark: '#000', light: '#fff' } })
    .then((url: string) => {
      directorQrUrl.value = url;
    })
    .catch((err) => {
      console.error('生成二维码失败:', err);
      directorQrUrl.value = '';
    });
};

const openQrInNewWindow = () => {
  if (!directorQrLink.value) return;
  window.open(directorQrLink.value, '_blank', 'noopener');
};

const closeDirectorQr = () => {
  showDirectorQr.value = false;
  directorQrUrl.value = '';
  directorQrLink.value = '';
};

// --- 重置导播台二维码(泄露后调用,旧凭证立即失效) ---
const resetDirectorQr = async () => {
  if (!tournamentId.value || resettingQr.value) return;
  resettingQr.value = true;
  try {
    const resp = await regenerateTournamentAuthKey(tournamentId.value);
    const newKey = resp?.data?.authKey ?? resp?.data ?? '';
    if (!newKey) {
      ElMessage.error('重置失败，请重试');
      return;
    }
    renderDirectorQr(newKey);
    ElMessage.success('二维码已重置，旧二维码立即失效');
  } catch (error) {
    console.error('重置赛事凭证失败:', error);
    ElMessage.error('重置赛事凭证失败');
  } finally {
    resettingQr.value = false;
  }
};

// --- 组件挂载时加载数据 ---
onMounted(() => {
  loadTournamentData();
});
</script>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.3s ease-out;
}
@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(5px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>

<style lang="scss">
/* 设置滚动条样式，与其他页面保持一致 */
::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

::-webkit-scrollbar-track {
  background-color: #f1f1f1;
}

::-webkit-scrollbar-thumb {
  background-color: #c0c0c0;
  border-radius: 3px;
}

/* 使用 overlay 模式滚动条，不挤占页面空间 */
html, body {
  scrollbar-gutter: stable;
  scrollbar-width: thin;
}

/* 确保 body 的滚动条是 overlay 模式 */
body {
  overflow-y: overlay;
}
</style>
