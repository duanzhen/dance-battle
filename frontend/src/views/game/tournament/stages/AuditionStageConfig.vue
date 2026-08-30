<template>
  <div class="stage-config">
    <div class="bg-neutral-900 border border-neutral-800 rounded-xl p-6">
      <h3 class="text-sm font-bold text-neutral-400 uppercase tracking-wider mb-6 flex items-center gap-2"><Mic class="w-4 h-4" /> 海选赛配置</h3>

      <div class="space-y-6">
        <!-- STARTED 模式: 创建+初始配置只读展示 -->
        <template v-if="currentMode === ConfigMode.STARTED">
          <!-- 基础信息 -->
          <div class="grid grid-cols-2 lg:grid-cols-4 gap-4">
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">海选规模</div>
              <div class="text-2xl font-bold text-white font-mono">{{ config.scale }}</div>
              <div class="text-xs text-neutral-600 mt-1">支队伍</div>
            </div>
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">晋级名额</div>
              <div class="text-2xl font-bold text-amber-500 font-mono">{{ config.advanceCount }}</div>
              <div class="text-xs text-neutral-600 mt-1">支晋级</div>
            </div>
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">分圈</div>
              <div class="text-2xl font-bold text-white font-mono">{{ config.circles || 1 }}</div>
              <div class="text-xs text-neutral-600 mt-1">圈并行</div>
            </div>
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-1">比赛格式</div>
              <div class="text-2xl font-bold text-white font-mono">{{ config.format }}</div>
              <div class="text-xs text-neutral-600 mt-1">{{ formatLabel }}</div>
            </div>
          </div>

          <!-- 评分规则 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">评分规则</div>
            <div class="flex flex-wrap gap-2">
              <span class="px-2.5 py-1 rounded text-xs border border-green-500/30 bg-green-500/10 text-green-400">按评分晋级</span>
              <span class="px-2.5 py-1 rounded text-xs border border-neutral-800 bg-neutral-900 text-neutral-300">满分 {{ config.maxScore }}</span>
            </div>
          </div>

          <!-- 每圈配置明细 -->
          <div v-if="(config.circles || 1) > 1" class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-3">每圈配置</div>
            <div class="grid gap-3" :style="{ gridTemplateColumns: `repeat(${Math.min(circleCount, 3)}, minmax(0, 1fr))` }">
              <div v-for="(_, i) in circleCount" :key="i" class="border border-neutral-800 rounded-lg p-3">
                <div class="text-sm font-bold text-amber-500 mb-2">第 {{ i + 1 }} 圈</div>
                <div class="text-xs text-neutral-400 flex items-center justify-between py-0.5 gap-3">
                  <span>人数</span>
                  <span class="text-white font-mono">{{ circlePlayerCount(i) }} 人</span>
                </div>
                <div class="text-xs text-neutral-400 flex items-center justify-between py-0.5 gap-3">
                  <span>晋级</span>
                  <span class="text-white font-mono">{{ circleQuotaText(i) }} 人</span>
                </div>
                <div class="text-xs text-neutral-400 flex items-center justify-between py-0.5 gap-3">
                  <span class="flex-none">裁判</span>
                  <span class="text-white text-right truncate">{{ circleRefereeText(i) }}</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 赛制概览 -->
          <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
            <div class="text-xs text-neutral-500 mb-2">赛制概览</div>
            <div class="text-sm text-neutral-300">
              {{ config.scale }} 支队伍参加选拔，最终 {{ config.advanceCount }} 支队伍晋级（晋级率
              {{ ((config.advanceCount / config.scale) * 100).toFixed(1) }}%）
            </div>
            <div v-if="(config.circles || 1) > 1" class="text-sm text-neutral-400 mt-1">分 {{ config.circles }} 圈并行：{{ circleOverviewText }}</div>
          </div>
        </template>

        <!-- CREATE/INIT 模式: 可编辑 -->
        <template v-else>
          <!-- CREATE 模式: 仅创建配置(创建后锁定) -->
          <template v-if="currentMode === ConfigMode.CREATE">
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-3">创建配置</div>
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">海选规模</label>
                <div class="relative">
                  <input
                    type="number"
                    v-model.number="config.scale"
                    :min="8"
                    :max="512"
                    class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                    @input="handleUpdate"
                  />
                  <div class="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-neutral-600">支队伍</div>
                </div>
              </div>
              <p class="text-[11px] text-neutral-500 mt-3">
                海选规模为创建配置,创建后锁定;晋级名额、分圈、每圈名额/裁判等初始配置请在创建完成后于「初始配置」中设置
              </p>
            </div>
          </template>

          <!-- INIT 模式: 创建配置只读 + 初始配置可编辑 -->
          <template v-else>
            <!-- 规模设置 -->
            <div>
              <label class="text-xs text-neutral-500 mb-2 block">选拔规模</label>
              <div class="grid grid-cols-3 gap-4">
                <div>
                  <label class="text-xs text-neutral-600 mb-1 block">海选规模</label>
                  <div class="relative">
                    <input
                      type="number"
                      v-model.number="config.scale"
                      disabled
                      :min="8"
                      :max="512"
                      class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors disabled:opacity-50"
                    />
                    <div class="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-neutral-600">支队伍</div>
                  </div>
                </div>
                <div>
                  <label class="text-xs text-neutral-600 mb-1 block">晋级名额</label>
                  <div class="relative">
                    <input
                      type="number"
                      v-model.number="config.advanceCount"
                      :min="1"
                      :max="config.scale - 1"
                      class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                      @input="handleUpdate"
                    />
                    <div class="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-neutral-600">支晋级</div>
                  </div>
                </div>
                <div>
                  <label class="text-xs text-neutral-600 mb-1 block">分圈数</label>
                  <div class="relative">
                    <input
                      type="number"
                      v-model.number="config.circles"
                      :min="1"
                      :max="config.scale"
                      class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                      @input="handleUpdate"
                    />
                    <div class="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-neutral-600">圈</div>
                  </div>
                </div>
              </div>
              <p class="text-[11px] text-neutral-500 mt-2">海选规模为创建配置,创建后锁定;晋级名额与分圈为初始配置,赛段开始前可继续修改</p>
              <p v-if="(config.circles || 1) > 1" class="text-[11px] text-neutral-500 mt-2">
                分 {{ config.circles }} 圈并行进行，每圈约 {{ Math.ceil(config.scale / config.circles) }} 人，可分别配置每圈晋级人数
              </p>
            </div>

            <!-- 基本信息 -->
            <div>
              <label class="text-xs text-neutral-500 mb-2 block">比赛格式</label>
              <select
                v-model="config.format"
                class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                @change="handleUpdate"
              >
                <option value="BO1">BO1 (单局决胜)</option>
                <option value="BO3">BO3 (三局两胜)</option>
              </select>
            </div>

            <!-- 每圈晋级人数(分圈时可独立配置) -->
            <div v-if="(config.circles || 1) > 1" class="bg-black border border-neutral-700 rounded-lg p-4">
              <div class="flex items-center justify-between mb-2">
                <label class="text-xs text-neutral-500">每圈晋级人数</label>
                <span class="text-[11px] text-neutral-600">合计 {{ totalQuota }} / {{ config.advanceCount }}</span>
              </div>
              <div class="grid gap-2" :style="{ gridTemplateColumns: `repeat(${Math.min(circleCount, 4)}, minmax(0, 1fr))` }">
                <div v-for="(_, i) in circleCount" :key="i">
                  <label class="text-[10px] text-neutral-600 block mb-1">第 {{ i + 1 }} 圈</label>
                  <input
                    type="number"
                    v-model.number="config.circleAdvanceCounts[i]"
                    :min="0"
                    class="w-full bg-black border border-neutral-700 rounded p-2 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                    @input="handleUpdate"
                  />
                </div>
              </div>
              <p v-if="totalQuota !== config.advanceCount" class="text-[11px] text-amber-500/80 mt-2">每圈合计与总名额不一致，结算按每圈配置为准</p>
              <p v-if="currentMode === ConfigMode.INIT && localStage.status === 'PENDING'" class="text-[11px] text-amber-500/80 mt-2">
                赛段已生成对阵，修改圈数/每圈名额后请在「流程操作」中重新点击「生成对阵」生效
              </p>
            </div>

            <!-- 每圈裁判(分圈时可独立配置,一圈可多裁判) -->
            <div v-if="(config.circles || 1) > 1" class="bg-black border border-neutral-700 rounded-lg p-4">
              <div class="flex items-center justify-between mb-2">
                <label class="text-xs text-neutral-500">每圈裁判</label>
                <span v-if="referees.length === 0" class="text-[11px] text-neutral-600">暂无裁判，请先在赛事中创建裁判</span>
              </div>
              <div class="grid gap-2" :style="{ gridTemplateColumns: `repeat(${Math.min(circleCount, 2)}, minmax(0, 1fr))` }">
                <div v-for="(_, i) in circleCount" :key="i">
                  <label class="text-[10px] text-neutral-600 block mb-1">第 {{ i + 1 }} 圈</label>
                  <div class="space-y-1">
                    <label
                      v-for="r in referees"
                      :key="String(r.id)"
                      class="flex items-center gap-2 text-xs text-neutral-300 cursor-pointer select-none"
                    >
                      <input
                        type="checkbox"
                        class="accent-amber-500"
                        :checked="isCircleRefereeChecked(i, r.id)"
                        @change="toggleCircleReferee(i, r.id)"
                      />
                      {{ r.name }}
                    </label>
                    <p v-if="referees.length === 0" class="text-[11px] text-neutral-600">未指定</p>
                  </div>
                </div>
              </div>
              <p class="text-[11px] text-neutral-600 mt-2">
                不勾选表示该圈不指定裁判；生成对阵时按此配置写入圈-裁判绑定，已生成对阵需重新「生成对阵」生效
              </p>
            </div>

            <!-- 评分规则 -->
            <div>
              <label class="text-xs text-neutral-500 mb-2 block">评分规则</label>
              <div>
                <label class="text-xs text-neutral-600 mb-1 block">满分</label>
                <input
                  type="number"
                  v-model.number="config.maxScore"
                  :min="1"
                  class="w-full bg-black border border-neutral-700 rounded p-2.5 text-sm text-white focus:border-amber-500 focus:outline-none transition-colors"
                  @input="handleUpdate"
                />
              </div>
            </div>

            <!-- 预览 -->
            <div class="bg-black/50 border border-neutral-800 rounded-lg p-4">
              <div class="text-xs text-neutral-500 mb-2">赛制预览</div>
              <div class="text-sm text-neutral-300">{{ config.scale }} 支队伍参加选拔</div>
              <div class="text-sm text-neutral-300 mt-1">最终 {{ config.advanceCount }} 支队伍晋级</div>
              <div v-if="(config.circles || 1) > 1" class="text-sm text-neutral-400 mt-1">
                分 {{ config.circles }} 圈并行，每圈约 {{ Math.ceil(config.scale / config.circles) }} 人、晋级
                {{ Math.floor(config.advanceCount / config.circles) }} 人
              </div>
              <div v-if="(config.circles || 1) > 1 && hasCircleQuotas" class="text-sm text-neutral-400 mt-1">
                每圈晋级：{{ config.circleAdvanceCounts.join(' / ') }}（合计 {{ totalQuota }} 人）
              </div>
              <div v-if="(config.circles || 1) > 1 && hasCircleReferees" class="text-sm text-neutral-400 mt-1">
                每圈裁判：{{ circleRefereeSummary }}
              </div>
              <div class="text-xs text-neutral-500 mt-2">晋级率：{{ ((config.advanceCount / config.scale) * 100).toFixed(1) }}%</div>
            </div>
          </template>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue';
