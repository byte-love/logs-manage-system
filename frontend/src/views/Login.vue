<template>
  <div class="login-page">
    <div class="login-background">
      <div class="orb orb-one"></div>
      <div class="orb orb-two"></div>
      <div class="orb orb-three"></div>
      <div class="grid-overlay"></div>
    </div>

    <div class="login-content">
      <section class="brand-panel">
        <a
          class="brand-logo-link"
          :href="BRAND_URL"
          target="_blank"
          rel="noopener noreferrer"
          :aria-label="`${BRAND_NAME}（在新标签页打开）`"
        >
          <BrandMark class="brand-logo" variant="on-dark" decorative />
        </a>
        <h1>日志管理系统</h1>
        <p>
          集中管理账号数据、统一查看业务记录、快速检索分页展示。
          这一版首页会直接承载数据库中的实际数据。
        </p>

        <div class="feature-list">
          <div class="feature-item">
            <span class="feature-dot"></span>
            <span>动态首页视觉</span>
          </div>
          <div class="feature-item">
            <span class="feature-dot"></span>
            <span>MongoDB 实时读取</span>
          </div>
          <div class="feature-item">
            <span class="feature-dot"></span>
            <span>分页数据浏览</span>
          </div>
        </div>
      </section>

      <el-card class="login-card" shadow="never">
        <div class="card-header">
          <h2>欢迎登录</h2>
          <p>请输入你的账号密码进入系统</p>
        </div>

        <el-form @submit.prevent>
          <el-form-item>
            <el-input
              v-model="form.username"
              placeholder="请输入用户名"
              size="large"
              clearable
              @keyup.enter="handleLogin"
            />
          </el-form-item>

          <el-form-item>
            <el-input
              v-model="form.password"
              placeholder="请输入密码"
              size="large"
              show-password
              @keyup.enter="handleLogin"
            />
          </el-form-item>

          <el-form-item>
            <el-button
              type="primary"
              size="large"
              :loading="loading"
              class="login-button"
              @click="handleLogin"
            >
              登录系统
            </el-button>
          </el-form-item>
        </el-form>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '../api/auth'
import { saveLoginUser } from '../utils/auth'
import BrandMark from '../components/BrandMark.vue'
import { BRAND_URL, BRAND_NAME } from '../constants/brand'

const router = useRouter()
const loading = ref(false)
const form = reactive({
  username: '',
  password: ''
})

async function handleLogin() {
  if (!form.username.trim()) {
    ElMessage.error('请输入用户名')
    return
  }

  if (!form.password.trim()) {
    ElMessage.error('请输入密码')
    return
  }

  loading.value = true
  try {
    const res = await login({
      username: form.username.trim(),
      password: form.password
    })

    saveLoginUser(res.data.user)
    ElMessage.success(res.data.message || '登录成功')
    router.replace('/home')
  } catch (error) {
    ElMessage.error(error.response?.data?.message || error.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  background:
    radial-gradient(circle at top left, rgba(0, 191, 255, 0.28), transparent 30%),
    radial-gradient(circle at bottom right, rgba(122, 92, 255, 0.32), transparent 32%),
    linear-gradient(135deg, #081122 0%, #111d3a 48%, #08172d 100%);
}

.login-background {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.grid-overlay {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.05) 1px, transparent 1px);
  background-size: 42px 42px;
  mask-image: linear-gradient(to bottom, rgba(0, 0, 0, 0.9), transparent);
}

.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(24px);
  opacity: 0.85;
  animation: floatOrb 12s ease-in-out infinite;
}

.orb-one {
  width: 240px;
  height: 240px;
  left: 8%;
  top: 12%;
  background: rgba(73, 140, 255, 0.42);
}

.orb-two {
  width: 320px;
  height: 320px;
  right: 10%;
  top: 16%;
  background: rgba(0, 222, 255, 0.24);
  animation-delay: -4s;
}

.orb-three {
  width: 260px;
  height: 260px;
  right: 18%;
  bottom: 8%;
  background: rgba(130, 88, 255, 0.34);
  animation-delay: -7s;
}

.login-content {
  position: relative;
  z-index: 1;
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.1fr 420px;
  gap: 40px;
  align-items: center;
  max-width: 1240px;
  margin: 0 auto;
  padding: 40px 32px;
}

.brand-panel {
  color: #fff;
  padding-right: 24px;
}

/* 原来这里是个胶囊 badge（半透明白底 + BYTELOVE · LOG CENTER），
   现在换成公司 logo。不保留胶囊底：深蓝底上再压一层白底就是块贴纸，
   logo 本身透明底 + 刷成亮色渐变，直接落在背景上更自然 */
.brand-logo-link {
  display: inline-block;
  line-height: 0;
  margin-bottom: 22px;
  border-radius: 8px;
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.brand-logo-link:hover {
  transform: translateY(-1px);
}

.brand-logo-link:hover .brand-logo {
  opacity: 1;
}

.brand-logo-link:focus-visible {
  outline: 2px solid rgba(140, 232, 255, 0.9);
  outline-offset: 6px;
}

.brand-logo {
  width: 320px;
  max-width: 100%;
}

.brand-panel h1 {
  font-size: 56px;
  line-height: 1.06;
  margin-bottom: 18px;
}

.brand-panel p {
  max-width: 620px;
  color: rgba(232, 242, 255, 0.82);
  font-size: 18px;
  line-height: 1.9;
  margin-bottom: 28px;
}

.feature-list {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
}

.feature-item {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 12px 18px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.12);
  box-shadow: 0 16px 40px rgba(4, 17, 38, 0.28);
}

.feature-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: linear-gradient(135deg, #5ad7ff, #7a5cff);
  box-shadow: 0 0 12px rgba(90, 215, 255, 0.8);
}

.login-card {
  border-radius: 28px;
  border: 1px solid rgba(255, 255, 255, 0.24);
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(20px);
  box-shadow: 0 30px 80px rgba(7, 16, 34, 0.38);
}

.card-header {
  text-align: center;
  margin-bottom: 10px;
}

.card-header h2 {
  font-size: 30px;
  color: #18213a;
  margin-bottom: 8px;
}

.card-header p {
  color: #6d7a95;
  font-size: 14px;
}

:deep(.el-input__wrapper) {
  border-radius: 14px;
  padding: 6px 14px;
  box-shadow: 0 0 0 1px rgba(87, 104, 133, 0.12) inset;
}

:deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px rgba(74, 106, 255, 0.8) inset, 0 0 0 4px rgba(95, 138, 255, 0.12);
}

.login-button {
  width: 100%;
  height: 48px;
  border: none;
  border-radius: 14px;
  background: linear-gradient(135deg, #3f8cff 0%, #6c63ff 100%);
  box-shadow: 0 16px 28px rgba(63, 140, 255, 0.34);
}

.login-button:hover {
  transform: translateY(-1px);
  box-shadow: 0 20px 34px rgba(63, 140, 255, 0.42);
}

@keyframes floatOrb {
  0%, 100% {
    transform: translate3d(0, 0, 0) scale(1);
  }
  50% {
    transform: translate3d(18px, -24px, 0) scale(1.06);
  }
}

@media (max-width: 980px) {
  .login-content {
    grid-template-columns: 1fr;
    padding: 28px 20px;
  }

  .brand-panel {
    padding-right: 0;
  }

  .brand-panel h1 {
    font-size: 40px;
  }

  .brand-logo {
    width: 240px;
  }
}
</style>
