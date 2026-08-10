<template>
  <div class="space-y-6">
    <div class="flex justify-between items-center">
      <div>
        <h2 class="text-xl font-bold text-neutral-100 flex items-center gap-2"><Gavel class="w-5 h-5 text-amber-500" /> 裁判</h2>
      </div>
      <button
        @click="openAddDialog"
        class="text-xs flex items-center gap-1 bg-neutral-800 hover:bg-neutral-700 text-neutral-300 px-3 py-1.5 rounded border border-neutral-700 transition-colors"
      >
        <Plus class="w-3 h-3" /> 添加裁判
      </button>
    </div>

    <!-- 添加裁判弹窗 -->
    <Teleport to="body">
      <div
        v-if="showAddDialog"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
        @click.self="closeAddDialog"
      >
        <div class="bg-neutral-900 border border-neutral-700 rounded-xl w-full max-w-md mx-4 shadow-2xl" @click.stop>
          <div class="px-5 py-4 border-b border-neutral-800 flex items-center justify-between">
            <h3 class="text-sm font-bold text-neutral-100">添加裁判</h3>
            <button @click="closeAddDialog" class="text-neutral-500 hover:text-neutral-300 border-none appearance-none">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
          <div class="p-5 space-y-4">
            <div class="flex flex-col items-center gap-2">
              <div
                class="w-20 h-20 rounded-full bg-neutral-800 border border-neutral-700 overflow-hidden flex items-center justify-center cursor-pointer relative group"
                @click="!uploadingAvatar && triggerAvatarFile()"
              >
                <img v-if="addForm.avatar" :src="addForm.avatar" alt="头像" class="w-full h-full object-cover" />
                <UserPlus v-else class="w-7 h-7 text-neutral-600 group-hover:text-amber-500 transition-colors" />
                <input ref="avatarInput" type="file" class="hidden" accept="image/*" @change="handleAvatarFile" />
              </div>
              <p class="text-[10px] text-neutral-600">{{ uploadingAvatar ? '上传中...' : '点击上传头像（可选）' }}</p>
            </div>
            <div>
              <label class="block text-[10px] font-bold text-neutral-500 uppercase tracking-wider mb-1.5">裁判姓名</label>
              <input
                v-model="addForm.name"
                class="w-full bg-neutral-800 border border-neutral-700 rounded-lg px-3 py-2 text-sm text-neutral-200 focus:border-amber-500 focus:outline-none transition-colors placeholder-neutral-600"
                placeholder="输入裁判姓名"
                @keyup.enter="submitAddReferee"
              />
            </div>
          </div>
          <div class="px-5 py-4 border-t border-neutral-800 flex justify-end gap-3">
            <button
              @click="closeAddDialog"
              class="px-4 py-2 text-xs text-neutral-400 hover:text-neutral-200 bg-neutral-800 hover:bg-neutral-700 rounded-lg border border-neutral-700 transition-colors"
            >
              取消
            </button>
            <button
              @click="submitAddReferee"
              :disabled="!addForm.name.trim() || submitting"
              class="px-4 py-2 text-xs font-bold text-neutral-900 bg-amber-500 hover:bg-amber-400 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {{ submitting ? '提交中...' : '确认添加' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-amber-500"></div>
    </div>

    <div v-else>
      <div v-if="referees.length === 0" class="py-6 text-center text-neutral-600 text-xs">暂无裁判人员，请点击右上角添加</div>
      <div v-else class="flex flex-wrap items-start gap-3">
        <TransitionGroup name="list">
          <div
            v-for="referee in referees"
            :key="referee.id"
            class="relative w-28 flex flex-col items-center gap-1.5 group"
          >
            <!-- 状态指示点 -->
            <span
              class="absolute top-0 right-1 w-2 h-2 rounded-full"
              :class="isRefereeActive(referee) ? 'bg-green-500 shadow-[0_0_5px_lime]' : 'bg-neutral-600'"
              :title="isRefereeActive(referee) ? 'ACTIVE' : 'STANDBY'"
            ></span>
            <!-- 头像 -->
            <div class="w-14 h-14 rounded-full bg-neutral-700 overflow-hidden flex items-center justify-center">
              <img v-if="referee.avatar" :src="referee.avatar" alt="头像" class="w-full h-full object-cover" />
              <Shield v-else class="w-5 h-5 text-neutral-500" />
            </div>
            <!-- 名字 -->
            <input
              v-model="referee.name"
              @blur="updateReferee(referee)"
              class="w-full bg-transparent text-center text-[11px] font-bold text-neutral-200 border-b border-transparent focus:border-amber-500 focus:outline-none py-0.5 transition-colors placeholder-neutral-600"
              placeholder="裁判姓名"
            />
            <!-- 二维码 -->
            <button
              v-if="referee.authKey"
              @click="showQRCode(referee)"
              class="w-7 h-7 rounded-lg flex items-center justify-center text-neutral-400 hover:text-amber-500 hover:bg-amber-500/10 transition-colors"
              title="二维码入口"
            >
              <QrCode class="w-4 h-4" />
            </button>
          </div>
        </TransitionGroup>
      </div>
    </div>

    <!-- 扫码入口弹窗 -->
    <Teleport to="body">
      <div
        v-if="showQrDialog"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
        @click.self="showQrDialog = false"
      >
        <div class="bg-neutral-900 border border-neutral-700 rounded-xl w-full max-w-sm mx-4 shadow-2xl" @click.stop>
          <div class="px-5 py-4 border-b border-neutral-800 flex items-center justify-between">
            <h3 class="text-sm font-bold text-neutral-100">裁判扫码入口</h3>
            <button @click="showQrDialog = false" class="text-neutral-500 hover:text-neutral-300 border-none appearance-none">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
          <div class="p-6 flex flex-col items-center gap-4">
            <p class="text-xs text-neutral-400">裁判 <span class="text-amber-400 font-bold">{{ qrRefereeName }}</span> 扫码进入判罚页面</p>
            <div class="bg-white p-3 rounded-lg">
              <img
                :src="qrCodeUrl"
                alt="QR Code"
                class="w-48 h-48"
              />
            </div>
            <a
              :href="qrScoringUrl"
              target="_blank"
              rel="noopener"
              class="text-[11px] font-bold text-amber-400 hover:text-amber-300 underline underline-offset-2"
            >
              点开进入判罚页面（开发用）
            </a>
            <p class="text-[10px] text-neutral-500">手机扫描二维码，免登录直接进入判罚界面</p>
          </div>
          <div class="px-5 py-4 border-t border-neutral-800 flex justify-between items-center">
            <button
              @click="resetRefereeQr"
              :disabled="resettingRefereeQr"
              class="px-4 py-2 text-xs font-bold text-red-400 hover:text-red-300 bg-red-500/10 hover:bg-red-500/20 rounded-lg border border-red-500/20 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {{ resettingRefereeQr ? '重置中...' : '重置二维码' }}
            </button>
            <button
              @click="showQrDialog = false"
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
import { ref, onMounted, watch } from 'vue';
import { Gavel, Plus, Shield, QrCode, UserPlus } from 'lucide-vue-next';
import QRCode from 'qrcode';
import { listReferee, addReferee as addRefereeApi, updateReferee as updateRefereeApi, regenerateRefereeAuthKey } from '@/api/game/referee';
import { RefereeVO, RefereeForm } from '@/api/game/referee/types';
import { ElMessage } from 'element-plus';
import request from '@/utils/request';