import { Mic } from 'lucide-vue-next';
import { AuditionConfig, StageData, ConfigMode } from './types';
import { listReferee } from '@/api/game/referee';
import { listMatchReferee } from '@/api/game/matchReferee';
import { listMatch } from '@/api/game/match';

// Props
const props = defineProps<{
  stage: StageData;
  mode?: ConfigMode;
}>();

// Emits
const emit = defineEmits<{
  update: [stage: StageData];
}>();

// 本地赛段数据
const localStage = ref<StageData>({ ...props.stage });

// 当前模式
const currentMode = computed(() => props.mode || ConfigMode.INIT);

// 配置对象
const config = ref<any>({
  scale: 64,
  advanceCondition: 'score',
  advanceCount: 16,
  circles: 1,
  format: 'BO1',
  maxScore: 10,
  circleAdvanceCounts: [],
  circleRefereeIds: []
});

// 赛事裁判列表(用于每圈裁判勾选)
const referees = ref<{ id: string | number; name: string }[]>([]);
// 实际已写入 t_match_referee 的圈-裁判(按圈顺序;生成对阵后才有值)
const actualCircleReferees = ref<string[]>([]);

const loadReferees = async () => {
  if (props.stage.tournamentId == null) {
    referees.value = [];
    return;
  }
  try {
    const resp: any = await listReferee({
      tournamentId: props.stage.tournamentId,
      pageNum: 1,
      pageSize: 99
    });
    referees.value = resp?.data ?? [];
  } catch (e) {
    console.error('加载裁判列表失败:', e);
    referees.value = [];
  }
};

