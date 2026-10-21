<template>
  <!-- 顶栏只占右侧这一块，高度与左侧品牌区一致（64px），
       两条分隔线在同一水平线上贯通整宽 -->
  <header class="topbar">
    <button class="icon-btn" title="收起 / 展开侧栏" @click="emit('toggle')">☰</button>

    <div class="crumb">
      <!-- 二级项一律带父级前缀，哪怕它跟父级同名（会出现「学校列表 / 学校列表」）。
           判据必须是 isChild，不能用「两级名字相不相同」—— 后者会把同名的二级项漏掉 -->
      <template v-if="isChild">
        {{ parentTitle }}<span class="sep">/</span><b>{{ title }}</b>
      </template>
      <b v-else>{{ title }}</b>
    </div>

    <div class="spacer"></div>

    <span class="clock">{{ nowText }}</span>
    <div class="who">
      <span class="avatar">{{ initial }}</span>{{ userName }}
    </div>
    <button class="btn-out" @click="emit('logout')">退出登录</button>
  </header>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  /** 当前页所属的一级菜单名；一级单页时与 title 相同 */
  parentTitle: { type: String, default: '' },
  title: { type: String, default: '' },
  /** 当前页是不是二级项 —— 决定面包屑要不要带父级前缀。见 nav.js 的 findLeaf */
  isChild: { type: Boolean, default: false },
  nowText: { type: String, default: '' },
  userName: { type: String, default: '' }
})

const emit = defineEmits(['toggle', 'logout'])

const initial = computed(() => (props.userName || '?').trim().charAt(0) || '?')
</script>

<style scoped>
/* 顶栏与内容区同为白面，只靠一条分隔线与内容区分开；
   深色只留给侧栏，避免整屏上下都是深色把中间的数据区压得发闷。
   高度与左侧品牌区一致（64px），两条分隔线在同一水平线上贯通整宽 */
.topbar {
  flex: none;
  height: 64px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 18px;
  background: #fff;
  border-bottom: 1px solid var(--line);
}

.icon-btn {
  width: 34px;
  height: 34px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  color: #5a6784;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease, border-color 0.15s ease;
}
.icon-btn:hover { background: #f0f5ff; color: #2563eb; border-color: #cddffb; }

.crumb { font-size: 13.5px; color: #8a95b0; white-space: nowrap; }
.crumb b { color: #17233d; font-weight: 600; }
.crumb .sep { margin: 0 7px; color: #c3ccdd; }

.spacer { flex: 1; }

.clock {
  font-size: 13px;
  color: #6b7796;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.who {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px 4px 4px;
  border-radius: 999px;
  background: #f4f7fc;
  font-size: 13px;
  color: #3d4864;
  white-space: nowrap;
}
.who .avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--brand-grad);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}

.btn-out {
  padding: 8px 15px;
  border-radius: 8px;
  border: 1px solid #f3c9c9;
  background: #fff5f5;
  color: #d24a4a;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
}
.btn-out:hover { background: #ffecec; }

@media (max-width: 900px) {
  .clock { display: none; }
}
</style>
