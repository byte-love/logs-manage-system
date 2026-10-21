<template>
  <aside class="side" :class="{ collapsed }">
    <!-- 品牌区：高度与右侧顶栏一致（都是 64px），两条分隔线在同一水平线上贯通整宽 -->
    <div class="brand">
      <img class="brand-icon" :src="brandIcon" alt="DataHub">
      <div class="brand-text">
        <span class="brand-name">日志管理系统</span>
        <span class="brand-sub">DataHub · 示例科技</span>
      </div>
    </div>

    <nav class="nav">
      <div
        v-for="item in NAV"
        :key="item.key"
        class="nav-group"
        :class="{
          open: item.children && openTops[item.key],
          'has-active': item.children && topKeyOf(active) === item.key
        }"
      >
        <div
          class="nav-item"
          :class="{ active: active === item.key }"
          @click="onTopClick(item)"
        >
          <span class="ico">{{ item.icon }}</span>
          <span class="txt">{{ item.name }}</span>
          <span v-if="item.children" class="chev">▶</span>
        </div>

        <div v-if="item.children" class="sub">
          <div
            v-for="child in item.children"
            :key="child.key"
            class="sub-item"
            :class="{ active: active === child.key }"
            @click="select(child.key)"
          >{{ child.name }}</div>
        </div>
      </div>
    </nav>

    <div class="side-foot">v1.0.0 · 上海示例科技</div>
  </aside>
</template>

<script setup>
import { reactive, watch } from 'vue'
import brandIcon from '@/assets/brand-icon.ico'
import { NAV, topKeyOf } from '@/constants/nav'

const props = defineProps({
  /** 当前选中的叶子 key */
  active: { type: String, default: '' },
  collapsed: { type: Boolean, default: false }
})

const emit = defineEmits(['update:active', 'update:collapsed'])

// 展开的一级项。各开各的，不互斥 —— 点哪个开哪个，可以同时全开
const openTops = reactive({})
openTops[topKeyOf(props.active)] = true

// 选中的叶子换了（比如从别处跳进来），保证它所在的那组是展开的。
// 只负责把它打开，不去关别的组 —— 用户手动展开的组不该被顶掉
watch(() => props.active, (key) => {
  const top = topKeyOf(key)
  if (top) openTops[top] = true
})

function select(key) {
  emit('update:active', key)
}

function onTopClick(item) {
  // 单页项：直接切过去，不展开
  if (!item.children) {
    select(item.key)
    return
  }
  // 收起状态下点分组：先把侧栏展开，再展开这一组
  if (props.collapsed) {
    emit('update:collapsed', false)
    openTops[item.key] = true
    return
  }
  openTops[item.key] = !openTops[item.key]
}
</script>

<style scoped>
/* 通了整高：上下都顶到框架边缘。
   深色只给侧栏，顶栏和内容区保持白面 —— 整屏上下一圈都压深色会把中间的数据区闷住 */
.side {
  /* 深色侧栏配色。取本系统自己的品牌色系（品牌蓝 #3f8cff / 青 #5ad7ff，
     与原 hero 的深藏青同族），不用绿色 —— 绿色在这套系统里已经是
     「正常 / 成功」的语义色（账号状态、申请通道、同步完成都是绿的），
     选中态再用绿会打架。只有侧栏用，所以留在组件内，不往 theme.css 放。

     文字亮度：一级 14.11、二级 10.39 —— 都远高于 AA 的 4.5。
     层级靠字号（15.5 / 14）、字重（500 / 400）和缩进表达，不靠调暗颜色：
     深底上把二级压到 5 左右会显灰、看不清，那是上一版踩过的坑 */
  --side-bg: #131f36;
  --side-ink: #e8eef8;
  --side-ink-2: #c3cee4;
  --side-hover: rgba(255, 255, 255, 0.07);
  --side-active: rgba(63, 140, 255, 0.2);
  --side-accent: #5ad7ff;
  --side-accent-ink: #8fbcff;
  --side-dim: #96a4c1;
  --side-line: rgba(255, 255, 255, 0.08);

  width: 236px;
  flex: none;
  display: flex;
  flex-direction: column;
  background: var(--side-bg);
  border-right: 1px solid rgba(0, 0, 0, 0.18);
  transition: width 0.22s ease;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 64px;
  flex: none;
  padding: 0 16px;
  border-bottom: 1px solid var(--side-line);
}
.brand-icon {
  width: 34px;
  height: 34px;
  flex: none;
  object-fit: contain;
}
.brand-text { min-width: 0; }
.brand-name {
  display: block;
  font-size: 14.5px;
  font-weight: 700;
  color: #fff;
  letter-spacing: 0.3px;
  white-space: nowrap;
}
.brand-sub {
  display: block;
  margin-top: 1px;
  font-size: 11px;
  color: var(--side-dim);
  white-space: nowrap;
}

