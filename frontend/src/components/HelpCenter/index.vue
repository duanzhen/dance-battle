<template>
  <GameDialog
    :model-value="modelValue"
    width="720px"
    title="帮助中心"
    subtitle="按流程、角色或常见问题快速找到答案"
    :icon="CircleHelp"
    dialog-class="help-center-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <!-- 分区切换:上手流程 / 角色与入口 / 常见问题 -->
    <div role="tablist" class="flex gap-1 p-1 rounded-xl bg-neutral-950/80 border border-neutral-800 mb-3">
      <button
        v-for="t in tabs"
        :key="t.key"
        type="button"
        role="tab"
        :aria-selected="active === t.key"
        @click="active = t.key"
        class="flex-1 flex items-center justify-center gap-1.5 px-2 py-2 rounded-lg text-xs font-bold transition-colors"
        :class="
          active === t.key
            ? 'bg-neutral-800 text-amber-400 ring-1 ring-amber-500/40'
            : 'text-neutral-400 hover:text-neutral-200 hover:bg-neutral-800/60'
        "
      >
        <component :is="t.icon" class="w-3.5 h-3.5" />
        <span>{{ t.label }}</span>
      </button>
    </div>

    <div class="max-h-[60vh] overflow-y-auto pr-1">
      <!-- 上手流程:建赛 → 备赛 → 编排 → 开赛 → 投射 -->
      <div v-if="active === 'flow'" class="space-y-2.5">
        <div v-for="(phase, i) in phases" :key="phase.title" class="rounded-xl border border-neutral-800 bg-neutral-950/60 p-3.5">
          <div class="flex items-start gap-3">
            <div
              class="flex-none w-7 h-7 rounded-lg bg-amber-500/15 border border-amber-500/30 text-amber-400 flex items-center justify-center text-xs font-bold"
            >
              {{ i + 1 }}
            </div>
            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
                <h4 class="text-sm font-bold text-neutral-100">{{ phase.title }}</h4>
                <span class="text-[10px] px-1.5 py-0.5 rounded bg-neutral-800 text-neutral-400 border border-neutral-700">
                  {{ phase.where }}
                </span>
                <span class="text-[10px] text-amber-500/80">{{ phase.owner }}</span>
              </div>
              <ul class="mt-2 space-y-1.5">
                <li v-for="(b, bi) in phase.bullets" :key="bi" class="flex gap-2 text-xs text-neutral-400 leading-relaxed">
                  <span class="mt-[6px] w-1 h-1 rounded-full bg-amber-500/70 flex-none"></span>
                  <span class="min-w-0">{{ b }}</span>
                </li>
              </ul>
            </div>
          </div>
        </div>
      </div>

      <!-- 角色与入口:谁在哪个入口做什么 -->
      <div v-else-if="active === 'roles'" class="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
        <div v-for="r in roles" :key="r.name" class="rounded-xl border border-neutral-800 bg-neutral-950/60 p-3.5">
          <div class="flex items-center gap-2">
            <component :is="r.icon" class="w-4 h-4 text-amber-500" />
            <h4 class="text-sm font-bold text-neutral-100">{{ r.name }}</h4>
          </div>
          <p class="mt-1.5 text-[11px] text-amber-500/80">{{ r.entry }}</p>
          <p class="mt-1 text-xs text-neutral-400 leading-relaxed">{{ r.desc }}</p>
        </div>
      </div>

      <!-- 常见问题:现场最容易卡住的点 -->
      <div v-else class="space-y-2">
        <details v-for="(f, i) in faqs" :key="i" class="group rounded-xl border border-neutral-800 bg-neutral-950/60 overflow-hidden">
          <summary
            class="flex items-center gap-2 px-3.5 py-3 cursor-pointer text-sm text-neutral-200 hover:text-amber-400 transition-colors list-none [&::-webkit-details-marker]:hidden"
          >
            <span class="flex-none w-5 h-5 rounded bg-amber-500/15 text-amber-400 text-[11px] font-bold flex items-center justify-center">?</span>
            <span class="flex-1">{{ f.q }}</span>
            <svg
              class="w-3.5 h-3.5 flex-none text-neutral-500 transition-transform group-open:rotate-180"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
            </svg>
          </summary>
          <p class="px-3.5 pb-3 pl-[2.35rem] text-xs text-neutral-400 leading-relaxed">{{ f.a }}</p>
        </details>
      </div>
    </div>

    <template #footer>
      <div class="flex items-center justify-between gap-3">
        <a
          href="https://docs.dancebattle.win/"
          target="_blank"
          rel="noopener noreferrer"
          class="text-xs text-amber-500 hover:text-amber-400 transition-colors"
        >
          查看完整文档
        </a>
        <button
          type="button"
          @click="emit('update:modelValue', false)"
          class="px-5 py-2 rounded-lg text-sm font-bold text-neutral-900 bg-amber-500 hover:bg-amber-400 active:scale-[0.98] transition-all"
        >
          知道了
        </button>
      </div>
    </template>
  </GameDialog>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { CircleHelp, MonitorPlay, Gavel, Smartphone, Settings2, ListChecks, Users, MessageCircleQuestion } from 'lucide-vue-next';