const loadActualCircleReferees = async () => {
  actualCircleReferees.value = [];
  // 创建模式下 stage.id 为占位符(如 temp),无真实场次,跳过接口调用
  const sid = props.stage?.id;
  if (sid == null || !/^\d+$/.test(String(sid))) return;
  try {
    const [mrResp, matchResp]: any = await Promise.all([listMatchReferee(sid), listMatch({ stageId: sid, pageNum: 1, pageSize: 99 } as any)]);
    const rows = mrResp?.data ?? [];
    const matches = (matchResp?.data?.data || matchResp?.data || [])
      .slice()
      .sort((a: any, b: any) => (a.displayRow ?? 0) - (b.displayRow ?? 0) || String(a.id).localeCompare(String(b.id)));
    const byMatch: Record<string, string> = {};
    rows.forEach((r: any) => {
      if (r.matchId == null || !r.refereeName) return;
      const k = String(r.matchId);
      byMatch[k] = byMatch[k] ? byMatch[k] + ' / ' + r.refereeName : r.refereeName;
    });
    actualCircleReferees.value = matches.map((m: any) => byMatch[String(m.id)] || '');
  } catch (e) {
    console.error('加载实际圈裁判失败:', e);
    actualCircleReferees.value = [];
  }
};

// 分圈数量
const circleCount = computed(() => Math.max(1, config.value.circles || 1));

