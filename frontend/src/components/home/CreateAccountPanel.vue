<template>
  <!-- 创建正式账号：内嵌页面形态（原为 el-dialog 弹窗）。
       表单 46 个字段、外加一张数据库权限表，内容量远超一个弹窗该有的样子；
       改成页面后顶部留「返回 + 标题」（和另两个工具一致），
       取消/确认放在底部吸底条 —— 不管滚到哪都能提交，不用滚回顶部 -->
  <section class="account-panel">
    <div class="detail-head">
      <div class="detail-title-row">
        <el-button class="back-button" @click="emit('close')"><span class="arw">‹</span>返回</el-button>
        <div class="detail-title">
          <h2>创建正式账号</h2>
          <span>录入账号资料、配置数据库权限并同步正式账号库</span>
        </div>
      </div>
    </div>

    <div class="account-body">
      <div class="section-heading">
        <span class="section-index">01</span>
        <div>
          <h3>基本信息</h3>
          <p>带 * 的字段为正式账号必填项</p>
        </div>
      </div>

      <!-- label-width 要放得下「带必填星号的最长标签」：
           「市场部发起人」6 个字 84px，加星号 12px、右侧留白 12px，共约 108px ——
           原来的 104px 就差这几像素，会把它折成两行。
           其它标签都短于它，加宽后仍统一左对齐 -->
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="120px"
        status-icon
        class="account-form"
      >
        <el-row :gutter="22">
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="账户名" prop="userName">
              <el-input v-model="form.userName" maxlength="100" clearable placeholder="请输入唯一账户名" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="密码" prop="password">
              <el-input v-model="form.password" type="password" maxlength="100" show-password clearable placeholder="请输入密码" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="姓名" prop="nickName">
              <el-input v-model="form.nickName" maxlength="100" clearable placeholder="请输入姓名" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="手机号" prop="phone">
              <el-input v-model="form.phone" maxlength="11" clearable placeholder="11 位手机号" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="邮箱" prop="mail">
              <el-input v-model="form.mail" maxlength="100" clearable placeholder="name@example.com" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="学校/机构" prop="institution">
              <el-input
                v-model="form.institution"
                maxlength="100"
                clearable
                :disabled="institutionLocked"
                placeholder="请输入学校或机构"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="学院">
              <el-input v-model="form.college" maxlength="500" clearable placeholder="选填" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="系所">
              <el-input v-model="form.collegeDept" maxlength="500" clearable placeholder="选填" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="职位">
              <el-select v-model="form.position" filterable allow-create default-first-option style="width: 100%">
                <el-option v-for="item in createOptions.positions" :key="item" :label="item" :value="item" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="账户类型">
              <el-select v-model="form.userType" style="width: 100%">
                <el-option
                  v-for="item in createOptions.userTypes"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="接收邮箱">
              <el-input v-model="form.receiveMail" maxlength="500" clearable placeholder="选填" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="学院(复核)">
              <el-select
                v-model="form.dataCode"
                filterable
                clearable
                placeholder="请选择复核学院"
                style="width: 100%"
              >
                <el-option v-for="item in createOptions.colleges" :key="item" :label="item" :value="item" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="新增复核学院">
              <div class="inline-control">
                <el-input
                  v-model="newCollegeName"
                  maxlength="255"
                  clearable
                  placeholder="输入学院名称"
                  class="grow-control"
                />
                <el-button :loading="collegeAdding" @click="createCollegeOption">新增</el-button>
              </div>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="联系人">
              <el-input v-model="form.contactors" maxlength="500" clearable placeholder="选填" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="联系人电话">
              <el-input v-model="form.contactPhone" maxlength="500" clearable placeholder="选填" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="联系人邮箱">
              <el-input v-model="form.contactMail" maxlength="500" clearable placeholder="选填" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="联系人职位">
              <el-input v-model="form.contactPosition" maxlength="500" clearable placeholder="选填" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="地址">
              <el-input v-model="form.addresses" maxlength="500" clearable placeholder="选填" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="账号过期时间">
              <el-date-picker
                v-model="form.expireDateTime"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="选填"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <!-- 紧挨「账号过期时间」：两个都是单行短字段，并排正好凑满一行。
               别把它挪到「IP 范围」后面 —— 那是整行 textarea，会把这两个拆到两行去 -->
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="市场部发起人" prop="marketInitiator">
              <el-input
                v-model="form.marketInitiator"
                maxlength="100"
                clearable
                placeholder="请输入市场部发起人"
              />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="IP 范围">
              <el-input
                v-model="form.ipRange"
                type="textarea"
                :rows="2"
                placeholder="选填；多个范围请按旧系统格式填写"
              />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="其他说明" prop="otherDescription">
              <el-input
                v-model="form.otherDescription"
                type="textarea"
                :rows="3"
                maxlength="1000"
                show-word-limit
                placeholder="请输入本次创建账号的其他说明"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8" :xl="6">
            <el-form-item label="通知设置">
              <el-checkbox v-model="form.sendEmail">发送邮件</el-checkbox>
              <el-checkbox v-model="form.sendMessage">发送短信</el-checkbox>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <div class="section-heading permissions-heading">
        <span class="section-index">02</span>
        <div>
          <h3>数据库权限</h3>
          <p>正式权限 {{ selectedPermissionCount }} 个（level 2），其余 {{ trialPermissionCount }} 个按高级试用（level 1.5）写入</p>
        </div>
      </div>

      <div class="permission-toolbar">
        <el-button size="small" @click="setAllPermissions(true)">选择所有库</el-button>
        <el-button size="small" @click="selectBasePermissions">选择基础库</el-button>
        <el-button size="small" @click="reversePermissions">反选</el-button>
        <el-date-picker
          v-model="uniformExpiry"
          type="datetime"
          value-format="YYYY-MM-DD HH:mm:ss"
          placeholder="统一过期时间"
          size="small"
        />
        <el-button class="high-contrast-action" size="small" type="primary" @click="applyUniformExpiry">
          统一设置过期时间
        </el-button>
        <el-input
          v-model="systemKeyword"
          size="small"
          clearable
          placeholder="搜索数据库"
          class="system-search"
          :class="{ 'is-filtering': !!systemKeyword }"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
      </div>

      <div class="school-permission-toolbar">
        <span class="school-permission-label">学校权限模板</span>
        <el-button
          class="high-contrast-action"
          type="primary"
          :loading="schoolTemplateLoading"
          @click="applySchoolTemplate"
        >
          按学校设置权限
        </el-button>
        <el-select
          v-model="selectedSchool"
          filterable
          clearable
          placeholder="选择机构管理中的学校"
          class="school-permission-select"
        >
          <el-option v-for="item in createOptions.schools" :key="item" :label="item" :value="item" />
        </el-select>
        <el-button v-if="institutionLocked" @click="unlockInstitution">解除模板</el-button>
        <span class="school-permission-tip">应用后将按学校已购库及各库到期时间覆盖下方权限</span>
      </div>

      <!-- 权限表的三态：加载中给骨架、失败给重试出口、其余给表格。
           骨架只占表格这一块，上面的表单照常能填 —— 46 个输入框本来就不等接口，
           没道理陪着一块转圈。
           表格不设固定高度：弹窗里空间有限才锁 300px，整页之后让它跟着内容走。
           空表格分两种：搜索没命中 vs 配置真的没加载出来，文案要分开，
           否则搜了个不存在的库名会显示成「暂无可用数据库配置」，像是配置没拉到 -->
      <div v-if="optionsLoading" class="perm-skeleton" aria-hidden="true">
        <div class="sk-row sk-head">
          <span v-for="n in SKELETON_COLUMNS" :key="n" class="sk"></span>
        </div>
        <div v-for="row in SKELETON_ROWS" :key="row" class="sk-row">
          <span v-for="n in SKELETON_COLUMNS" :key="n" class="sk"></span>
        </div>
      </div>

      <div v-else-if="optionsError" class="perm-error">
        <span>账号配置加载失败</span>
        <el-button size="small" @click="loadOptions">重试</el-button>
      </div>

      <el-table
        v-else
        :data="filteredSystems"
        border
        stripe
        :empty-text="systemKeyword.trim() ? '没有匹配的数据库' : '暂无可用数据库配置'"
      >
        <el-table-column label="开通" width="70" align="center">
          <template #default="scope">
            <el-checkbox v-model="permissionState[scope.row.code].selected" />
          </template>
        </el-table-column>
        <el-table-column prop="code" label="库代码" min-width="120" show-overflow-tooltip />
        <el-table-column prop="name" label="数据库名称" min-width="220" show-overflow-tooltip />
        <el-table-column prop="type" label="类型" width="100" />
        <el-table-column prop="partition" label="分类" min-width="170" show-overflow-tooltip />
        <el-table-column label="过期时间" width="205">
          <template #default="scope">
            <el-date-picker
              v-model="permissionState[scope.row.code].expiryDate"
              type="datetime"
              value-format="YYYY-MM-DD HH:mm:ss"
              size="small"
              style="width: 178px"
            />
          </template>
        </el-table-column>
      </el-table>

      <el-alert
        v-if="!selectedPermissionCount"
        title="当前未选择正式权限；所有已发布数据库仍会按高级试用 level 1.5 写入。"
        type="warning"
        :closable="false"
        show-icon
        class="permission-alert"
      />
    </div>

    <!-- 吸底操作条：贴住内容区（.page）滚动视口的下沿，滚到哪都能提交。
         横向负 margin 抵消 .view 的左右内边距、让条子铺满内容区；
         纵向刻意不吃负 margin —— 吃了会让条子在滚动过程中超出滚动视口被裁掉一截 -->
    <div class="account-foot">
      <el-button :disabled="submitting" @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="optionsLoading" @click="submitAccount">
        确认添加
      </el-button>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import {
  addCollege,
  createAccount,
  getCreateAccountOptions,
  getSchoolPermissionTemplate
} from '../../api/userData'

