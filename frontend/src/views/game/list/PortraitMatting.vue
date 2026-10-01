<template>
  <div class="matting-container">
    <input ref="fileInputRef" type="file" accept="image/*" @change="handleFileUpload" hidden />

    <div
      class="canvas-wrapper"
      :class="{ 'has-image': hasProcessedImg, 'is-dragging': isFileDragging, 'is-uploading': isUploading }"
      @click="!hasProcessedImg && triggerFileSelect()"
      @dragover.prevent="handleFileDragOver"
      @dragleave.prevent="handleFileDragLeave"
      @drop.prevent="handleFileDrop"
      @wheel.prevent="handleWheel"
    >
      <!-- v-if 而不是直接绑 src:清空 src 时浏览器会当成一次加载失败(触发 error),
           而且卸载元素能让浏览器回收那张全分辨率位图 -->
      <img v-if="imgSrc" ref="sourceImg" :src="imgSrc" @load="onImageLoad" @error="onImageError" style="display: none" />

      <div v-if="isProcessing" class="loading-overlay">
        <span>AI 正在处理中...</span>
      </div>

      <div v-if="isUploading" class="loading-overlay">
        <span>正在上传...</span>
      </div>

      <!-- 500x500 固定大小画布 -->
      <canvas
        ref="canvasRef"
        width="500"
        height="500"
        @mousedown="hasProcessedImg && handleMouseDown($event)"
        @touchstart.prevent="handleTouchStart"
        @touchmove.prevent="handleTouchMove"
        @touchend="handleTouchEnd"
      ></canvas>

      <!-- 人像单线轮廓:辅助把人物摆到统一位置(头/肩对齐,人靠画布底部),不挡拖拽缩放 -->
      <svg v-if="hasProcessedImg && showPoseGuide" class="pose-guide" viewBox="0 0 1024 1024" preserveAspectRatio="xMidYMid meet" aria-hidden="true">
        <!--
          头 + 肩单线轮廓:以"肩线中点"为锚点等比放大后,把该锚点落在画布底边中点 ——
          人靠底、只放大不拉伸。改 scale 的倍数即可整体放大/缩小。
        -->
        <g transform="translate(501.333333 1024) scale(1.2) translate(-501.333333 -882.773333)">
          <!-- 头:一个圆,一根线 -->
          <circle class="pose-guide-shape" cx="501.333333" cy="320" r="192" />
          <!-- 肩:一条开口弧线,两端贴底边,不闭合 -->
          <path class="pose-guide-shape" d="M170.666667 882.773333c0-229.546667 149.333333-416 330.666666-416S832 653.226667 832 882.773333" />
        </g>
      </svg>

      <div v-if="!hasProcessedImg" class="placeholder">
        <div class="placeholder-icon"><Upload :size="48" /></div>
        <div class="placeholder-text">选择图片</div>
        <div class="placeholder-hint">请选择JPG/PNG 支持拖拽</div>
      </div>

      <div v-if="hasProcessedImg" class="canvas-hint">
        <span class="hidden sm:inline">拖动移动 滚轮缩放</span>
        <span class="sm:hidden">单指拖动 双指缩放</span>
      </div>

      <!-- 右上角移除照片:清空后回到「选择图片」空态,可重新选择/替换 -->
      <button v-if="hasProcessedImg" type="button" class="clear-btn" title="移除照片" @click.stop="clearImage">
        <X :size="16" />
      </button>

      <!-- 左上角:对齐轮廓显隐开关 -->
      <button
        v-if="hasProcessedImg"
        type="button"
        class="guide-btn"
        :class="{ 'is-off': !showPoseGuide }"
        :title="showPoseGuide ? '隐藏对齐轮廓' : '显示对齐轮廓'"
        @click.stop="showPoseGuide = !showPoseGuide"
      >
        <User :size="16" />
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, onBeforeUnmount, getCurrentInstance } from 'vue';
import { Upload, User, X } from 'lucide-vue-next';
import { acquireSelfieSegmentation, releaseSelfieSegmentation } from '@/utils/selfieSegmentation';
import request from '@/utils/request';
import { globalHeaders } from '@/utils/request';

const props = defineProps({
  modelValue: {
    type: String,
    default: ''
  }
});

