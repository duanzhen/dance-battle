<template>
  <div class="w-full h-full">
    <!-- 查看模式: 显示视频 -->
    <div v-if="mode !== 'edit'" class="w-full h-full bg-black overflow-hidden select-none relative group">
      <div class="w-full h-full bg-neutral-700/30">
        <video
          ref="videoRef"
          :src="src"
          class="w-full h-full object-fill block pointer-events-none"
          :loop="loop"
          :muted="effectiveMuted"
          :autoplay="autoplay"
          playsinline
            @loadedmetadata="handleLoadedMetadata"
            @loadeddata="applyRemoteState"
            @canplay="applyRemoteState"
            @timeupdate="handleTimeUpdate"
          @play="handlePlay"
          @pause="handlePause"
        ></video>
      </div>

      <!-- 播放控制栏 -->
      <div
        v-if="canControl"
        class="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/80 to-transparent p-3 opacity-0 group-hover:opacity-100 transition-opacity duration-300 pointer-events-auto z-50"
      >
        <div class="flex items-center gap-3">
          <!-- 播放/暂停按钮 -->
          <button @click="togglePlay" class="flex-shrink-0 text-white hover:text-amber-500 transition-colors">
            <svg v-if="isPlaying" class="w-6 h-6" fill="currentColor" viewBox="0 0 24 24">
              <path d="M6 4h4v16H6V4zm8 0h4v16h-4V4z" />
            </svg>
            <svg v-else class="w-6 h-6" fill="currentColor" viewBox="0 0 24 24">
              <path d="M8 5v14l11-7z" />
            </svg>
          </button>

          <!-- 结束按钮:暂停并回到开头 -->
          <button @click="stopPlayback" class="flex-shrink-0 text-white hover:text-amber-500 transition-colors" title="结束">
            <svg class="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
              <path d="M6 6h12v12H6z" />
            </svg>
          </button>

          <!-- 进度条 -->
          <div class="flex-1 flex items-center">
            <input
              type="range"
              min="0"
              max="100"
              :value="progress"
              @input="handleSeeking"
              @change="handleSeekEnd"
              @mousedown="handleSeekStart"
              @touchstart="handleSeekStart"
              class="flex-1 h-1 appearance-none bg-neutral-600 rounded-full cursor-pointer [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:w-3 [&::-webkit-slider-thumb]:h-3 [&::-webkit-slider-thumb]:bg-amber-500 [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:cursor-pointer [&::-webkit-slider-thumb]:transition-transform [&::-webkit-slider-thumb]:hover:scale-125"
            />
          </div>

          <!-- 音量按钮 -->
          <button @click="$emit('update:muted', !muted)" class="flex-shrink-0 text-white hover:text-amber-500 transition-colors">
            <svg v-if="muted" class="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
              <path
                d="M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z"
              />
            </svg>
            <svg v-else class="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
              <path
                d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"
              />
            </svg>
          </button>
        </div>
      </div>
    </div>

    <!-- 编辑模式: 显示属性表单 -->
    <div v-else class="video-editor space-y-4 px-2 py-4">
      <section>
        <span class="section-title">视频属性</span>
        <AssetUpload
          label="素材视频"
          :model-value="src"
          :is-image="false"
          :is-video="true"
          accept="video/*"
          :show-url-input="true"
          @update:model-value="$emit('update:src', $event)"
        />

        <CheckboxGroup
          label="播放选项"
          :model-value="{ loop, muted, autoplay }"
          :options="[
            { key: 'loop', label: '循环播放' },
            { key: 'muted', label: '静音' },
            { key: 'autoplay', label: '自动播放' }
          ]"
          @update:item="handleCheckboxUpdate"
        />

        <div class="space-y-1">
          <label class="label">音量: {{ Math.round((volume || 1) * 100) }}%</label>
          <input type="range" min="0" max="1" step="0.1" :value="volume || 1" @change="handleVolumeChange" class="w-full" />
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, computed } from 'vue';
import AssetUpload from './common/AssetUpload.vue';
import CheckboxGroup from './common/CheckboxGroup.vue';