const emit = defineEmits(['close'])

const formRef = ref(null)
// 初值就是 true：组件一挂载就发请求，先按「加载中」渲染，
// 免得第一帧先闪一下空表格再切进骨架
const optionsLoading = ref(true)
// 加载失败的信号。存的是给界面看的一句话，后端原文只进 console
const optionsError = ref('')
// 骨架的行列数：列跟真实表格对齐（开通/库代码/名称/类型/分类/过期时间），
// 行数取个常见值 —— 和真实行数差太多的话，数据回来时表格会跳一下
const SKELETON_ROWS = 6
const SKELETON_COLUMNS = 6
const submitting = ref(false)
const schoolTemplateLoading = ref(false)
const collegeAdding = ref(false)
const systemKeyword = ref('')
const uniformExpiry = ref(defaultExpiry())
const selectedSchool = ref('')
const institutionLocked = ref(false)
const newCollegeName = ref('')
const permissionState = reactive({})
const createOptions = reactive({
  systems: [],
  schools: [],
  colleges: [],
  positions: ['教师', '博士', '硕士', '本科', '其他'],
  userTypes: [
    { value: 0, label: '图书馆院系' },
    { value: 1, label: '个人账号' },
    { value: 2, label: '机构账号' },
    { value: 3, label: '班级账号' }
  ]
})