const emit = defineEmits(['update:modelValue']);

const { proxy } = getCurrentInstance();

// 常量
const CANVAS_SIZE = 500;
/**
 * 送进 MediaPipe 的图片尺寸上限(最长边 / 总像素)。
 *
 * <p>手机原图动辄 4000x3000 甚至上亿像素,MediaPipe 会按原图尺寸建 WebGL 纹理;
 * 超过 GPU 纹理上限(常见 4096/8192)或显存不够时,wasm 会直接 abort、GL 上下文丢失,
 * 表现就是「某些图片一选就闪退 / 标签页重载」。模型内部本来就只吃 256x256,
 * 所以先等比缩到安全尺寸再送进去,画质不影响,崩溃面直接消掉。</p>
 */
const MAX_WORK_EDGE = 1600;
const MAX_WORK_PIXELS = 1600 * 1600;
/** send 之后迟迟不回调(运行时被 abort / 上下文丢失)时的兜底时长 */
const PROCESS_TIMEOUT_MS = 20000;
/** 冷启动还要等 wasm + 模型下载(5MB 多的 wasm),弱网下会更久,单独给更长的兜底 */
const COLD_START_TIMEOUT_MS = 60000;
/** 单张原图大小上限:几十 MB 的图解码后内存要翻好几倍,移动端很容易 OOM */
const MAX_FILE_SIZE = 20 * 1024 * 1024;

// --- 响应式状态 ---
const imgSrc = ref('');
const canvasRef = ref(null);
const sourceImg = ref(null);
const fileInputRef = ref(null);
const isProcessing = ref(false);
const isUploading = ref(false);
const isFileDragging = ref(false);
const hasProcessedImg = ref(false);
/** 对齐轮廓(半透明人形)显隐:默认开启,便于把人物摆到统一位置 */
const showPoseGuide = ref(true);
/**
 * 0 = 通用模型(256x256),1 = 横向模型(256x144)。
 * 这里处理的是竖构图人像,通用模型的人像边缘明显更稳,所以用 0。
 */
const modelSelection = ref(0);

// 图片变换状态
const scale = ref(1);
const offsetX = ref(0);
const offsetY = ref(0);

// 改动标记
const hasChanges = ref(false);

// 拖拽状态
const isDragging = ref(false);
const dragStartX = ref(0);
const dragStartY = ref(0);
// 拖拽起始时的画布偏移(画布像素),与屏幕起点配合计算
const dragStartOffsetX = ref(0);
const dragStartOffsetY = ref(0);
// 触屏双指缩放
const pinchStartDist = ref(0);
const pinchStartScale = ref(1);

// --- MediaPipe 句柄(真正实例缓存在模块级单例里,见 utils/selfieSegmentation) ---
let selfieSegmentation = null;
let processedCanvas = null; // 存储抠图后的原始图片
let processedCtx = null;
// MediaPipe 的工作画布:原图等比缩小后的副本,检测/抠图/导出都基于它
let workCanvas = null;
let workCtx = null;
// 当前选中文件的 objectURL,需要主动释放,避免大图一直占着内存
let sourceObjectUrl = null;
let processTimer = null;
// 模型是否已加载完成:决定抠图超时用冷启动时长还是常规时长
let modelReady = false;

// --- 初始化/复用 MediaPipe(整页唯一实例,后续挂载只重绑回调) ---
const initMediaPipe = () => {
  selfieSegmentation = acquireSelfieSegmentation({ modelSelection: modelSelection.value }, onResults);
  return selfieSegmentation;
};

const clearProcessTimer = () => {
  if (processTimer) {
    clearTimeout(processTimer);
    processTimer = null;
  }
};

const revokeSourceObjectUrl = () => {
  if (sourceObjectUrl) {
    URL.revokeObjectURL(sourceObjectUrl);
    sourceObjectUrl = null;
  }
};

/** 置 0 尺寸才会真正把画布的像素缓冲还给浏览器(只清 rect 是不够的) */
const destroyCanvas = (canvas) => {
  if (canvas) {
    canvas.width = 0;
    canvas.height = 0;
  }
};

