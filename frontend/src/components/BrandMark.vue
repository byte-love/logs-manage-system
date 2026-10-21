<template>
  <span
    class="brand-mark"
    :class="`brand-mark--${variant}`"
    :role="decorative ? undefined : 'img'"
    :aria-label="decorative ? undefined : 'DataHub 示例数据服务平台'"
    :aria-hidden="decorative ? 'true' : undefined"
  ></span>
</template>

<script setup>
defineProps({
  // on-dark 深色底（登录页），on-light 浅色底（内页页脚）
  variant: { type: String, default: 'on-light' },
  // 纯装饰时不进无障碍树：同页已有别的地方念过品牌名，避免读屏重复
  decorative: { type: Boolean, default: false }
})
</script>

<style scoped>
/* 原图是透明底 + 单一色相（实测色相 198°~200°，#2189B8 占多数），
   所以不当普通 <img> 用，而是拿它当 mask 再刷色：
   深色底刷亮色、浅色底刷淡色，各自融进背景，不会像贴上去的白块。
   用 mask 而非 filter，是因为 filter 只能整体偏移色相/亮度，
   刷不出跟页面呼应的渐变。 */
.brand-mark {
  display: block;
  width: 100%;
  /* 863x119 ≈ 7.25，用 aspect-ratio 锁住，避免任何拉伸 */
  aspect-ratio: 863 / 119;
  mask-image: url('../assets/datahub-logo.png');
  mask-repeat: no-repeat;
  mask-position: center;
  mask-size: contain;
  -webkit-mask-image: url('../assets/datahub-logo.png');
  -webkit-mask-repeat: no-repeat;
  -webkit-mask-position: center;
  -webkit-mask-size: contain;
}

/* 深色底：用登录页本来就有的青→蓝→紫做渐变，跟背景光斑同一套色 */
.brand-mark--on-dark {
  background: linear-gradient(100deg, #8ce8ff 0%, #a9c8ff 52%, #c3b4ff 100%);
  opacity: 0.92;
  filter: drop-shadow(0 2px 14px rgba(90, 215, 255, 0.28));
}

/* 浅色底：压到品牌蓝的低透明度，作水印用，不抢表格内容 */
.brand-mark--on-light {
  background: linear-gradient(100deg, #2189b8 0%, #3f7fb5 55%, #5c74ad 100%);
  opacity: 0.42;
}
</style>