.nav {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 10px 10px 16px;
}
.nav::-webkit-scrollbar { width: 5px; }
.nav::-webkit-scrollbar-thumb { background: rgba(255, 255, 255, 0.16); border-radius: 3px; }

/* 组与组之间留白。二级展开时组内紧凑、组间分开，一列读下来才有节奏 */
.nav-group { margin-bottom: 8px; }

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  margin: 2px 0;
  border-radius: 10px;
  color: var(--side-ink);
  font-size: 15.5px;
  font-weight: 500;
  cursor: pointer;
  user-select: none;
  transition: background 0.15s ease, color 0.15s ease;
}
.nav-item:hover { background: var(--side-hover); color: #fff; }
.nav-item .ico {
  width: 26px;
  height: 26px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.06);
  font-size: 14px;
  line-height: 1;
  transition: background 0.15s ease;
}
.nav-item:hover .ico { background: rgba(255, 255, 255, 0.13); }
.nav-group.has-active > .nav-item .ico { background: rgba(63, 140, 255, 0.26); }
.nav-item.active .ico { background: var(--side-active); }
.nav-item .txt {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.nav-item .chev {
  flex: none;
  font-size: 10px;
  opacity: 0.5;
  transition: transform 0.2s ease;
}
.nav-group.open > .nav-item .chev { transform: rotate(90deg); }
.nav-group.has-active > .nav-item { color: var(--side-accent-ink); }

/* 二级 */
.sub { display: none; position: relative; padding: 2px 0 4px; }
.nav-group.open > .sub { display: block; }
/* 引导竖线：从父项图标方块的正中垂下来，二级项挂在线上。
   横向位置 = .nav 左内边距 10 + .nav-item 左内边距 10 + 图标半宽 13 */
.sub::before {
  content: '';
  position: absolute;
  left: 23px;
  top: -2px;
  bottom: 6px;
  width: 1px;
  background: rgba(255, 255, 255, 0.09);
}

.sub-item {
  position: relative;
  display: block;
  /* 左内边距让二级文字与一级文字起点对齐：
     一级文字在 56px（.nav 10 + 项 10 + 图标 26 + 间距 10），
     而本元素的左边缘已经在 .nav 的 10px 内边距里，所以这里只要 46 */
  padding: 7px 12px 7px 46px;
  margin: 1px 0;
  border-radius: 10px;
  color: var(--side-ink-2);
  font-size: 14px;
  cursor: pointer;
  user-select: none;
  transition: background 0.15s ease, color 0.15s ease;
}
.sub-item:hover { background: var(--side-hover); color: #fff; }

/* 选中态：淡蓝底 + 白字 + 左侧青色竖条。
   竖条扛颜色，文字走白 —— 底本身已经是蓝的，文字再用蓝就压不住了。
   不加粗：选中已经有底、字色、竖条三重信号，再加粗就是第四重；
   而且粗体字更宽，切换选中时标签会横向位移（二级从 400 跳到 600 尤其明显） */
.nav-item.active,
.sub-item.active {
  background: var(--side-active);
  color: #fff;
}
.nav-item.active::before,
.sub-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  width: 3px;
  height: 16px;
  transform: translateY(-50%);
  border-radius: 0 3px 3px 0;
  background: var(--side-accent);
}

.side-foot {
  flex: none;
  padding: 12px 16px;
  border-top: 1px solid var(--side-line);
  font-size: 11.5px;
  color: var(--side-dim);
  white-space: nowrap;
  overflow: hidden;
}

/* 收起态：只剩图标。68px 放不下 7:1 的横版 logo，
   品牌区退化成「只留图标」，其余文字全部隐藏 */
.side.collapsed { width: 68px; }
.side.collapsed .brand { padding: 0; justify-content: center; }
.side.collapsed .brand-text,
.side.collapsed .nav-item .txt,
.side.collapsed .nav-item .chev,
.side.collapsed .sub,
.side.collapsed .side-foot { display: none; }
.side.collapsed .nav-item { justify-content: center; padding: 9px 0; }
.side.collapsed .nav-item .ico { background: transparent; }
</style>
