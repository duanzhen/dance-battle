<template>
  <div class="flex flex-col h-full bg-neutral-900">
    <div class="px-3 border-b border-neutral-800">
      <h3 class="text-xs font-bold text-neutral-500 uppercase tracking-wider">组件库</h3>
    </div>

    <div class="flex-1 overflow-y-auto p-3 space-y-5 custom-scrollbar-y">
      <div v-for="category in categories" :key="category.title">
        <div class="text-[10px] text-neutral-600 font-bold mb-2 pl-1">{{ category.title }}</div>
        <div class="grid grid-cols-2 gap-2">
          <div
            v-for="item in category.items"
            :key="item.type"
            @click="!item.disabled && handleAddWidget(item.type)"
            :class="[
              'group rounded p-3 flex flex-col items-center justify-center gap-2 transition-all active:scale-95',
              item.disabled
                ? 'bg-neutral-800/50 border border-neutral-800 cursor-not-allowed opacity-50'
                : 'bg-neutral-800 hover:bg-neutral-750 hover:text-amber-500 border border-neutral-700 hover:border-amber-500/50 cursor-pointer'
            ]"
          >
            <component
              :is="item.icon"
              class="w-6 h-6 transition-colors"
              :class="item.disabled ? 'text-neutral-600' : 'text-neutral-400 group-hover:text-amber-500'"
            />
            <span class="text-xs font-medium">{{ item.label }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { useDirectorStore } from '@/store/modules/directorStore';
import {
  Type,
  Image,
  Hash,
  Monitor,
  Trophy,
  Coins,
  Network,
  ScrollText,
  Users,
  ListTree,
  Timer,
  CreditCard,
  Swords,
  ListOrdered
} from 'lucide-vue-next'; // 假设使用 lucide 图标库，或者你自己写 SVG
import { ElMessage } from 'element-plus';

const store = useDirectorStore();

const categories = [
  {
    title: '基础元素',
    items: [
      { label: '文本', type: 'TEXT', icon: Type },
      { label: '图片', type: 'Image', icon: Image },
      { label: '倒计时', type: 'TIMER', icon: Timer },
      { label: '视频流', type: 'Video', icon: Monitor }
    ]
  },
  {
    title: '赛事专用',
    items: [
      { label: '海选名单', type: 'AUDITION', icon: Users },
      { label: '晋级名单', type: 'SCOREBOARD', icon: ScrollText },
      { label: '擂台积分', type: 'ARENA_SCORE', icon: Coins },
      { label: '对战树', type: 'BRACKET', icon: Network },
      { label: '当前场次', type: 'MATCH_DETAIL', icon: Swords },
      { label: '排名展示', type: 'RANKING', icon: ListOrdered }
    ]
  }
];

const handleAddWidget = async (type) => {
  try {
    await store.addWidget(type);
  } catch (error) {
    console.error('添加组件失败:', error);
    ElMessage.error(error.message || '添加组件失败，请稍后重试');
  }
};
</script>