const form = reactive(emptyForm())

const rules = {
  userName: [
    { required: true, message: '请输入账户名', trigger: 'blur' },
    { max: 100, message: '账户名不能超过 100 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { max: 100, message: '密码不能超过 100 位', trigger: 'blur' }
  ],
  nickName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^\d{11}$/, message: '手机号必须为 11 位数字', trigger: 'blur' }
  ],
  mail: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: ['blur', 'change'] }
  ],
  institution: [{ required: true, message: '请输入学校或机构', trigger: 'blur' }],
  marketInitiator: [
    { required: true, message: '请输入市场部发起人', trigger: 'blur' },
    { max: 100, message: '市场部发起人不能超过 100 个字符', trigger: 'blur' }
  ],
  otherDescription: [
    { required: true, message: '请输入其他说明', trigger: 'blur' },
    { max: 1000, message: '其他说明不能超过 1000 个字符', trigger: 'blur' }
  ]
}

const filteredSystems = computed(() => {
  const keyword = systemKeyword.value.trim().toLowerCase()
  if (!keyword) return createOptions.systems
  return createOptions.systems.filter((item) =>
    [item.code, item.name, item.type, item.partition]
      .some((value) => String(value || '').toLowerCase().includes(keyword))
  )
})

const selectedPermissionCount = computed(() =>
  Object.values(permissionState).filter((item) => item.selected).length
)
const trialPermissionCount = computed(() =>
  Math.max(0, createOptions.systems.length - selectedPermissionCount.value)
)