/**
 * 释放这一轮抠图/编辑占下的大块内存:全分辨率原图位图、工作画布、objectURL。
 * 抠图结果保存成功后调用;500x500 预览画布保留(保存后组件可能还挂在页面上,马上会被保存结果替换)。
 * 注意只清组件自己的东西,MediaPipe 实例和已加载的模型保持常驻,不重新加载。
 */
const releaseMattingMemory = () => {
  revokeSourceObjectUrl();
  // imgSrc 清空 → v-if 卸载隐藏 img → 全分辨率位图可被回收(12MP 大概 48MB)
  imgSrc.value = '';

  destroyCanvas(workCanvas);
  workCanvas = null;
  workCtx = null;
};

/** 连预览一起清掉(移除照片 / 组件卸载时用) */
const disposeMattingMemory = () => {
  releaseMattingMemory();
  destroyCanvas(processedCanvas);
  processedCanvas = null;
  processedCtx = null;
};

/**
 * 把原图等比缩到 MediaPipe 的安全尺寸,生成/复用一张离屏工作画布。
 * 超限的图不再进模型,也就不会出现「大图必崩」。
 */
const buildWorkCanvas = (img) => {
  const width = img.naturalWidth || img.width;
  const height = img.naturalHeight || img.height;
  if (!width || !height) return null;

  const edgeScale = Math.min(1, MAX_WORK_EDGE / Math.max(width, height));
  let targetW = Math.max(1, Math.round(width * edgeScale));
  let targetH = Math.max(1, Math.round(height * edgeScale));

  // 极端长图(如全景细长图)按边长缩完像素量仍可能偏大,再按总面积兜一次底
  const pixelScale = Math.min(1, Math.sqrt(MAX_WORK_PIXELS / (targetW * targetH)));
  if (pixelScale < 1) {
    targetW = Math.max(1, Math.round(targetW * pixelScale));
    targetH = Math.max(1, Math.round(targetH * pixelScale));
  }

  if (!workCanvas) {
    workCanvas = document.createElement('canvas');
    workCtx = workCanvas.getContext('2d', { willReadFrequently: true });
  }

  workCanvas.width = targetW;
  workCanvas.height = targetH;
  workCtx.clearRect(0, 0, targetW, targetH);
  workCtx.imageSmoothingEnabled = true;
  workCtx.imageSmoothingQuality = 'high';
  workCtx.drawImage(img, 0, 0, targetW, targetH);

  return workCanvas;
};

// --- 加载编辑图片 ---
const loadEditImage = async (url) => {
  return new Promise<void>((resolve, reject) => {
    const img = new Image();
    img.crossOrigin = 'anonymous';

    img.onload = () => {
      // 创建离屏 canvas
      if (!processedCanvas) {
        processedCanvas = document.createElement('canvas');
        processedCtx = processedCanvas.getContext('2d');
      }

      // 编辑模式下，加载的是 500x500 的输出图片
      processedCanvas.width = img.width;
      processedCanvas.height = img.height;

      // 绘制图片到离屏 canvas
      processedCtx.clearRect(0, 0, processedCanvas.width, processedCanvas.height);
      processedCtx.drawImage(img, 0, 0);

      // 编辑模式：图片已经是 500x500，直接 1:1 显示，不做缩放和位置调整
      scale.value = 1;
      offsetX.value = 0;
      offsetY.value = 0;

      hasProcessedImg.value = true;
      hasChanges.value = false; // 编辑模式初始状态无改动

      render();
      resolve();
    };

    img.onerror = () => {
      proxy.$modal.msgError('加载图片失败');
      reject(new Error('Failed to load image'));
    };

    img.src = url;
  });
};

// --- 重置位置和缩放 ---
const resetPosition = () => {
  if (!processedCanvas?.width || !processedCanvas?.height) return;

  // 计算合适的缩放比例，使图片占据约 85% 画布高度
  const targetHeight = CANVAS_SIZE * 0.85;
  let calculatedScale = targetHeight / processedCanvas.height;

  // 确保缩放后宽度也不超过画布
  const maxScaleForWidth = CANVAS_SIZE / processedCanvas.width;
  calculatedScale = Math.min(calculatedScale, maxScaleForWidth);

  scale.value = calculatedScale;

  // 居中
  offsetX.value = (CANVAS_SIZE - processedCanvas.width) / 2;
  offsetY.value = (CANVAS_SIZE - processedCanvas.height) / 2;

  render();
};

