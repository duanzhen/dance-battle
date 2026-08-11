<template>
  <div class="h-full flex flex-col bg-[#0a0a0a] rounded-xl border border-neutral-800 overflow-hidden">
    <!-- 头部 -->
    <div class="flex-none px-6 py-4 border-b border-neutral-800 flex items-center justify-between">
      <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider">参赛选手</h3>
      <div class="flex items-center gap-3 text-xs">
        <el-button
          type="warning"
          size="small"
          class="!h-7 !px-3 !text-xs"
          :disabled="isAudition"
          :title="isAudition ? '海选赛段不支持嘉宾加入' : '赛段中间态(PENDING/GAMING)可加入嘉宾'"
          @click="openGuestDialog"
        >
          添加嘉宾
        </el-button>
        <span class="text-neutral-500">共</span>
        <span class="text-amber-500 font-mono">{{ competitors.length }}</span>
        <span class="text-neutral-500">名</span>
      </div>
    </div>

    <!-- 加载状态 -->
    <div v-if="loading" class="flex-1 flex items-center justify-center">
      <div class="text-neutral-500 text-sm">加载中...</div>
    </div>

    <!-- 空状态 -->
    <div v-else-if="competitors.length === 0" class="flex-1 flex items-center justify-center">
      <div class="text-center">
        <p class="text-neutral-500 text-sm mb-2">暂无参赛选手</p>
        <p class="text-neutral-600 text-xs">请先添加参赛选手</p>
      </div>
    </div>

    <!-- 选手列表 -->
    <div v-else class="flex-1 overflow-y-auto">
      <div class="divide-y divide-neutral-800/50">
        <div
          v-for="competitor in competitors"
          :key="competitor.id"
          class="px-6 py-4 hover:bg-neutral-900/50 transition-colors"
        >
          <div class="flex items-center gap-4">
            <!-- 种子排名 -->
            <div class="flex-shrink-0 w-12 h-12 rounded-lg bg-gradient-to-br flex items-center justify-center"
                 :class="getSeedRankClass(competitor.seedRank)">
              <span class="text-lg font-bold">{{ competitor.seedRank || '-' }}</span>
            </div>

            <!-- 选手信息 -->
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 mb-1">
                <span class="text-sm font-medium text-white truncate">{{ competitor.name }}</span>
                <span v-if="competitor.type === 1"
                      class="flex-shrink-0 px-1.5 py-0.5 rounded text-[10px] bg-blue-500/10 text-blue-500 border border-blue-500/20">
                  队伍
                </span>
                <span v-else
                      class="flex-shrink-0 px-1.5 py-0.5 rounded text-[10px] bg-green-500/10 text-green-500 border border-green-500/20">
                  个人
                </span>
                <span v-if="isGuest(competitor)"
                      class="flex-shrink-0 px-1.5 py-0.5 rounded text-[10px] bg-amber-500/10 text-amber-500 border border-amber-500/30">
                  嘉宾
                </span>
              </div>
              <div v-if="competitor.remark && !isGuest(competitor)" class="text-xs text-neutral-500 truncate">
                {{ competitor.remark }}
              </div>
            </div>

            <!-- 结果状态 -->
            <div v-if="competitor.outcomeStatus" class="flex-shrink-0">
              <span class="px-2 py-1 rounded text-xs font-medium"
                    :class="getOutcomeStatusClass(competitor.outcomeStatus)">
                {{ getOutcomeStatusText(competitor.outcomeStatus) }}
              </span>
            </div>

            <!-- 最终排名 -->
            <div v-if="competitor.finalRank" class="flex-shrink-0 w-16 text-center">
              <div class="text-xs text-neutral-500">排名</div>
              <div class="text-lg font-bold"
                   :class="getFinalRankClass(competitor.finalRank)">
                {{ competitor.finalRank }}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 添加嘉宾弹窗 -->
    <el-dialog
      v-model="guestDialogVisible"
      title="添加嘉宾"
      width="420px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-form label-width="72px" @submit.prevent>
        <el-form-item label="名称" required>
          <el-input v-model="guestForm.name" placeholder="嘉宾名称" maxlength="50" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="guestForm.type">
            <el-radio :value="0">个人</el-radio>
            <el-radio :value="1">队伍</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="选手号">
          <el-input v-model="guestForm.number" placeholder="留空自动生成(G+序号)" maxlength="20" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="guestDialogVisible = false">取消</el-button>
        <el-button size="small" type="warning" :loading="guestSubmitting" @click="handleAddGuest">确认加入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { listCompetitor } from '@/api/game/competitor';
import { addStageGuest } from '@/api/game/stage';
import { CompetitorVO } from '@/api/game/competitor/types';