// 页面挂载即拉配置。原来是弹窗的 @opened 才加载，这里进页面就等于「打开」
onMounted(loadOptions)

async function loadOptions() {
  optionsLoading.value = true
  optionsError.value = ''
  try {
    const response = await getCreateAccountOptions()
    const data = response.data || {}
    createOptions.systems = Array.isArray(data.systems) ? data.systems : []
    createOptions.schools = Array.isArray(data.schools) ? data.schools : []
    createOptions.colleges = Array.isArray(data.colleges) ? data.colleges : []
    if (!form.dataCode && createOptions.colleges.length) {
      form.dataCode = createOptions.colleges[0]
    }
    createOptions.positions = Array.isArray(data.positions) && data.positions.length
      ? data.positions
      : createOptions.positions
    createOptions.userTypes = Array.isArray(data.userTypes) && data.userTypes.length
      ? data.userTypes
      : createOptions.userTypes
    initializePermissions()
  } catch (error) {
    // 失败不再弹 toast：弹完就没了，只剩一张空表格，用户不知道还能不能提交。
    // 改成让权限区就地显示「加载失败」+ 重试，出口留在原地
    optionsError.value = '账号配置加载失败'
    console.error('[创建正式账号] 账号配置加载失败', error)
  } finally {
    optionsLoading.value = false
  }
}

function initializePermissions() {
  Object.keys(permissionState).forEach((key) => delete permissionState[key])
  const expiry = defaultExpiry()
  createOptions.systems.forEach((item) => {
    permissionState[item.code] = { selected: false, expiryDate: expiry }
  })
}

function setAllPermissions(selected) {
  Object.values(permissionState).forEach((item) => { item.selected = selected })
}

function selectBasePermissions() {
  createOptions.systems.forEach((system) => {
    if (system.type === '普通') permissionState[system.code].selected = true
  })
}

function reversePermissions() {
  Object.values(permissionState).forEach((item) => { item.selected = !item.selected })
}

function applyUniformExpiry() {
  if (!uniformExpiry.value) {
    ElMessage.warning('请先选择统一过期时间')
    return
  }
  Object.values(permissionState).forEach((item) => {
    item.expiryDate = uniformExpiry.value
  })
}

async function applySchoolTemplate() {
  if (!selectedSchool.value) {
    ElMessage.warning('请先选择学校')
    return
  }
  try {
    await ElMessageBox.confirm(
      '确定按该学校在机构管理中的配置覆盖当前勾选状态吗？请先确认其中不应开通的新库已经剔除。',
      '按学校设置权限',
      { type: 'warning', confirmButtonText: '确认应用', cancelButtonText: '取消' }
    )
  } catch (_) {
    return
  }

  schoolTemplateLoading.value = true
  try {
    const response = await getSchoolPermissionTemplate(selectedSchool.value)
    const data = response.data || {}
    const template = new Map(
      (Array.isArray(data.permissions) ? data.permissions : [])
        .map((item) => [item.code, item])
    )
    createOptions.systems.forEach((system) => {
      const state = permissionState[system.code]
      const configured = template.get(system.code)
      state.selected = !!configured
      if (configured?.expiryDate) state.expiryDate = configured.expiryDate
    })
    form.institution = data.school || selectedSchool.value
    institutionLocked.value = true
    formRef.value?.validateField('institution').catch(() => {})
    ElMessage.success(`已应用 ${template.size} 个学校数据库权限`)
    if (Number(data.ignoredCodes) > 0) {
      ElMessage.warning(`学校模板中有 ${data.ignoredCodes} 个非当前发布库，已按旧工具逻辑忽略`)
    }
  } catch (error) {
    ElMessage.error(error.response?.data?.message || error.message || '学校权限加载失败')
  } finally {
    schoolTemplateLoading.value = false
  }
}