// --- 渲染到显示画布 ---
const render = () => {
  if (!canvasRef.value) return;

  const canvas = canvasRef.value;
  const ctx = canvas.getContext('2d');

  // 清空画布
  ctx.clearRect(0, 0, CANVAS_SIZE, CANVAS_SIZE);

  // processedCanvas 可能刚被回收掉(置 0 尺寸),这种情况只清屏不画
  if (!hasProcessedImg.value || !processedCanvas?.width || !processedCanvas?.height) {
    return;
  }

  // 应用变换（以图片中心为参考点）
  ctx.save();
  // 平移到图片中心在画布上的位置
  ctx.translate(offsetX.value + processedCanvas.width / 2, offsetY.value + processedCanvas.height / 2);
  // 缩放
  ctx.scale(scale.value, scale.value);
  // 平移回去，使图片中心对齐到原点
  ctx.translate(-processedCanvas.width / 2, -processedCanvas.height / 2);

  // 绘制抠图
  ctx.drawImage(processedCanvas, 0, 0);
  ctx.restore();
};

// --- 监听 modelValue 变化，支持编辑模式 ---
watch(
  () => props.modelValue,
  async (newUrl) => {
    if (newUrl && newUrl !== imgSrc.value) {
      // 加载已保存的图片进行编辑
      await loadEditImage(newUrl);
    } else if (!newUrl) {
      // 清空状态
      hasProcessedImg.value = false;
      disposeMattingMemory();
      render();
    }
  },
  { immediate: true }
);

// --- 触发文件选择 ---
const triggerFileSelect = () => {
  fileInputRef.value?.click();
};

/**
 * 移除当前照片:回到「选择图片」空态,并把空串回传给父组件(保存时即清空头像)。
 *
 * <p>同时清掉 imgSrc:否则清空后再选中同一张图,隐藏 img 的 :src 没变化、
 * 不会触发 load,新图就加载不出来。</p>
 */
const clearImage = () => {
  if (isProcessing.value || isUploading.value) return;
  clearProcessTimer();
  hasProcessedImg.value = false;
  hasChanges.value = false;
  disposeMattingMemory();
  render();
  emit('update:modelValue', '');
};

/** 隐藏 img 加载失败(坏文件、浏览器不支持的格式如 HEIC):给个明确提示,别让界面卡在空态 */
const onImageError = () => {
  isProcessing.value = false;
  clearProcessTimer();
  proxy.$modal.msgError('图片加载失败,请更换为 JPG/PNG 图片');
};

// --- 文件上传处理 ---
const handleFileUpload = (event) => {
  const file = event.target.files[0];
  handleFile(file);
  // 清空 input，允许重复选择同一文件
  event.target.value = '';
};

// --- 处理单个文件 ---
const handleFile = (file) => {
  if (!file) return;
  if (!file.type.startsWith('image/')) {
    proxy.$modal.msgError('请选择有效的图片文件！');
    return;
  }
  if (file.size > MAX_FILE_SIZE) {
    proxy.$modal.msgError('图片太大了(超过 20MB),请压缩后再选择');
    return;
  }
  // 上一张还在跑:换图会让 send 的运行状态错乱,直接挡住
  if (isProcessing.value) {
    proxy.$modal.msgWarning('正在处理上一张图片，请稍候');
    return;
  }

  // 用 objectURL 而不是 FileReader 转 base64:后者要整张图多存一份 1.33 倍的字符串,
  // 大图在移动端很容易顶到内存上限。每次重新生成 URL 也顺带解决「清空后再选同一张图不触发 load」
  revokeSourceObjectUrl();
  sourceObjectUrl = URL.createObjectURL(file);
  imgSrc.value = sourceObjectUrl;
};

// --- 文件拖拽事件处理 ---
const handleFileDragOver = () => {
  isFileDragging.value = true;
};

const handleFileDragLeave = () => {
  isFileDragging.value = false;
};

const handleFileDrop = (event) => {
  isFileDragging.value = false;
  const file = event.dataTransfer.files[0];
  handleFile(file);
};