const props = defineProps<{
  tournamentId?: string | number;
}>();

// 本地状态
const referees = ref<RefereeVO[]>([]);
const loading = ref(false);

// 添加弹窗状态
const showAddDialog = ref(false);
const submitting = ref(false);
const addForm = ref({
  name: '',
  avatar: ''
});
const avatarInput = ref<HTMLInputElement | null>(null);
const uploadingAvatar = ref(false);

// QR 码弹窗状态
const showQrDialog = ref(false);
const qrCodeUrl = ref('');
const qrRefereeName = ref('');
const qrScoringUrl = ref('');
const qrRefereeId = ref<string | number>('');
const resettingRefereeQr = ref(false);

const showQRCode = async (referee: RefereeVO) => {
  qrRefereeId.value = referee.id;
  qrRefereeName.value = referee.name;
  qrScoringUrl.value = `${window.location.origin}/tournament/referee-scoring?authKey=${encodeURIComponent(referee.authKey)}`;
  qrCodeUrl.value = await QRCode.toDataURL(qrScoringUrl.value, { width: 240, margin: 2, color: { dark: '#000', light: '#fff' } });
  showQrDialog.value = true;
};

// 重置裁判二维码(泄露后调用,旧凭证立即失效)
const resetRefereeQr = async () => {
  if (!qrRefereeId.value || resettingRefereeQr.value) return;
  resettingRefereeQr.value = true;
  try {
    const response = await regenerateRefereeAuthKey(qrRefereeId.value);
    const newKey = response?.data?.authKey || response?.data;
    if (!newKey) {
      ElMessage.error('重置失败，请重试');
      return;
    }
    qrScoringUrl.value = `${window.location.origin}/tournament/referee-scoring?authKey=${encodeURIComponent(newKey)}`;
    qrCodeUrl.value = await QRCode.toDataURL(qrScoringUrl.value, { width: 240, margin: 2, color: { dark: '#000', light: '#fff' } });
    await loadReferees();
    ElMessage.success('二维码已重置，旧二维码立即失效');
  } catch (error) {
    console.error('重置裁判密钥失败:', error);
    ElMessage.error('重置裁判密钥失败');
  } finally {
    resettingRefereeQr.value = false;
  }
};