// Props
const props = defineProps<{
  stageId: string | number;
  stageMode?: string;
}>();

// 状态
const loading = ref(false);
const competitors = ref<CompetitorVO[]>([]);

// 海选赛段不支持嘉宾加入
const isAudition = computed(() => props.stageMode === 'AUDITION');

// 嘉宾标记:remark == GUEST(由后端 addGuest 写入)
const isGuest = (competitor: CompetitorVO) => competitor.remark === 'GUEST';

// 添加嘉宾弹窗
const guestDialogVisible = ref(false);
const guestSubmitting = ref(false);
const guestForm = ref({ name: '', type: 0, number: '' });

const openGuestDialog = () => {
  guestForm.value = { name: '', type: 0, number: '' };
  guestDialogVisible.value = true;
};

const handleAddGuest = async () => {
  if (!guestForm.value.name.trim()) {
    ElMessage.warning('请输入嘉宾名称');
    return;
  }
  guestSubmitting.value = true;
  try {
    await addStageGuest(props.stageId, {
      name: guestForm.value.name.trim(),
      type: guestForm.value.type,
      number: guestForm.value.number.trim() || undefined
    });
    ElMessage.success('嘉宾已加入赛段');
    guestDialogVisible.value = false;
    loadCompetitors();
  } catch (error) {
    console.error('添加嘉宾失败:', error);
    ElMessage.error((error as any)?.message || '添加嘉宾失败');
  } finally {
    guestSubmitting.value = false;
  }
};

// 加载参赛选手列表
const loadCompetitors = async () => {
  if (!props.stageId) {
    competitors.value = [];
    return;
  }

  loading.value = true;
  try {
    const { data } = await listCompetitor({ stageId: props.stageId });
    competitors.value = data || [];
    // 按种子排名排序
    competitors.value.sort((a, b) => (a.seedRank || 999) - (b.seedRank || 999));
  } catch (error) {
    console.error('加载参赛选手失败:', error);
    competitors.value = [];
  } finally {
    loading.value = false;
  }
};

// 获取种子排名样式
const getSeedRankClass = (rank: number) => {
  if (!rank) return 'bg-neutral-800 text-neutral-400';
  if (rank === 1) return 'from-amber-500/20 to-amber-600/20 text-amber-500 border border-amber-500/30';
  if (rank === 2) return 'from-neutral-400/20 to-neutral-500/20 text-neutral-300 border border-neutral-400/30';
  if (rank === 3) return 'from-orange-600/20 to-orange-700/20 text-orange-500 border border-orange-600/30';
  if (rank <= 8) return 'from-blue-500/20 to-blue-600/20 text-blue-400 border border-blue-500/30';
  return 'bg-neutral-800 text-neutral-400';
};

// 获取结果状态样式
const getOutcomeStatusClass = (status: string) => {
  const statusMap: Record<string, string> = {
    'ADVANCE': 'bg-green-500/10 text-green-500 border border-green-500/20',
    'ELIMINATED': 'bg-red-500/10 text-red-500 border border-red-500/20',
    'PENDING': 'bg-neutral-500/10 text-neutral-400 border border-neutral-500/20',
    'WITHDRAWN': 'bg-neutral-600/10 text-neutral-500 border border-neutral-600/20'
  };
  return statusMap[status] || 'bg-neutral-500/10 text-neutral-400 border border-neutral-500/20';
};

// 获取结果状态文本
const getOutcomeStatusText = (status: string) => {
  const statusMap: Record<string, string> = {
    'ADVANCE': '晋级',
    'ELIMINATED': '淘汰',
    'PENDING': '进行中',
    'WITHDRAWN': '退赛'
  };
  return statusMap[status] || status;
};

// 获取最终排名样式
const getFinalRankClass = (rank: number) => {
  if (rank === 1) return 'text-amber-500';
  if (rank === 2) return 'text-neutral-300';
  if (rank === 3) return 'text-orange-500';
  return 'text-neutral-400';
};

// 监听 stageId 变化
watch(() => props.stageId, () => {
  loadCompetitors();
}, { immediate: true });

// 组件挂载时加载数据
onMounted(() => {
  loadCompetitors();
});
</script>

<style scoped>
/* 自定义滚动条 */
.overflow-y-auto::-webkit-scrollbar {
  width: 6px;
}
.overflow-y-auto::-webkit-scrollbar-track {
  background: #0a0a0a;
}
.overflow-y-auto::-webkit-scrollbar-thumb {
  background: #262626;
  border-radius: 3px;
}
.overflow-y-auto::-webkit-scrollbar-thumb:hover {
  background: #404040;
}
</style>