function unlockInstitution() {
  institutionLocked.value = false
  selectedSchool.value = ''
}

async function createCollegeOption() {
  const name = newCollegeName.value.trim()
  if (!name) {
    ElMessage.warning('请输入新增学院名称')
    return
  }
  if (createOptions.colleges.includes(name)) {
    ElMessage.warning('该学院名称已存在，无需添加')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定新增学院复核项“${name}”吗？`,
      '新增学院',
      { type: 'warning', confirmButtonText: '确认新增', cancelButtonText: '取消' }
    )
  } catch (_) {
    return
  }

  collegeAdding.value = true
  try {
    const response = await addCollege(name)
    const data = response.data || {}
    createOptions.colleges = Array.isArray(data.colleges)
      ? data.colleges
      : [...createOptions.colleges, name]
    form.dataCode = data.college || name
    newCollegeName.value = ''
    ElMessage.success(data.message || '学院添加成功')
  } catch (error) {
    ElMessage.error(error.response?.data?.message || error.message || '学院添加失败')
  } finally {
    collegeAdding.value = false
  }
}

async function submitAccount() {
  if (!formRef.value) return
  // 配置没到手时 systems 是空的，提交上去会建出一个「零权限」的正式账号 —— 拦住。
  // 放在表单校验之前：先告诉用户去重试，别让他把整张表填完才被拦
  if (optionsError.value) {
    ElMessage.warning('数据库配置尚未加载成功，请先点权限区的「重试」')
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  const permissions = createOptions.systems.map((system) => ({
    code: system.code,
    selected: !!permissionState[system.code]?.selected,
    expiryDate: permissionState[system.code]?.expiryDate
  }))

  if (permissions.some((item) => !item.expiryDate)) {
    ElMessage.warning('请为所有数据库设置过期时间（未勾选项也会按 level 1.5 写入）')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确定添加账号“${form.userName.trim()}”吗？创建后将写入正式账号库并绑定手机号。`,
      '确认添加账号',
      { type: 'warning', confirmButtonText: '确认添加', cancelButtonText: '取消' }
    )
  } catch (_) {
    return
  }

  submitting.value = true
  try {
    const response = await createAccount({ ...form, permissions })
    const data = response.data || {}
    ElMessage.success(data.message || '账号添加成功')
    if (Array.isArray(data.warnings)) {
      data.warnings.forEach((warning) => ElMessage.warning(warning))
    }
    emit('created', data)
    // 创建完就退出本页回工具首页。组件随之销毁，表单状态自然清空，
    // 不需要再手动 reset —— 下次进来是一张干净的表单
    emit('close')
  } catch (error) {
    ElMessage.error(error.response?.data?.message || error.message || '账号添加失败')
  } finally {
    submitting.value = false
  }
}

function emptyForm() {
  return {
    userName: '', password: '', nickName: '', phone: '', mail: '', institution: '',
    college: '', collegeDept: '', position: '教师', userType: 1, dataCode: '',
    receiveMail: '', contactors: '', contactPhone: '', contactMail: '',
    contactPosition: '', addresses: '', expireDateTime: '', ipRange: '',
    xuezhi: '', ruxueYear: '', endDate: '', xueEnddate: '',
    marketInitiator: '', otherDescription: '',
    sendEmail: false, sendMessage: false
  }
}