const openAddDialog = () => {
  addForm.value = { name: '', avatar: '' };
  showAddDialog.value = true;
};

const closeAddDialog = () => {
  showAddDialog.value = false;
};

// 头像文件选择与上传
const triggerAvatarFile = () => avatarInput.value?.click();

const handleAvatarFile = async (e: Event) => {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('头像仅支持图片文件');
    return;
  }
  uploadingAvatar.value = true;
  try {
    const formData = new FormData();
    formData.append('file', file);
    const res = await request({
      url: '/resource/oss/upload',
      method: 'post',
      data: formData
    });
    if (res.code === 200 && res.data?.url) {
      addForm.value.avatar = res.data.url;
    } else {
      ElMessage.error(res?.msg || '头像上传失败');
    }
  } catch (error) {
    console.error('头像上传失败:', error);
    ElMessage.error('头像上传失败');
  } finally {
    uploadingAvatar.value = false;
    if (avatarInput.value) {
      avatarInput.value.value = '';
    }
  }
};

const submitAddReferee = async () => {
  if (!addForm.value.name.trim()) return;
  if (!props.tournamentId) {
    ElMessage.warning('请先选择赛事');
    return;
  }

  submitting.value = true;
  try {
    await addRefereeApi({
      tournamentId: props.tournamentId,
      name: addForm.value.name.trim(),
      avatar: addForm.value.avatar || null
    });
    ElMessage.success('添加成功');
    showAddDialog.value = false;
    await loadReferees();
  } catch (error) {
    console.error('添加裁判失败:', error);
    ElMessage.error('添加裁判失败');
  } finally {
    submitting.value = false;
  }
};

// 加载裁判列表
const loadReferees = async () => {
  if (!props.tournamentId) {
    referees.value = [];
    return;
  }

  try {
    loading.value = true;
    const response = await listReferee({ tournamentId: props.tournamentId } as any);
    referees.value = response.data || [];
  } catch (error) {
    console.error('加载裁判列表失败:', error);
    ElMessage.error('加载裁判列表失败');
    referees.value = [];
  } finally {
    loading.value = false;
  }
};

// 裁判状态:绑定过赛段(或持有历史权限)视为 ACTIVE,否则 STANDBY
const isRefereeActive = (referee: RefereeVO) => {
  return (Number(referee.assignedStageCount) > 0) || !!referee.permissions;
};

// 更新裁判
const updateReferee = async (referee: RefereeVO) => {
  if (!props.tournamentId) return;

  try {
    const updateData: RefereeForm = {
      id: referee.id,
      tournamentId: props.tournamentId,
      name: referee.name,
      avatar: referee.avatar,
      authKey: referee.authKey,
      permissions: referee.permissions,
      remark: referee.remark
    };

    await updateRefereeApi(updateData);
  } catch (error) {
    console.error('更新裁判失败:', error);
    // ElMessage.error('更新失败'); // 注释掉避免每次失焦都弹提示
  }
};

// 监听tournamentId变化
watch(
  () => props.tournamentId,
  () => {
    loadReferees();
  },
  { immediate: true }
);

// 组件挂载时加载数据
onMounted(() => {
  loadReferees();
});

// 暴露刷新方法供父组件调用
defineExpose({
  refresh: loadReferees
});
</script>

<style scoped>
.list-enter-active,
.list-leave-active {
  transition: all 0.3s ease;
}
.list-enter-from,
.list-leave-to {
  opacity: 0;
  transform: translateX(-10px);
}
</style>
