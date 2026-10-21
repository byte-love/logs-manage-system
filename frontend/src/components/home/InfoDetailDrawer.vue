<template>
  <el-drawer
    :model-value="modelValue"
    title="Info 详情"
    size="640px"
    destroy-on-close
    @close="emit('update:modelValue', false)"
  >
    <div class="drawer-content">
      <div class="drawer-tip">以下为当前记录的完整 Info 内容：</div>
      <pre class="drawer-pre">{{ formattedValue }}</pre>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  value: {
    type: [String, Object, Array, Number, Boolean, null],
    default: ''
  }
})

const emit = defineEmits(['update:modelValue'])

const formattedValue = computed(() => {
  if (props.value === null || props.value === undefined || props.value === '') {
    return '-'
  }

  if (typeof props.value === 'object') {
    try {
      return JSON.stringify(props.value, null, 2)
    } catch (error) {
      return String(props.value)
    }
  }

  const raw = String(props.value)
  try {
    const parsed = JSON.parse(raw)
    return JSON.stringify(parsed, null, 2)
  } catch (error) {
    return raw
  }
})
</script>

<style scoped>
.drawer-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.drawer-tip {
  color: #5f6b84;
  font-size: 14px;
}

.drawer-pre {
  margin: 0;
  padding: 16px;
  border-radius: 16px;
  background: #0f1729;
  color: #dce7ff;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