function defaultExpiry() {
  const value = new Date()
  value.setFullYear(value.getFullYear() + 1)
  const pad = (number) => String(number).padStart(2, '0')
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())} ${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}`
}
</script>

<style scoped>
.account-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* 头部与页面里另两个工具（用户日志导出 / 学校日志导出）同一套视觉。
   那两份样式在 UtilityToolsPanel.vue 的 scoped 里，跨组件拿不到，所以这里复刻一份 ——
   改头部观感时两处要一起改 */
.detail-head {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  padding: 16px 18px;
  border: 1px solid rgba(99, 122, 165, 0.16);
  border-radius: 16px;
  background: #fff;
}

.detail-title-row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.detail-title-row .back-button {
  background: #f2f5fa;
  border-color: #dde5f0;
}
.detail-title-row .back-button:hover,
.detail-title-row .back-button:focus {
  background: #e8eefb;
  border-color: #cddffb;
  color: #2563eb;
}
.detail-title-row .back-button .arw {
  margin-right: 4px;
  font-size: 15px;
  line-height: 1;
}

.detail-title {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.detail-title h2 {
  margin: 0;
  color: #2b3654;
  font-size: 20px;
}

.detail-title span {
  margin-top: 3px;
  color: #8a95b0;
  font-size: 13px;
}

/* 权限表骨架。整块延迟 200ms 才淡入 —— 接口快的话用户根本看不到它，
   进来直接就是真实表格；只有真慢了，才有「正在加载」的形状可看。
   这 200ms 里表格区域是空白的，不会先闪一下「暂无可用数据库配置」 */
.perm-skeleton {
  border: 1px solid rgba(99, 122, 165, 0.14);
  border-radius: 8px;
  overflow: hidden;
  animation: skelIn 0.24s ease 0.2s both;
}
@keyframes skelIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.sk-row {
  display: grid;
  /* 六列对着真实表格的列定义给：70 / 120 / 220 / 100 / 170 / 205 */
  grid-template-columns: 70px 130px minmax(180px, 1fr) 100px 170px 205px;
  gap: 12px;
  align-items: center;
  padding: 11px 10px;
  border-bottom: 1px solid rgba(99, 122, 165, 0.1);
}
.sk-row:last-child { border-bottom: none; }
.sk-head { background: linear-gradient(180deg, #eef3ff, #e7edfb); }

.sk {
  height: 13px;
  width: 60%;
  border-radius: 7px;
  background: linear-gradient(90deg, #eaeff8 25%, #f6f9fd 37%, #eaeff8 63%);
  background-size: 400% 100%;
  animation: skelShimmer 1.5s ease infinite;
}
@keyframes skelShimmer {
  from { background-position: 100% 50%; }
  to { background-position: 0 50%; }
}

/* 加载失败的出口：一句话 + 一个按钮，就地摆在权限表的位置上 */
.perm-error {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 120px;
  border: 1px dashed rgba(99, 122, 165, 0.28);
  border-radius: 12px;
  color: #93a0b8;
  font-size: 13.5px;
}

.section-heading {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 18px;
}
.section-index {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 12px;
  color: #fff;
  font-weight: 800;
  background: linear-gradient(135deg, #3f7cff, #725cff);
  box-shadow: 0 10px 22px rgba(73, 92, 255, .25);
}
.section-heading h3 { margin: 0; color: #19233d; font-size: 18px; }
.section-heading p { margin: 4px 0 0; color: #8490a8; font-size: 13px; }
.account-form {
  padding: 20px 20px 4px;
  border: 1px solid #e9edf5;
  border-radius: 18px;
  background: linear-gradient(180deg, #fbfcff, #f7f9fd);
}
/* 标签字重与行距。原来这两条是 theme.css 里的 `.el-dialog .el-form-item`，
   搬到页面之后不在弹窗里、选择器不再命中，所以在这里按同一口径补一遍，
   否则标签会退回不加粗的默认样 */
.account-form :deep(.el-form-item__label) {
  font-weight: 600;
  color: #4a5573;
}
.account-form :deep(.el-form-item) {
  margin-bottom: 20px;
}
.inline-control { display: flex; align-items: center; gap: 8px; width: 100%; }
.grow-control { flex: 1; min-width: 0; }
:deep(.high-contrast-action.el-button) {
  --el-button-text-color: #fff;
  --el-button-hover-text-color: #fff;
  --el-button-active-text-color: #fff;
  --el-button-disabled-text-color: rgba(255, 255, 255, 0.82);
  color: #fff !important;
  border-color: transparent !important;
  background: linear-gradient(135deg, #2458d6, #5946d8) !important;
  box-shadow: 0 8px 18px rgba(52, 76, 199, 0.24);
}
:deep(.high-contrast-action.el-button:hover),
:deep(.high-contrast-action.el-button:focus) {
  color: #fff !important;
  border-color: transparent !important;
  background: linear-gradient(135deg, #1749c6, #4935ca) !important;
  box-shadow: 0 10px 22px rgba(52, 76, 199, 0.32);
}
:deep(.high-contrast-action.el-button:active) {
  color: #fff !important;
  background: linear-gradient(135deg, #123da9, #3e2caf) !important;
}
:deep(.high-contrast-action.el-button.is-disabled) {
  color: rgba(255, 255, 255, 0.82) !important;
  background: linear-gradient(135deg, #809ad9, #9990d7) !important;
  box-shadow: none;
}
.permissions-heading { margin-top: 24px; }
.permission-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.permission-toolbar :deep(.el-button + .el-button) { margin-left: 0; }
/* 搜索框。它是这条工具栏里唯一不"实"的控件 —— 同行都是带文字的按钮，
   只有它是淡灰描边（theme.css 给输入框的默认描边透明度只有 0.14），
   尺寸又跟按钮一样是 small，推在最右边很容易看漏。
   这里给它白底 + 品牌色描边 + 搜索图标，输入关键词后描边变实、加一圈外发光，
   表示下方表格正被它筛着 */
.system-search {
  --el-input-placeholder-color: #8894ac;
  width: 240px;
  margin-left: auto;
}
.system-search :deep(.el-input__wrapper) {
  background: #fff;
  box-shadow: 0 0 0 1px rgba(63, 140, 255, 0.38) inset !important;
}
.system-search :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px rgba(63, 140, 255, 0.62) inset !important;
}
.system-search.is-filtering :deep(.el-input__wrapper),
.system-search.is-filtering :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--brand-start) inset, var(--ring-focus) !important;
}
.system-search :deep(.el-input__prefix) {
  color: var(--brand-start);
}
.school-permission-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin: 0 0 14px;
  padding: 12px 14px;
  border: 1px solid #d9e2f5;
  border-radius: 12px;
  background: #f5f8ff;
}
.school-permission-label {
  color: #263b68;
  font-weight: 700;
  white-space: nowrap;
}
.school-permission-select { width: min(360px, 100%); }
.school-permission-tip {
  color: #71809e;
  font-size: 12px;
}
.permission-alert { margin-top: 12px; }

/* 吸底操作条。
   sticky 的参照是最近的滚动祖先 —— 这里是 Home.vue 的 .page（内容区唯一的滚动容器），
   不是本组件；所以它能一直贴在屏幕下沿，而不是只在本页范围内粘。
   横向负 margin 抵消 .view 的左右内边距，让条子通铺到内容区两边；
   底边也留 .view 的内边距，是页面本来的页脚留白，视觉上无缝 */
.account-foot {
  position: sticky;
  bottom: 0;
  z-index: 2;
  margin: 0 calc(-1 * var(--content-gutter));
  padding: 12px var(--content-gutter);
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  background: rgba(255, 255, 255, 0.96);
  border-top: 1px solid var(--line);
  backdrop-filter: blur(6px);
}

@media (max-width: 980px) {
  /* 六列骨架按窄屏收一收，不然会溢出被裁掉一截 */
  .sk-row {
    grid-template-columns: 60px 110px minmax(120px, 1fr) 80px 130px 150px;
    gap: 8px;
  }
}

@media (max-width: 760px) {
  .account-form { padding: 16px 10px 2px; }
  .inline-control { align-items: stretch; flex-direction: column; }
  .system-search { width: 100%; margin-left: 0; }
  .school-permission-toolbar { align-items: stretch; }
  .school-permission-label { width: 100%; }
  .school-permission-select { width: 100%; }
}
</style>