import GameDialog from '@/components/GameDialog/index.vue';

defineProps<{
  modelValue: boolean;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
}>();

type TabKey = 'flow' | 'roles' | 'faq';

const active = ref<TabKey>('flow');

const tabs: { key: TabKey; label: string; icon: unknown }[] = [
  { key: 'flow', label: '上手流程', icon: ListChecks },
  { key: 'roles', label: '角色与入口', icon: Users },
  { key: 'faq', label: '常见问题', icon: MessageCircleQuestion }
];

// 上手流程:按"建赛 → 备赛 → 编排 → 开赛 → 投射"四/五个阶段组织,
// 每段都标清在哪个入口、由谁操作,避免读者先判断"这几步是不是我的"。
const phases = [
  {
    title: '创建赛事',
    where: '管理端 · 赛事大厅',
    owner: '主办方',
    bullets: [
      '点右上角「创建赛事」,填赛事名称、封面与状态。',
      '裁判是必填项:至少添加一名裁判,或点输入框右侧的 X 标记「暂无裁判」。',
      '想省事就选一个模版——会自动生成赛段链(海选 + 淘汰赛)、两个场景(主视觉 / 对战)并对战树关联;不选则创建后手动编排。'
    ]
  },
  {
    title: '备赛:导入名单、加裁判',
    where: '管理端 · 赛事配置',
    owner: '主办方',
    bullets: [
      '「参赛阵容」里用「批量导入」上传 Excel,或点「添加选手」逐个建档案。',
      '现场点选手的「签到」给号码落圈;海选进行中签到会自动加入场次。赛段开始后签到结果锁定,只能补签,不能改号或解除。',
      '「裁判」里添加裁判,每人生成专属二维码发给本人扫码进入判罚页;二维码泄露可在弹窗里「重置」,旧码立即失效。'
    ]
  },
  {
    title: '编排赛段与晋级规则',
    where: '管理端 · 赛段流程',
    owner: '主办方',
    bullets: [
      '增删赛段、设置赛制与规则,配置「谁晋级进哪一段」(来源组 / 出口)。',
      '上一段全部打完、晋级者确定后,要在「中间态」确认名单,下一段才能开赛。',
      '中间态是一份两段之间的临时名单,一行一个座位,改错了随时还原;确认后才成为下一段的正式参赛方。'
    ]
  },
  {
    title: '开赛与判罚',
    where: '手机导播台 + 裁判端(扫码进入)',
    owner: 'MC / 裁判',
    bullets: [
      'MC 点顶部「MC导播台」出二维码,扫码进入:开始赛段、逐场推进,每场判完点「下一场」。',
      '赛段打完点「完成赛段」结算排名;擂台赛逐场轮转,自由对抗由导播手动加场。',
      '裁判扫码进入自己的判罚页,只会看到自己该判的圈 / 场次,打分、判胜负平,不需要管理端账号。'
    ]
  },
  {
    title: '投射大屏给观众',
    where: '大屏(无需登录)',
    owner: '导播 / 观众',
    bullets: [
      '在「屏幕控制」里新建屏幕、拖拽排版,点「投射至屏幕」把场景推上去。',
      '对战树、记分板、倒计时随比赛实时更新;多台机器可同时投射同一画面、互相联动。',
      '点屏幕上的「在新窗口打开」可把大屏放到另一台设备或第二块屏。'
    ]
  }
];