// --- Props 定义 ---
const props = withDefaults(
  defineProps<{
    src: string;
    // 是否循环播放
    loop?: boolean;
    // 是否静音 (如果要自动播放且页面未交互，通常需要静音，但既然你说有过交互，默认可以为 false)
    muted?: boolean;
    // 是否自动播放
    autoplay?: boolean;
    // 音量 (0.0 ~ 1.0)
    volume?: number;
    mode?: 'view' | 'edit';
    /** 是否展示播放/暂停/结束控制。只有管理端编辑画布传 true;大屏投射端只读 */
    canControl?: boolean;
    /** 以下三个键用于多端同步:是否在播放 / 暂停位置(ms) / 本次播放起点时间戳(ms) */
    videoPlaying?: boolean;
    videoPositionMs?: number | string | null;
    videoStartedAt?: number | string | null;
  }>(),
  {
    loop: true,
    muted: false, // 默认开启声音
    autoplay: true,
    volume: 1.0,
    mode: 'view'
  }
);

// --- Emits ---
const emit = defineEmits<{
  'update:src': [value: string];
  'update:loop': [value: boolean];
  'update:muted': [value: boolean];
  'update:autoplay': [value: boolean];
  'update:volume': [value: number];
  /** 一次提交整份播放状态:是否在播 / 暂停位置(ms) / 本次播放起点时间戳(ms) */
  'update:playback': [value: { videoPlaying: boolean; videoStartedAt: number | null; videoPositionMs: number }];
}>();

// --- 内部逻辑 ---
const videoRef = ref<HTMLVideoElement | null>(null);

// 播放控制状态
const isPlaying = ref(false);
const currentTime = ref(0);
const duration = ref(0);
const isDragging = ref(false);
/** 播放态下周期性对齐进度的定时器(仅大屏/跟随端用) */
let syncTimer: number | null = null;

/**
 * 管理端画布一律静音:同一台设备上画布与投屏会同时播放,不静音会出现重音。
 * 声音只由投射端(非控制端)按配置输出。
 */
const effectiveMuted = computed(() => (props.canControl ? true : !!props.muted));

// 处理复选框更新
const handleCheckboxUpdate = (key: string, value: boolean) => {
  emit(`update:${key}` as any, value);
};

// 处理音量变化
const handleVolumeChange = (e: Event) => {
  const value = parseFloat((e.target as HTMLInputElement).value);
  emit('update:volume', value);
};

// 监听视频元数据加载
const handleLoadedMetadata = () => {
  if (videoRef.value) {
    duration.value = videoRef.value.duration;
  }
  // 元数据就绪后按远端状态落位(大屏/管理端都用同一份播放状态)
  applyRemoteState();
};

/** 远端播放状态(管理端写入)推算出的目标位置(秒) */
const targetPositionSec = () => {
  const base = Number(props.videoPositionMs ?? 0) || 0;
  const startedAt = props.videoStartedAt != null ? Number(props.videoStartedAt) : null;
  const playing = props.videoPlaying === true;
  const elapsed = playing && startedAt ? Math.max(0, Date.now() - startedAt) : 0;
  return (base + elapsed) / 1000;
};

/** 是否已有远端播放状态(没被管理端控制过时保持自动播放) */
const hasRemoteState = computed(
  () => props.videoPlaying !== undefined || props.videoStartedAt != null || props.videoPositionMs != null
);

/** 应用远端播放状态:把本地 video 元素拉到目标进度并按需播放/暂停 */
const desiredPositionSec = () => {
  const target = targetPositionSec();
  const v = videoRef.value;
  const dur = v?.duration ?? 0;
  if (Number.isFinite(dur) && dur > 0) {
    return props.loop ? target % dur : Math.min(target, dur);
  }
  return target;
};

const applyRemoteState = () => {
  const v = videoRef.value;
  if (!v || !hasRemoteState.value) {
    return;
  }
  const t = desiredPositionSec();
  // 只有偏差明显时才 seek,避免每次状态广播都抖动
  if (Math.abs(v.currentTime - t) > 0.5) {
    try {
      v.currentTime = t;
    } catch (e) {
      // 元数据未就绪时忽略
    }
  }
  if (props.videoPlaying) {
    v.play().catch(() => {});
  } else {
    v.pause();
  }
};

/** 用户操作后把播放状态回写(仅管理端画布) */
const emitPlaybackState = (playing: boolean, positionMsOverride?: number) => {
  if (!props.canControl) {
    return;
  }
  const v = videoRef.value;
  const posMs = positionMsOverride != null ? positionMsOverride : v ? Math.round(v.currentTime * 1000) : 0;
  emit('update:playback', {
    videoPlaying: playing,
    videoStartedAt: playing ? Date.now() : null,
    videoPositionMs: posMs
  });
};

// 监听播放进度更新
const handleTimeUpdate = () => {
  if (!isDragging.value && videoRef.value) {
    currentTime.value = videoRef.value.currentTime;
  }
};

