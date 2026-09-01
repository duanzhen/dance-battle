<template>
  <div class="inline-flex">
    <button
      @click="openDirectorQr"
      class="px-2 sm:px-3 py-1.5 text-xs rounded transition-all duration-200 bg-neutral-800 text-neutral-400 hover:bg-neutral-700 hover:text-neutral-200 flex items-center gap-1.5 flex-none"
    >
      <Smartphone class="w-3.5 h-3.5 hidden sm:block" />
      <span>MC导播台</span>
    </button>

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
            <button @click="closeDirectorQr" class="text-neutral-500 hover:text-neutral-300 border-none appearance-none">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
          <div class="p-6 flex flex-col items-center gap-4">
            <p class="text-xs text-neutral-400">
              赛事 <span class="text-amber-400 font-bold">{{ tournamentName || '未命名' }}</span> 扫码进入手机导播台
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
import { ref } from 'vue';
import { Smartphone } from 'lucide-vue-next';
import { ElMessage } from 'element-plus';
import { getTournament, getTournamentAuthKey, regenerateTournamentAuthKey } from '@/api/game/tournament';
import QRCode from 'qrcode';

const props = defineProps<{
  tournamentId?: string | number | null;
  tournamentName?: string;
}>();

const showDirectorQr = ref(false);
const directorQrUrl = ref('');
const directorQrLink = ref('');
const resettingQr = ref(false);
const tournamentName = ref(props.tournamentName || '');

const openDirectorQr = async () => {
  if (!props.tournamentId) {
    ElMessage.warning('缺少赛事 ID');
    return;
  }
  try {
    try {
      const tResp = await getTournament(props.tournamentId);
      tournamentName.value = tResp.data?.name || '';
    } catch {
      // 名称获取失败不阻塞二维码
    }
    const resp = await getTournamentAuthKey(props.tournamentId);
    let authKey = resp?.data?.authKey ?? resp?.data ?? '';
    if (!authKey) {
      const regResp = await regenerateTournamentAuthKey(props.tournamentId);
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
  directorQrLink.value = `${window.location.origin}/tournament/mobile-director?authKey=${encodeURIComponent(authKey)}&id=${props.tournamentId}`;
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

const resetDirectorQr = async () => {
  if (!props.tournamentId || resettingQr.value) return;
  resettingQr.value = true;
  try {
    const resp = await regenerateTournamentAuthKey(props.tournamentId);
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
</script>