// --- 检测图片是否已经是透明背景 ---
// source 传的是已经缩过的 workCanvas,避免在全分辨率原图上 getImageData(大图会瞬间吃掉几百 MB)
const checkTransparentBackground = (source) => {
  return new Promise((resolve) => {
    try {
      const width = source?.naturalWidth || source?.width || 0;
      const height = source?.naturalHeight || source?.height || 0;
      if (!width || !height) {
        resolve(false);
        return;
      }

      const canvas = document.createElement('canvas');
      const ctx = canvas.getContext('2d', { willReadFrequently: true });
      canvas.width = width;
      canvas.height = height;

      ctx.drawImage(source, 0, 0, width, height);

      const imageData = ctx.getImageData(0, 0, width, height);
      const pixels = imageData.data;

      // 检查边缘区域的透明度（边缘通常更容易有背景）
      const edgeWidth = Math.min(50, Math.floor(width / 4));
      const edgeHeight = Math.min(50, Math.floor(height / 4));

      let transparentPixels = 0;
      let totalEdgePixels = 0;

      const countPixel = (x, y) => {
        totalEdgePixels++;
        if (pixels[(y * width + x) * 4 + 3] < 128) {
          transparentPixels++;
        }
      };

      // 只扫四条边缘带,不遍历整图(原实现 1600x1600 要跑 250 万次,纯浪费)
      for (let y = 0; y < height; y++) {
        if (y < edgeHeight || y >= height - edgeHeight) {
          for (let x = 0; x < width; x++) {
            countPixel(x, y);
          }
        } else {
          for (let x = 0; x < edgeWidth; x++) {
            countPixel(x, y);
          }
          for (let x = Math.max(edgeWidth, width - edgeWidth); x < width; x++) {
            countPixel(x, y);
          }
        }
      }

      // 如果边缘透明像素超过 30%，认为是透明背景图
      const transparentRatio = totalEdgePixels > 0 ? transparentPixels / totalEdgePixels : 0;
      resolve(transparentRatio > 0.3);
    } catch (e) {
      // 跨域图会因 canvas 被污染而抛 SecurityError;这种情况退回「按需要抠图」的路径
      console.warn('透明背景检测失败,按普通图片处理:', e);
      resolve(false);
    }
  });
};

// --- 图片加载完毕，开始处理 ---
const onImageLoad = async () => {
  const img = sourceImg.value;
  if (!img) return;

  // 原图先缩到工作尺寸,后面的检测/抠图/合成全部基于它
  const work = buildWorkCanvas(img);
  if (!work) {
    proxy.$modal.msgError('图片尺寸异常,请更换图片');
    return;
  }

  // 先检测是否已经是透明背景
  const isTransparent = await checkTransparentBackground(work);

  if (isTransparent) {
    // 已经是透明背景，直接使用原图
    loadOriginalImage();
  } else {
    // 需要抠图处理
    await processImage();
  }
};

// --- 直接加载原图（跳过 AI 处理） ---
const loadOriginalImage = () => {
  if (!workCanvas) return;

  // 创建离屏 canvas
  if (!processedCanvas) {
    processedCanvas = document.createElement('canvas');
    processedCtx = processedCanvas.getContext('2d');
  }

  processedCanvas.width = workCanvas.width;
  processedCanvas.height = workCanvas.height;

  // 直接绘制原图
  processedCtx.clearRect(0, 0, processedCanvas.width, processedCanvas.height);
  processedCtx.drawImage(workCanvas, 0, 0);

  // 重置位置和缩放
  resetPosition();
  hasProcessedImg.value = true;
  hasChanges.value = true;

  // 初始渲染
  render();
};