// 是否已配置每圈晋级名额
const hasCircleQuotas = computed(
  () => circleCount.value > 1 && Array.isArray(config.value.circleAdvanceCounts) && config.value.circleAdvanceCounts.length === circleCount.value
);

// 每圈名额合计
const totalQuota = computed(() => {
  if (hasCircleQuotas.value) {
    return config.value.circleAdvanceCounts.reduce((s: number, n: number) => s + (Number(n) || 0), 0);
  }
  return config.value.advanceCount;
});

// 是否已配置每圈裁判
const hasCircleReferees = computed(
  () =>
    circleCount.value > 1 &&
    Array.isArray(config.value.circleRefereeIds) &&
    config.value.circleRefereeIds.some((l: any) => Array.isArray(l) && l.length > 0)
);

// 每圈裁判摘要(只读展示)
const circleRefereeSummary = computed(() => {
  const nameById: Record<string, string> = {};
  referees.value.forEach((r) => {
    nameById[String(r.id)] = r.name;
  });
  return Array.from({ length: circleCount.value }, (_, i) => {
    const ids: any[] = config.value.circleRefereeIds?.[i] || [];
    const names = ids.map((id: any) => nameById[String(id)] || String(id)).join(' / ');
    return `第${i + 1}圈: ${names || '未指定'}`;
  }).join('  ');
});

// 比赛格式中文标签(只读展示)
const formatLabel = computed(() => {
  const map: Record<string, string> = { BO1: '单局决胜', BO3: '三局两胜', BO5: '五局三胜' };
  return map[config.value.format] || config.value.format;
});

// 每圈预估人数(按规模均分,余数从前到后补)
const circlePlayerCount = (i: number) => {
  const n = circleCount.value;
  const base = Math.floor((config.value.scale || 0) / n);
  const rem = (config.value.scale || 0) % n;
  return base + (i < rem ? 1 : 0);
};

// 每圈晋级人数:已配置按配置,未配置显示均分值
const circleQuotaText = (i: number) => {
  if (hasCircleQuotas.value) {
    return Number(config.value.circleAdvanceCounts[i]) || 0;
  }
  return Math.floor((config.value.advanceCount || 0) / circleCount.value);
};