// 监听播放状态变化
const handlePlay = () => {
  isPlaying.value = true;
};

const handlePause = () => {
  isPlaying.value = false;
};

// 切换播放/暂停
const togglePlay = async () => {
  if (!videoRef.value) return;

  if (isPlaying.value) {
    videoRef.value.pause();
    emitPlaybackState(false);
  } else {
    try {
      await videoRef.value.play();
      emitPlaybackState(true);
    } catch (e) {
      console.error('Play failed:', e);
    }
  }
};

// 进度条拖动开始
const handleSeekStart = () => {
  isDragging.value = true;
};

// 进度条拖动中
const handleSeeking = (e: Event) => {
  const value = parseFloat((e.target as HTMLInputElement).value);
  currentTime.value = (value / 100) * duration.value;
};

// 进度条拖动结束
const handleSeekEnd = async (e: Event) => {
  isDragging.value = false;
  const value = parseFloat((e.target as HTMLInputElement).value);
  const targetTime = (value / 100) * duration.value;

  if (videoRef.value) {
    videoRef.value.currentTime = targetTime;
  }

  // 如果之前在播放,继续播放
  if (isPlaying.value) {
    try {
      await videoRef.value.play();
    } catch (e) {
      console.error('Play failed:', e);
    }
  }
  // 同步进度到其它端(播放中同时刷新起点时间戳)
  emitPlaybackState(isPlaying.value);
};

// 计算播放进度百分比
const progress = computed(() => {
  if (duration.value === 0) return 0;
  return (currentTime.value / duration.value) * 100;
});

// 监听音量变化实时生效
watch(
  () => props.volume,
  (newVol) => {
    if (videoRef.value) videoRef.value.volume = newVol;
  }
);

// 监听静音状态变化
watch(
  effectiveMuted,
  (m) => {
    if (videoRef.value) videoRef.value.muted = m;
  }
);

// --- 对外暴露的方法 (External Control) ---

// 1. 播放
const play = async () => {
  try {
    await videoRef.value?.play();
  } catch (e) {
    console.error('VideoWidget play failed:', e);
  }
};

// 2. 暂停
const pause = () => {
  videoRef.value?.pause();
};

// 3. 结束:暂停并回到开头
const stopPlayback = () => {
  const v = videoRef.value;
  if (!v) {
    return;
  }
  v.pause();
  try {
    v.currentTime = 0;
  } catch (e) {
    // 元数据还没就绪时忽略
  }
  currentTime.value = 0;
  emitPlaybackState(false, 0);
};

// 4. 跳转时间 (单位: 秒)
const seek = (time: number) => {
  if (videoRef.value) {
    // 确保不超出视频总时长
    const t = Math.max(0, Math.min(time, videoRef.value.duration || Infinity));
    videoRef.value.currentTime = t;
  }
};

// 5. 获取当前状态 (可选)
const getStatus = () => {
  return {
    currentTime: videoRef.value?.currentTime || 0,
    duration: videoRef.value?.duration || 0,
    paused: videoRef.value?.paused || false
  };
};

// 使用 defineExpose 将方法暴露给父组件
defineExpose({
  play,
  pause,
  stopPlayback,
  seek,
  getStatus,
  // 也可以直接把 DOM 暴露出去，看你需求
  videoElement: videoRef
});

onMounted(() => {
  // 初始化音量
  if (videoRef.value) {
    videoRef.value.volume = props.volume;
    videoRef.value.muted = effectiveMuted.value;
  }
  applyRemoteState();
  // 兜底:播放态下周期性对齐进度(阈值 1.5s,避免频繁跳帧),
  // 防止某一次 sceneUpdate 丢失后大屏长期停在错误进度
  syncTimer = window.setInterval(() => {
    if (hasRemoteState.value && props.videoPlaying) {
      const v = videoRef.value;
      if (v && Math.abs(v.currentTime - desiredPositionSec()) > 1.5) {
        applyRemoteState();
      }
    }
  }, 3000);
});

// 管理端控制后,所有端(含大屏投射)跟随同一份播放状态
watch(
  () => [props.videoPlaying, props.videoStartedAt, props.videoPositionMs],
  () => applyRemoteState()
);

onUnmounted(() => {
  if (syncTimer) {
    clearInterval(syncTimer);
    syncTimer = null;
  }
});
</script>

<style scoped>
.section-title {
  @apply text-xs font-bold text-neutral-400 block mb-3;
}

.label {
  @apply text-[10px] font-bold text-neutral-500 uppercase tracking-wider block mb-1;
}
</style>