// --- 执行抠图逻辑 ---
const processImage = async () => {
  if (!workCanvas || !selfieSegmentation || isProcessing.value) return;

  isProcessing.value = true;
  clearProcessTimer();
  // send 在运行时被 abort / GL 上下文丢失时可能既不 resolve 也不回调,
  // 没有兜底就会出现「AI 正在处理中...」永久卡死,再点几次只会把运行时彻底打死
  processTimer = setTimeout(
    () => {
      if (isProcessing.value) {
        isProcessing.value = false;
        proxy.$modal.msgError('自动抠图超时,请重试或更换图片');
      }
    },
    modelReady ? PROCESS_TIMEOUT_MS : COLD_START_TIMEOUT_MS
  );

  try {
    selfieSegmentation.setOptions({
      modelSelection: modelSelection.value
    });
    // 送缩过的画布,不送原图:大图直接进模型是之前闪退的主因
    await selfieSegmentation.send({ image: workCanvas });
  } catch (e) {
    // 之前没有兜底:send 抛错会留下"AI 正在处理中..."卡死,再点一次更容易把运行时打死
    console.error('自动抠图失败:', e);
    clearProcessTimer();
    isProcessing.value = false;
    proxy.$modal.msgError('自动抠图失败,请重试或更换图片');
  }
};

// --- AI 处理回调 ---
const onResults = (results) => {
  clearProcessTimer();
  try {
    if (!results?.image || !results?.segmentationMask) {
      throw new Error('抠图结果为空');
    }
    // 能出结果就说明模型确实跑起来了(预加载失败过也能在这里补上)
    modelReady = true;

    // 创建离屏 canvas 保存抠图结果
    if (!processedCanvas) {
      processedCanvas = document.createElement('canvas');
      processedCtx = processedCanvas.getContext('2d');
    }

    processedCanvas.width = results.image.width;
    processedCanvas.height = results.image.height;

    processedCtx.save();
    processedCtx.clearRect(0, 0, processedCanvas.width, processedCanvas.height);

    // 1. 绘制分割掩码
    processedCtx.drawImage(results.segmentationMask, 0, 0, processedCanvas.width, processedCanvas.height);

    // 2. 使用 source-in 混合模式
    processedCtx.globalCompositeOperation = 'source-in';

    // 3. 绘制原始图片
    processedCtx.drawImage(results.image, 0, 0, processedCanvas.width, processedCanvas.height);

    processedCtx.restore();

    // 重置位置和缩放
    resetPosition();
    hasProcessedImg.value = true;
    hasChanges.value = true; // 新上传的图片，有改动

    // 初始渲染
    render();
  } catch (e) {
    // 回调里抛错会顺着 MediaPipe 调用栈把运行时带崩,这里必须自己兜住
    console.error('抠图结果处理失败:', e);
    proxy.$modal.msgError('抠图失败,请重试或更换图片');
  } finally {
    isProcessing.value = false;
  }
};

// --- 鼠标拖拽事件 ---
/** 画布显示缩放系数:画布 CSS 宽度 / 逻辑尺寸(500),移动端自适应后坐标需按此换算 */
const displayScale = () => {
  const canvas = canvasRef.value;
  if (!canvas) return 1;
  const rect = canvas.getBoundingClientRect();
  return rect.width > 0 ? rect.width / CANVAS_SIZE : 1;
};

const handleMouseDown = (event) => {
  if (!hasProcessedImg.value) return;

  isDragging.value = true;
  dragStartX.value = event.clientX;
  dragStartY.value = event.clientY;
  dragStartOffsetX.value = offsetX.value;
  dragStartOffsetY.value = offsetY.value;

  document.addEventListener('mousemove', handleMouseMove);
  document.addEventListener('mouseup', handleMouseUp);
};

const handleMouseMove = (event) => {
  if (!isDragging.value) return;

  const s = displayScale();
  offsetX.value = dragStartOffsetX.value + (event.clientX - dragStartX.value) / s;
  offsetY.value = dragStartOffsetY.value + (event.clientY - dragStartY.value) / s;

  hasChanges.value = true; // 拖拽位置，有改动
  render();
};

const handleMouseUp = () => {
  isDragging.value = false;
  document.removeEventListener('mousemove', handleMouseMove);
  document.removeEventListener('mouseup', handleMouseUp);
};

// --- 触屏拖拽 / 双指缩放 ---
const touchDist = (touches) => {
  if (!touches || touches.length < 2) return 0;
  const dx = touches[0].clientX - touches[1].clientX;
  const dy = touches[0].clientY - touches[1].clientY;
  return Math.sqrt(dx * dx + dy * dy);
};

