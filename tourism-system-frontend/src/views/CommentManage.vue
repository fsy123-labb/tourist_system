<template>
  <div class="comment-manage">
    <el-card class="page-card">
      <template #header>
        <div class="card-header">
          <div class="card-title">
            <div class="title-icon" style="background: linear-gradient(135deg, #f7971e, #ffd200);">
              <el-icon><ChatDotRound /></el-icon>
            </div>
            <div>
              <h3>评论审核</h3>
              <p>用户评论内容查看与违规评论删除</p>
            </div>
          </div>
          <div class="card-stat" style="background: #fffbe6;">
            <el-icon><DataAnalysis /></el-icon>
            <span>共 <strong style="color:#f7971e;">{{ page.total }}</strong> 条评论</span>
          </div>
        </div>
      </template>

      <div class="search-bar">
        <el-input v-model="filterForm.spotName" placeholder="景点名称" style="width: 160px" clearable :prefix-icon="Location" @keyup.enter="handleSearch" @clear="handleSearch" />
        <el-input v-model="filterForm.userId" placeholder="用户ID(数字)" style="width: 150px" clearable :prefix-icon="User" @keyup.enter="handleSearch" @clear="handleSearch" />
        <el-button type="primary" :icon="Search" @click="handleSearch">筛选</el-button>
        <el-button :icon="Refresh" @click="handleReset">重置</el-button>
      </div>

      <el-table :data="tableData" v-loading="loading" stripe class="modern-table">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="spotName" label="景点" min-width="140" show-overflow-tooltip />
        <el-table-column prop="userName" label="评论用户" width="130">
          <template #default="scope">
            <div class="user-cell">
              <el-avatar :size="24" class="mini-avatar" style="background: linear-gradient(135deg,#f7971e,#ffd200);">{{ scope.row.userName?.charAt(0).toUpperCase() }}</el-avatar>
              <span>{{ scope.row.userName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="评分" width="140">
          <template #default="scope">
            <el-rate :model-value="scope.row.rating" disabled size="small" />
          </template>
        </el-table-column>
        <el-table-column prop="content" label="评论内容" min-width="220" show-overflow-tooltip />
        <el-table-column label="评论时间" width="160">
          <template #default="scope">{{ formatTime(scope.row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="scope">
            <el-button size="small" type="danger" link :icon="Delete" @click="handleDelete(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-container">
        <el-pagination
          v-model:current-page="page.current"
          v-model:page-size="page.size"
          :page-sizes="[10, 20, 50]"
          :total="page.total"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="loadData"
          @current-change="loadData"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, Search, Refresh, DataAnalysis, Location, User, Delete } from '@element-plus/icons-vue'
import { getAllComments, adminDeleteComment } from '@/api/comment.js'

const loading = ref(false)
const tableData = ref([])
const page = reactive({ current: 1, size: 10, total: 0 })
const filterForm = reactive({ spotName: '', userId: '' })

// 筛选: 用户ID仅允许数字, 非数字时提示且不发起请求(避免后端类型转换异常)
function handleSearch() {
  const uid = (filterForm.userId || '').trim()
  if (uid && !/^\d+$/.test(uid)) {
    ElMessage.warning('用户ID请输入数字')
    return
  }
  page.current = 1
  loadData()
}

async function loadData() {
  loading.value = true
  try {
    const res = await getAllComments({
      spotName: filterForm.spotName || undefined,
      userId: filterForm.userId || undefined,
      current: page.current, size: page.size
    })
    tableData.value = res.records
    page.total = res.total
  } catch (err) { console.error(err) }
  finally { loading.value = false }
}

function handleReset() {
  filterForm.spotName = ''; filterForm.userId = ''
  page.current = 1; loadData()
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除该评论吗?"${row.content?.substring(0, 30)}${row.content?.length > 30 ? '...' : ''}"`,
      '提示', { type: 'warning' }
    )
    await adminDeleteComment(row.id)
    ElMessage.success('删除成功')
    loadData()
  } catch (err) { if (err !== 'cancel') console.error(err) }
}

function formatTime(t) {
  if (!t) return ''
  return t.replace('T', ' ').substring(0, 16)
}

onMounted(loadData)
</script>

<style scoped>
.page-card { border-radius: 12px; border: none; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.card-title { display: flex; align-items: center; gap: 14px; }
.title-icon {
  width: 42px; height: 42px; border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-size: 20px;
  box-shadow: 0 4px 12px rgba(247,151,30,0.3);
}
.card-title h3 { margin: 0; font-size: 16px; color: #1e2a47; font-weight: 600; }
.card-title p { margin: 2px 0 0; font-size: 12px; color: #909399; }
.card-stat { display: flex; align-items: center; gap: 6px; color: #606266; font-size: 13px; padding: 6px 14px; border-radius: 20px; }

.search-bar { display: flex; gap: 10px; margin-bottom: 16px; }

.modern-table { border-radius: 10px; overflow: hidden; }
.modern-table :deep(th.el-table__cell) { background: #f5f7fa !important; color: #606266; font-weight: 600; }
.user-cell { display: flex; align-items: center; gap: 6px; }
.mini-avatar { color: #fff; font-size: 11px; font-weight: 500; }

.pagination-container { margin-top: 18px; display: flex; justify-content: flex-end; }
</style>