// 每圈裁判文本:只读展示优先显示实际绑定(t_match_referee),其次回退到配置;均无显示"未指定"
const circleRefereeText = (i: number) => {
  const actual = actualCircleReferees.value[i];
  if (actual) return actual;
  const ids: any[] = config.value.circleRefereeIds?.[i] || [];
  if (!ids.length) return '未指定';
  const nameById: Record<string, string> = {};
  referees.value.forEach((r) => {
    nameById[String(r.id)] = r.name;
  });
  return ids.map((id: any) => nameById[String(id)] || String(id)).join(' / ');
};

// 每圈配置一行摘要(只读展示)
const circleOverviewText = computed(() =>
  Array.from({ length: circleCount.value }, (_, i) => `第${i + 1}圈 ${circlePlayerCount(i)}人晋${circleQuotaText(i)}`).join(' · ')
);

// 圈数变化时同步每圈名额数组(保留已配置值,新增位用均分值补齐)
const syncCircleQuotas = () => {
  const n = circleCount.value;
  if (n <= 1) {
    config.value.circleAdvanceCounts = [];
    return;
  }
  const current = Array.isArray(config.value.circleAdvanceCounts) ? [...config.value.circleAdvanceCounts] : [];
  const base = Math.max(0, Math.floor((config.value.advanceCount || 0) / n));
  const rem = Math.max(0, (config.value.advanceCount || 0) % n);
  if (current.length === 0) {
    for (let i = 0; i < n; i++) {
      current.push(base + (i < rem ? 1 : 0));
    }
  } else {
    while (current.length < n) {
      current.push(base);
    }
    current.length = n;
  }
  config.value.circleAdvanceCounts = current;
};

// 圈数变化时同步每圈裁判数组(保留已配置值,新增圈为空列表)
const syncCircleReferees = () => {
  const n = circleCount.value;
  const current = Array.isArray(config.value.circleRefereeIds) ? [...config.value.circleRefereeIds] : [];
  if (n <= 1) {
    config.value.circleRefereeIds = [];
    return;
  }
  while (current.length < n) {
    current.push([]);
  }
  current.length = n;
  config.value.circleRefereeIds = current;
};

// 当前圈是否已勾选某裁判
const isCircleRefereeChecked = (i: number, rid: string | number) =>
  Array.isArray(config.value.circleRefereeIds?.[i]) && config.value.circleRefereeIds[i].some((id: any) => String(id) === String(rid));

// 切换某圈绑定的裁判(一圈可多裁判)
const toggleCircleReferee = (i: number, rid: string | number) => {
  const list = Array.isArray(config.value.circleRefereeIds?.[i]) ? [...config.value.circleRefereeIds[i]] : [];
  const idx = list.findIndex((id: any) => String(id) === String(rid));
  if (idx >= 0) {
    list.splice(idx, 1);
  } else {
    list.push(rid);
  }
  config.value.circleRefereeIds[i] = list;
  handleUpdate();
};

// 解析配置
const parseConfig = () => {
  try {
    if (props.stage.ruleConfig) {
      const parsed = JSON.parse(props.stage.ruleConfig);
      config.value = { ...config.value, ...parsed };
    }
  } catch (e) {
    console.warn('Failed to parse ruleConfig:', e);
  }
};

// 序列化配置
const serializeConfig = () => {
  return JSON.stringify(config.value);
};

// 更新处理
const handleUpdate = () => {
  if (circleCount.value > 1 && hasCircleQuotas.value) {
    localStage.value.teamCountEnd = totalQuota.value;
  } else {
    localStage.value.teamCountEnd = config.value.advanceCount;
  }
  localStage.value.ruleConfig = serializeConfig();
  localStage.value.teamCountStart = config.value.scale;
  emit('update', localStage.value);
};

// 监听 props 变化
watch(
  () => props.stage,
  () => {
    localStage.value = { ...props.stage };
    parseConfig();
    loadActualCircleReferees();
  },
  { deep: true }
);

// 圈数变化:重建每圈名额数组并触发保存
watch(
  () => config.value.circles,
  () => {
    syncCircleQuotas();
    syncCircleReferees();
    handleUpdate();
  }
);

// 初始化
onMounted(() => {
  parseConfig();
  syncCircleQuotas();
  syncCircleReferees();
  loadReferees();
  loadActualCircleReferees();
});
</script>

<style scoped>
.stage-config {
  max-width: 900px;
  margin: 0 auto;
}

input[type='number']::-webkit-inner-spin-button,
input[type='number']::-webkit-outer-spin-button {
  opacity: 1;
}
</style>