const handleTouchStart = (event) => {
  if (!hasProcessedImg.value) return;
  const touches = event.touches;
  if (touches.length === 1) {
    isDragging.value = true;
    dragStartX.value = touches[0].clientX;
    dragStartY.value = touches[0].clientY;
    dragStartOffsetX.value = offsetX.value;
    dragStartOffsetY.value = offsetY.value;
  } else if (touches.length === 2) {
    pinchStartDist.value = touchDist(touches);
    pinchStartScale.value = scale.value;
  }
};

const handleTouchMove = (event) => {
  if (!hasProcessedImg.value) return;
  const touches = event.touches;
  if (touches.length === 1 && isDragging.value) {
    const s = displayScale();
    offsetX.value = dragStartOffsetX.value + (touches[0].clientX - dragStartX.value) / s;
    offsetY.value = dragStartOffsetY.value + (touches[0].clientY - dragStartY.value) / s;
    hasChanges.value = true;
    render();
  } else if (touches.length === 2 && pinchStartDist.value > 0) {
    const d = touchDist(touches);
    if (d > 0) {
      scale.value = Math.max(0.1, Math.min(3, pinchStartScale.value * (d / pinchStartDist.value)));
      hasChanges.value = true;
      render();
    }
  }
};

const handleTouchEnd = () => {
  isDragging.value = false;
  pinchStartDist.value = 0;
};

// --- 滚轮缩放事件 ---
const handleWheel = (event) => {
  if (!hasProcessedImg.value) return;

  const delta = event.deltaY > 0 ? -0.01 : 0.01;
  scale.value = Math.max(0.1, Math.min(3, scale.value + delta));

  hasChanges.value = true; // 缩放，有改动
  render();
};

// --- 上传图片到服务器(本地直传) ---
const uploadToServer = async (blob) => {
  try {
    const formData = new FormData();
    formData.append('file', blob, 'avatar.png');
    const res = await request({
      url: '/resource/oss/upload',
      method: 'post',
      headers: {
        ...globalHeaders()
      },
      data: formData
    });
    if (res.code !== 200 || !res.data?.url) {
      throw new Error(res?.msg || '上传失败');
    }
    // 返回文件 URL
    return { url: res.data.url };
  } catch (error) {
    throw error;
  }
};

// --- 导出图片 ---
const exportImage = async () => {
  if (!canvasRef.value || !hasProcessedImg.value) {
    proxy.$modal.msgWarning('请先上传并处理图片！');
    return null;
  }

  // 如果没有改动，直接返回现有 URL
  if (!hasChanges.value && props.modelValue) {
    return props.modelValue;
  }

  try {
    isUploading.value = true;

    // 将 canvas 转为 blob
    const blob = await new Promise((resolve) => {
      canvasRef.value.toBlob(resolve, 'image/png');
    });

    // 上传到服务器
    const result = await uploadToServer(blob);

    // 返回 url
    emit('update:modelValue', result.url);
    hasChanges.value = false; // 上传后重置改动标记

    // 保存完成:这一轮的原图位图和工作画布立刻还回去(模型保持常驻,不动)
    releaseMattingMemory();

    return result.url;
  } catch (error) {
    proxy.$modal.msgError((error as Error).message || '上传失败');
    return null;
  } finally {
    isUploading.value = false;
  }
};

// --- 对外暴露方法 ---
defineExpose({
  exportImage,
  hasProcessedImg
});

// --- 生命周期 ---
onMounted(() => {
  initMediaPipe();
  // 提前把 wasm + 模型拉起来:用户选图的这段时间正好用来加载,第一次抠图不用再等,
  // 也不会因为冷启动太慢被上面的超时误判成失败
  selfieSegmentation
    ?.initialize()
    .then(() => {
      modelReady = true;
    })
    .catch((e) => {
      console.warn('MediaPipe 预加载失败,首次抠图时会自动重试:', e);
    });
});

onBeforeUnmount(() => {
  // 只退订回调、释放本地内存;MediaPipe 实例整页复用,不能 close
  clearProcessTimer();
  releaseSelfieSegmentation(onResults);
  disposeMattingMemory();
});
</script>