// 角色与入口:四个入口各自"怎么进、能做什么"。
const roles = [
  {
    name: '管理端',
    icon: Settings2,
    entry: '登录后默认进入 · 赛事大厅',
    desc: '建赛、编排赛段、导入名单与签到、确认晋级名单、排版与投射。'
  },
  {
    name: 'MC 手机导播台',
    icon: Smartphone,
    entry: '顶部「MC导播台」→ 扫码进入',
    desc: '开赛、逐场推进、完成赛段结算、公布结果;擂台轮转与自由对抗加场。'
  },
  {
    name: '裁判端',
    icon: Gavel,
    entry: '赛事配置 →「裁判」→ 生成二维码扫码',
    desc: '只看自己该判的圈 / 场次,打分、判胜负平,与他人互不影响。'
  },
  {
    name: '大屏',
    icon: MonitorPlay,
    entry: '点赛事卡片进入工作区,或「屏幕控制」里投射',
    desc: '无需登录。实时展示对战树、记分板、倒计时;多台机器可同步。'
  }
];

// 常见问题:覆盖现场最容易卡住的点(每一条都对应真实行为)。
const faqs = [
  {
    q: '点「开始赛段」是灰的,怎么办?',
    a: '说明上一阶段的晋级者还没确认。到「赛段流程」的「中间态」确认名单即可解锁;如果创建赛事时开了「允许跳过中间态确认」(默认开启),MC 在导播台点开始时会弹窗确认并自动晋级。'
  },
  {
    q: '「中间态」到底是什么?',
    a: '两个赛段之间的一份临时名单,一行一个座位。多条来路的人先进入待落位区,由导播摆到具体座位;确认后才会落到下一段成为正式参赛方。改错了可以随时还原,不影响已经打完的赛段。'
  },
  {
    q: '签到之后会自动排进比赛吗?',
    a: '海选进行中签到会自动加入场次;如果赛段已经开始或结束,签到结果会被锁定——只能补签到,不能改号或解除。'
  },
  {
    q: '裁判 / 导播的二维码泄露了怎么办?',
    a: '在对应的二维码弹窗里点「重置二维码」,旧凭证立即失效,重新把新码发给本人即可。管理端账号不受影响。'
  },
  {
    q: '多台电脑 / 多块屏能放同一个画面吗?',
    a: '可以。屏幕列表按赛事保存在服务端,多台控制端共用同一份;投射后所有终端同步切换场景,断线重连后会自动补上当前画面。'
  },
  {
    q: '能上传哪些素材?有大小限制吗?',
    a: '图片和视频都支持(含 WebP、GIF 等动图),单文件最大 100MB。上传会按文件真实内容校验格式,单纯改扩展名无效。'
  },
  {
    q: '数据会丢吗?',
    a: '赛事数据、上传素材和登录密钥都保存在 data 目录(Docker 容器内为 /data),挂一个命名卷即可持久化;单机模式用的是本地 SQLite 文件库,断网也能正常办赛。'
  }
];
</script>

<style>
/* 帮助中心:窄屏下不超出视口 */
.el-overlay .help-center-dialog {
  max-width: 92vw;
}
</style>