<style scoped>
.matting-container {
  width: 100%;
  max-width: 350px;
  margin: 0 auto;
  font-family: sans-serif;
  border: 1px solid #eee;
  padding: 16px;
  border-radius: 8px;
  box-sizing: border-box;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.canvas-wrapper {
  position: relative;
  width: 100%;
  max-width: 300px;
  aspect-ratio: 1 / 1;
  margin: 0 auto;
  border: 2px dashed #ccc;
  display: flex;
  justify-content: center;
  align-items: center;
  overflow: hidden;
  transition: all 0.3s ease;
}

/* 无图片时的样式 */
.canvas-wrapper:not(.has-image) {
  background-color: #2a2a2a;
  background-image:
    linear-gradient(45deg, #3a3a3a 25%, transparent 25%), linear-gradient(-45deg, #3a3a3a 25%, transparent 25%),
    linear-gradient(45deg, transparent 75%, #3a3a3a 75%), linear-gradient(-45deg, transparent 75%, #3a3a3a 75%);
  background-size: 20px 20px;
  background-position:
    0 0,
    0 10px,
    10px -10px,
    -10px 0px;
  cursor: pointer;
}

.canvas-wrapper:not(.has-image):hover {
  border-color: #3498db;
  background-color: rgba(52, 152, 219, 0.05);
}

.canvas-wrapper.is-dragging {
  border-color: #2ecc71;
  background-color: rgba(46, 204, 113, 0.1);
  border-style: solid;
}

/* 有图片时的样式 */
.canvas-wrapper.has-image {
  cursor: grab;
  border-style: solid;
}

.canvas-wrapper.has-image:active {
  cursor: grabbing;
}

canvas {
  display: block;
  width: 100%;
  height: 100%;
  background-color: #1a1a1a;
  background-image:
    linear-gradient(45deg, #2a2a2a 25%, transparent 25%), linear-gradient(-45deg, #2a2a2a 25%, transparent 25%),
    linear-gradient(45deg, transparent 75%, #2a2a2a 75%), linear-gradient(-45deg, transparent 75%, #2a2a2a 75%);
  background-size: 20px 20px;
  background-position:
    0 0,
    0 10px,
    10px -10px,
    -10px 0px;
}

/* 无图片时 canvas 不响应鼠标事件，让点击穿透到父容器 */
.canvas-wrapper:not(.has-image) canvas {
  pointer-events: none;
}

.placeholder {
  position: absolute;
  text-align: center;
  padding: 40px 20px;
  pointer-events: none;
}

.placeholder-icon {
  font-size: 48px;
  margin-bottom: 16px;
}

.placeholder-text {
  font-size: 18px;
  font-weight: 500;
  color: #ddd;
  margin-bottom: 8px;
}

.placeholder-hint {
  font-size: 14px;
  color: #aaa;
}

.canvas-hint {
  position: absolute;
  bottom: 10px;
  left: 50%;
  transform: translateX(-50%);
  background: rgba(0, 0, 0, 0.35);
  color: white;
  padding: 6px 12px;
  border-radius: 20px;
  font-size: 12px;
  pointer-events: none;
  user-select: none;
}

/* 右上角移除照片:浮在画布之上,上传/抠图时被 loading-overlay 盖住不可点 */
.clear-btn {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  color: #fff;
  background: rgba(0, 0, 0, 0.55);
  transition: background 0.2s ease;
}

.clear-btn:hover {
  background: rgba(220, 38, 38, 0.85);
}

/* 半透明人形轮廓:盖在画布之上但不吃事件,拖拽/缩放照常 */
.pose-guide {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 2;
}

.pose-guide-shape {
  /* 单线轮廓:只描边不填充,线细一点但看得清 */
  fill: none;
  stroke: rgba(255, 255, 255, 0.55);
  stroke-width: 3;
  stroke-dasharray: 14 12;
  stroke-linecap: round;
}

/* 左上角:对齐轮廓开关 */
.guide-btn {
  position: absolute;
  top: 8px;
  left: 8px;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 50%;
  cursor: pointer;
  color: #e5e5e5;
  background: rgba(0, 0, 0, 0.55);
  transition:
    color 0.2s ease,
    border-color 0.2s ease;
}

.guide-btn:hover {
  color: #fbbf24;
  border-color: rgba(251, 191, 36, 0.4);
}

.guide-btn.is-off {
  color: #737373;
}

.loading-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.75);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 10;
  font-weight: bold;
  color: #fff;
}
</style>
