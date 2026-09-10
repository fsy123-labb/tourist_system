<template>
  <div class="dashboard">
    <!-- 顶部筛选条件 -->
    <el-card class="filter-card">
      <div class="filter-bar">
        <el-select v-model="filterForm.province" placeholder="选择省份" style="width: 150px" clearable>
          <el-option v-for="p in provinceOptions" :key="p" :label="p" :value="p" />
        </el-select>
        <el-select v-model="filterForm.spotType" placeholder="景区等级" style="width: 150px" clearable>
          <el-option v-for="t in spotTypes" :key="t" :label="t" :value="t" />
        </el-select>
        <el-button type="primary" @click="loadAllData">刷新数据</el-button>
      </div>
    </el-card>

    <!-- KPI概览卡片 -->
    <div class="kpi-row">
      <div class="kpi-card kpi-blue">
        <div class="kpi-title">景点总数</div>
        <div class="kpi-value">{{ kpiData.totalCount }}</div>
        <div class="kpi-unit">个</div>
      </div>
      <div class="kpi-card kpi-red">
        <div class="kpi-title">平均门票</div>
        <div class="kpi-value">¥{{ kpiData.avgPrice }}</div>
        <div class="kpi-unit">元</div>
      </div>
      <div class="kpi-card kpi-orange">
        <div class="kpi-title">平均评分</div>
        <div class="kpi-value">{{ kpiData.avgScore }}</div>
        <div class="kpi-unit">分</div>
      </div>
      <div class="kpi-card kpi-cyan">
        <div class="kpi-title">总销量</div>
        <div class="kpi-value">{{ formatNumber(kpiData.totalSales) }}</div>
        <div class="kpi-unit">次</div>
      </div>
      <div class="kpi-card kpi-green">
        <div class="kpi-title">免费景点</div>
        <div class="kpi-value">{{ kpiData.freeCount }}</div>
        <div class="kpi-unit">个</div>
      </div>
      <div class="kpi-card kpi-purple">
        <div class="kpi-title">5A景点</div>
        <div class="kpi-value">{{ kpiData.level5ACount }}</div>
        <div class="kpi-unit">个</div>
      </div>
      <div class="kpi-card kpi-darkgreen">
        <div class="kpi-title">4A及以上</div>
        <div class="kpi-value">{{ kpiData.level4APlusCount }}</div>
        <div class="kpi-unit">个</div>
      </div>
    </div>

    <!-- 图表区域 -->
    <div class="chart-container">
      <!-- 上排：省份TOP15柱状图 + 类型饼图 -->
      <div class="chart-row">
        <el-card class="chart-card">
          <div class="chart-title">{{ barChartTitle }}</div>
          <div ref="barChartRef" class="chart"></div>
        </el-card>
        <el-card class="chart-card">
          <div class="chart-title">景区等级占比</div>
          <div ref="pieChartRef" class="chart"></div>
        </el-card>
      </div>

      <!-- 新增行1：门票价格区间 + 各省平均门票价格 -->
      <div class="chart-row">
        <el-card class="chart-card">
          <div class="chart-title">门票价格区间分布</div>
          <div ref="priceRangeRef" class="chart"></div>
        </el-card>
        <el-card class="chart-card">
          <div class="chart-title">{{ filterForm.province ? filterForm.province + '平均门票价格' : '各省平均门票价格 TOP15' }}</div>
          <div ref="avgPriceRef" class="chart"></div>
        </el-card>
      </div>

      <!-- 中排：散点图 + TOP排行 -->
      <div class="chart-row">
        <el-card class="chart-card">
          <div class="chart-title">门票-评分散点图</div>
          <div ref="scatterChartRef" class="chart"></div>
        </el-card>
        <el-card class="chart-card">
          <div class="chart-title">高销量景点TOP10</div>
          <div ref="topChartRef" class="chart"></div>
        </el-card>
      </div>

      <!-- 数据分析算法一：K-Means 景点聚类 -->
      <div class="chart-row">
        <el-card class="chart-card">
          <div class="chart-title">K-Means 景点聚类分析（价格·评分）</div>
          <div class="cluster-desc">按门票价格与评分两维特征对景点做无监督聚类（Min-Max 归一化 + 欧氏距离迭代），不同颜色代表不同消费客群。圆形标记为各类簇中心。</div>
          <div ref="clusterChartRef" class="chart"></div>
        </el-card>
      </div>

      <!-- 新增行2：各省总销量 + 等级×省份堆叠 -->
      <div class="chart-row">
        <el-card class="chart-card">
          <div class="chart-title">{{ filterForm.province ? filterForm.province + '总销量' : '各省总销量 TOP15' }}</div>
          <div ref="salesSumRef" class="chart"></div>
        </el-card>
        <el-card class="chart-card">
          <div class="chart-title">等级×省份分布堆叠图</div>
          <div ref="stackRef" class="chart"></div>
        </el-card>
      </div>

      <!-- 底部：全国热力地图 -->
      <el-card class="chart-card map-card">
        <div class="chart-title">全国景点分布热力图</div>
        <div ref="mapChartRef" class="chart map-chart"></div>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, nextTick } from 'vue'
import * as echarts from 'echarts'
// 中国地图GeoJSON(本地资源,避免联网fetch失败导致热力图不渲染)
import chinaJson from '@/assets/china.json'
import { getProvinces } from '@/api/city.js'
// 导入统计API
import { getProvinceCount, getTypeCount, getPriceScoreData, getHotSalesTop, getPriceRange, getAvgPriceByProvince, getSalesSumByProvince, getProvinceLevelStack, getKpiOverview, getClusterAnalysis } from '@/api/stat.js'

// 筛选表单(必须在computed之前声明)
const filterForm = reactive({
  province: '',
  spotType: ''
})

// 动态柱状图标题(选了省份时只展示该省)
const barChartTitle = computed(() => {
  return filterForm.province ? `${filterForm.province}景点数量` : '各省份景点数量 TOP15'
})

// 图表实例引用
const barChartRef = ref(null)
const pieChartRef = ref(null)
const scatterChartRef = ref(null)
const topChartRef = ref(null)
const mapChartRef = ref(null)
// 新增图表ref
const priceRangeRef = ref(null)
const avgPriceRef = ref(null)
const salesSumRef = ref(null)
const stackRef = ref(null)
// K-Means聚类图ref
const clusterChartRef = ref(null)

// KPI概览数据(顶部数字卡片)
const kpiData = ref({
  totalCount: 0,
  avgPrice: 0,
  avgScore: 0,
  totalSales: 0,
  freeCount: 0,
  level5ACount: 0,
  level4APlusCount: 0
})

let barChart = null
let pieChart = null
let scatterChart = null
let topChart = null
let mapChart = null
// 新增图表实例
let priceRangeChart = null
let avgPriceChart = null
let salesSumChart = null
let stackChart = null
// K-Means聚类图实例
let clusterChart = null

const provinceOptions = ref([])
const spotTypes = ['5A景区', '4A景区', '3A景区', '未评级']

// 加载省份选项
async function loadProvinces() {
  try {
    const res = await getProvinces()
    provinceOptions.value = res || []
  } catch (err) {
    console.error(err)
  }
}

// 大数字千分位格式化(用于KPI总销量显示)
function formatNumber(val) {
  if (val == null) return '0'
  return Number(val).toLocaleString()
}

// 加载全部数据并刷新所有图表
async function loadAllData() {
  await nextTick()
  loadKpi()
  loadBarChart()
  loadPieChart()
  loadScatterChart()
  loadTopChart()
  loadMapChart()
  loadPriceRange()
  loadAvgPrice()
  loadSalesSum()
  loadStack()
  loadCluster()
}

// 省份柱状图 (选了省份时只展示该省一条, 否则 TOP15)
async function loadBarChart() {
  try {
    const res = await getProvinceCount(filterForm)
    // 选了省份时不截断(只有一条), 全国时取前15
    const topData = filterForm.province ? (res || []) : (res || []).slice(0, 15)
    barChart = echarts.init(barChartRef.value)
    const option = {
      tooltip: { trigger: 'axis' },
      grid: { left: 45, right: 20, top: 30, bottom: 50 },
      xAxis: {
        type: 'category',
        data: topData.map(i => i.name),
        axisLabel: {
          rotate: filterForm.province ? 0 : 45,
          fontSize: 11
        }
      },
      yAxis: {
        type: 'value',
        name: '景点数量',
        nameTextStyle: { fontSize: 11 }
      },
      series: [{
        type: 'bar',
        data: topData.map(i => i.value),
        itemStyle: { color: '#5470c6', borderRadius: [3, 3, 0, 0] },
        barMaxWidth: 28
      }]
    }
    barChart.setOption(option)
  } catch (err) {
    console.error(err)
  }
}

// 类型饼图
async function loadPieChart() {
  try {
    const res = await getTypeCount(filterForm)
    pieChart = echarts.init(pieChartRef.value)
    const option = {
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { orient: 'vertical', left: 'left', top: 'middle' },
      series: [{
        type: 'pie',
        radius: '60%',
        center: ['60%', '50%'],
        data: res,
        emphasis: { itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0, 0, 0, 0.5)' } }
      }]
    }
    pieChart.setOption(option)
  } catch (err) {
    console.error(err)
  }
}

// 散点图
async function loadScatterChart() {
  try {
    const res = await getPriceScoreData(filterForm)
    scatterChart = echarts.init(scatterChartRef.value)
    const option = {
      tooltip: {
        trigger: 'item',
        formatter: (params) => {
          return `${params.data[2]}<br/>价格: ¥${params.data[0]}<br/>评分: ${params.data[1]}`
        }
      },
      xAxis: { type: 'value', name: '门票价格' },
      yAxis: { type: 'value', name: '评分', min: 0, max: 5 },
      series: [{
        type: 'scatter',
        data: res.map(i => [i.price, i.score, i.spotName]),
        symbolSize: 10,
        itemStyle: { color: '#91cc75' }
      }]
    }
    scatterChart.setOption(option)
  } catch (err) {
    console.error(err)
  }
}

// TOP10条形图
async function loadTopChart() {
  try {
    const res = await getHotSalesTop({ ...filterForm, topN: 10 })
    topChart = echarts.init(topChartRef.value)
    const sortedData = [...res].sort((a, b) => a.value - b.value)
    // 计算合适的刻度间隔，避免重叠
    const maxVal = Math.max(...sortedData.map(i => i.value), 1000)
    // 把最大刻度向上取整到整千
    const maxRound = Math.ceil(maxVal / 1000) * 1000
    // 让刻度不超过5个
    const tickCount = 5
    const interval = Math.ceil(maxRound / tickCount / 100) * 100 || 500

    const option = {
      tooltip: { trigger: 'axis' },
      grid: { left: 150, right: 70, top: 40, bottom: 40 },
      xAxis: {
        type: 'value',
        // 最大值给一点余量（条形右侧有label）
        max: (val) => Math.ceil(val.max / interval) * interval + interval / 2,
        interval: interval,
        name: '销量',
        nameLocation: 'middle',
        nameGap: 28,
        nameTextStyle: { fontSize: 13, color: '#666' },
        axisLabel: {
          fontSize: 11,
          color: '#888',
          // 简化数字显示
          formatter: (val) => val >= 1000 ? (val / 1000).toFixed(1) + 'k' : val
        }
      },
      yAxis: {
        type: 'category',
        data: sortedData.map(i => i.name),
        axisLabel: {
          fontSize: 12,
          color: '#333',
          width: 140,
          overflow: 'truncate',
          ellipsis: '...'
        }
      },
      series: [{
        type: 'bar',
        data: sortedData.map(i => i.value),
        itemStyle: { color: '#fac858', borderRadius: [0, 4, 4, 0] },
        barMaxWidth: 22,
        label: {
          show: true,
          position: 'right',
          fontSize: 11,
          color: '#555'
        }
      }]
    }
    topChart.setOption(option)
  } catch (err) {
    console.error(err)
  }
}

// 省份名称映射（数据简称 → ECharts地图全称）
const provinceNameMap = {
  '北京': '北京市', '天津': '天津市', '上海': '上海市', '重庆': '重庆市',
  '河北': '河北省', '山西': '山西省', '辽宁': '辽宁省', '吉林': '吉林省', '黑龙江': '黑龙江省',
  '江苏': '江苏省', '浙江': '浙江省', '安徽': '安徽省', '福建': '福建省', '江西': '江西省', '山东': '山东省',
  '河南': '河南省', '湖北': '湖北省', '湖南': '湖南省', '广东': '广东省', '海南': '海南省',
  '四川': '四川省', '贵州': '贵州省', '云南': '云南省', '陕西': '陕西省', '甘肃': '甘肃省', '青海': '青海省',
  '台湾': '台湾省', '内蒙古': '内蒙古自治区', '广西': '广西壮族自治区',
  '西藏': '西藏自治区', '宁夏': '宁夏回族自治区', '新疆': '新疆维吾尔自治区',
  '香港': '香港特别行政区', '澳门': '澳门特别行政区'
}

// 全国热力地图 - 修正版
async function loadMapChart() {
  try {
    // 中国地图已在模块加载时同步注册(本地GeoJSON)

    // 复用省份统计接口(带筛选时只返回该省数据, 全国时返回全部)
    const res = await getProvinceCount(filterForm)
    const rawData = res || []
    const maxValue = Math.max(...rawData.map(d => d.value), 10)

    if (mapChart) {
      mapChart.dispose()
    }
    mapChart = echarts.init(mapChartRef.value)

    // 如果选了省份, 只显示该省, 其他省用0填充保持地图轮廓完整
    let heatData
    if (filterForm.province) {
      const selectedItem = rawData.length > 0 ? rawData[0] : { name: filterForm.province, value: 0 }
      heatData = Object.keys(provinceNameMap).map(key => {
        const fullName = provinceNameMap[key]
        if (fullName === provinceNameMap[selectedItem.name] || key === selectedItem.name) {
          return { name: fullName, value: selectedItem.value }
        }
        return { name: fullName, value: 0 }
      })
    } else {
      // 全国模式: 短省名→全称, 匹配ECharts地图区域名
      heatData = rawData.map(item => ({
        name: provinceNameMap[item.name] || item.name,
        value: item.value
      }))
    }

    const option = {
      tooltip: {
        trigger: 'item',
        formatter: '{b}: {c} 个景点'
      },
      // 颜色梯度条 - 放在右侧中间，不被截断
      visualMap: {
        min: 0,
        max: maxValue,
        calculable: true,
        orient: 'vertical',
        right: 20,
        top: 'center',
        text: ['多', '少'],
        textStyle: { fontSize: 13 },
        inRange: {
          color: ['#e0f3db', '#a8ddb5', '#7bccc4', '#4eb3d3', '#2b8cbe', '#08589e']
        }
      },
      series: [{
        type: 'map',
        map: 'china',
        roam: true,
        // 居中布局，为南海诸岛留够空间
        layoutCenter: ['48%', '55%'],
        layoutSize: '95%',
        label: {
          show: true,
          fontSize: 11,
          color: '#333'
        },
        itemStyle: {
          borderColor: '#fff',
          borderWidth: 0.8
        },
        data: heatData,
        emphasis: {
          label: { show: true, fontSize: 13, fontWeight: 'bold' },
          itemStyle: { areaColor: '#ffeb3b' }
        }
      }]
    }
    mapChart.setOption(option)
    // 渲染完成后手动resize确保布局正确
    setTimeout(() => mapChart.resize(), 200)
  } catch (err) {
    console.error('加载热力地图失败:', err)
  }
}

// KPI概览卡片(顶部数字汇总)
async function loadKpi() {
  try {
    const res = await getKpiOverview(filterForm)
    kpiData.value = res
  } catch (err) {
    console.error(err)
  }
}

// 门票价格区间分布(饼图)
async function loadPriceRange() {
  try {
    const res = await getPriceRange(filterForm)
    priceRangeChart = echarts.init(priceRangeRef.value)
    const option = {
      tooltip: { trigger: 'item', formatter: '{b}: {c} 个 ({d}%)' },
      legend: { orient: 'vertical', left: 'left', top: 'middle' },
      series: [{
        type: 'pie',
        radius: '60%',
        center: ['60%', '50%'],
        data: res,
        emphasis: { itemStyle: { shadowBlur: 10, shadowColor: 'rgba(0,0,0,0.5)' } }
      }]
    }
    priceRangeChart.setOption(option)
  } catch (err) {
    console.error(err)
  }
}

// 各省平均门票价格(柱状图, 取TOP15)
async function loadAvgPrice() {
  try {
    const res = await getAvgPriceByProvince(filterForm)
    const topData = filterForm.province ? (res || []) : (res || []).slice(0, 15)
    avgPriceChart = echarts.init(avgPriceRef.value)
    const option = {
      tooltip: {
        trigger: 'axis',
        formatter: (params) => `${params[0].name}: ¥${params[0].value}`
      },
      grid: { left: 50, right: 20, top: 30, bottom: 50 },
      xAxis: {
        type: 'category',
        data: topData.map(i => i.name),
        axisLabel: { rotate: filterForm.province ? 0 : 45, fontSize: 11 }
      },
      yAxis: {
        type: 'value',
        name: '均价(元)',
        nameTextStyle: { fontSize: 11 }
      },
      series: [{
        type: 'bar',
        data: topData.map(i => i.value),
        itemStyle: { color: '#ee6666', borderRadius: [3, 3, 0, 0] },
        barMaxWidth: 28,
        label: { show: true, position: 'top', fontSize: 10, formatter: '¥{c}' }
      }]
    }
    avgPriceChart.setOption(option)
  } catch (err) {
    console.error(err)
  }
}

// 各省总销量排行(横向条形图, 取TOP15)
async function loadSalesSum() {
  try {
    const res = await getSalesSumByProvince(filterForm)
    const topData = filterForm.province ? (res || []) : (res || []).slice(0, 15)
    // 横向条形图从小到大排序, 使大值显示在顶部
    const sortedData = [...topData].sort((a, b) => a.value - b.value)
    salesSumChart = echarts.init(salesSumRef.value)
    const option = {
      tooltip: { trigger: 'axis' },
      grid: { left: 90, right: 60, top: 30, bottom: 40 },
      xAxis: {
        type: 'value',
        name: '总销量',
        nameTextStyle: { fontSize: 11 },
        axisLabel: {
          fontSize: 11,
          formatter: (val) => val >= 1000 ? (val / 1000).toFixed(1) + 'k' : val
        }
      },
      yAxis: {
        type: 'category',
        data: sortedData.map(i => i.name),
        axisLabel: { fontSize: 11 }
      },
      series: [{
        type: 'bar',
        data: sortedData.map(i => i.value),
        itemStyle: { color: '#73c0de', borderRadius: [0, 4, 4, 0] },
        barMaxWidth: 22,
        label: { show: true, position: 'right', fontSize: 10 }
      }]
    }
    salesSumChart.setOption(option)
  } catch (err) {
    console.error(err)
  }
}

// 等级×省份堆叠柱状图
async function loadStack() {
  try {
    const res = await getProvinceLevelStack(filterForm)
    stackChart = echarts.init(stackRef.value)
    // 等级颜色: 5A红 / 4A橙 / 3A蓝 / 未评级灰
    const colors = ['#ee6666', '#fac858', '#5470c6', '#aaaaaa']
    const option = {
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      legend: { top: 0 },
      grid: { left: 40, right: 20, top: 40, bottom: 50 },
      xAxis: {
        type: 'category',
        data: res.provinces || [],
        axisLabel: { rotate: 35, fontSize: 11 }
      },
      yAxis: { type: 'value', name: '景点数', nameTextStyle: { fontSize: 11 } },
      series: (res.series || []).map((s, idx) => ({
        name: s.name,
        type: 'bar',
        stack: 'total',
        data: s.data,
        itemStyle: { color: colors[idx % colors.length] },
        barMaxWidth: 40
      }))
    }
    stackChart.setOption(option)
  } catch (err) {
    console.error(err)
  }
}

// K-Means聚类分析散点图(价格x, 评分y, 按簇着色 + 标绘中心点)
async function loadCluster() {
  try {
    const res = await getClusterAnalysis({ ...filterForm, sampleSize: 800, k: 4 })
    if (!res || !res.points) {
      clusterChart = echarts.init(clusterChartRef.value)
      return
    }
    // 按簇编号分组, 每种簇一种颜色
    const clusterColors = ['#5470c6', '#ee6666', '#91cc75', '#fac858', '#9a60b4', '#73c0de', '#3ba272', '#fc8452']
    // 按时簇规模统计簇数量, 用作series顺序(保证图例与颜色对应)
    const kCount = (res.centroids || []).length || Math.max(...res.points.map(p => p.clusterId)) + 1
    const series = []
    // 每个簇一条scatter series
    for (let c = 0; c < kCount; c++) {
      const pts = res.points.filter(p => p.clusterId === c)
      series.push({
        name: (res.centroids[c]?.clusterName) || `簇${c}`,
        type: 'scatter',
        data: pts.map(p => [p.price, p.score, p.name]),
        symbolSize: 9,
        itemStyle: { color: clusterColors[c % clusterColors.length], opacity: 0.75 }
      })
    }
    // 追加聚类中心点(大圆点+菱形)
    if (res.centroids && res.centroids.length) {
      series.push({
        name: '聚类中心',
        type: 'scatter',
        data: res.centroids.map(cn => [cn.price, cn.score, cn.clusterName]),
        symbolSize: 15,
        symbol: 'diamond',
        itemStyle: { color: '#333', borderColor: '#fff', borderWidth: 2 },
        label: { show: true, formatter: '{c}', position: 'top', fontSize: 10, color: '#333' }
      })
    }

    clusterChart = echarts.init(clusterChartRef.value)
    const option = {
      // 右上角图例, 展示各簇名
      legend: { top: 0, type: 'scroll', textStyle: { fontSize: 11 } },
      grid: { left: 50, right: 25, top: 40, bottom: 45 },
      tooltip: {
        trigger: 'item',
        formatter: (params) => {
          const d = params.data
          return `${d[2]}<br/>门票: ¥${d[0]}<br/>评分: ${d[1]}`
        }
      },
      xAxis: { type: 'value', name: '门票价格(元)', nameTextStyle: { fontSize: 11 } },
      yAxis: { type: 'value', name: '评分', min: 0, max: 5, nameTextStyle: { fontSize: 11 } },
      series
    }
    clusterChart.setOption(option)
  } catch (err) {
    console.error('加载K-Means聚类图失败:', err)
  }
}

// 窗口大小变化时重新渲染图表
function handleResize() {
  barChart?.resize()
  pieChart?.resize()
  scatterChart?.resize()
  topChart?.resize()
  mapChart?.resize()
  priceRangeChart?.resize()
  avgPriceChart?.resize()
  salesSumChart?.resize()
  stackChart?.resize()
  clusterChart?.resize()
}

onMounted(async () => {
  // 注册中国地图(带错误保护,避免GeoJSON异常导致整个大屏崩溃)
  try {
    echarts.registerMap('china', chinaJson)
  } catch (e) {
    console.error('注册中国地图失败:', e)
  }
  await loadProvinces()
  await loadAllData()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  barChart?.dispose()
  pieChart?.dispose()
  scatterChart?.dispose()
  topChart?.dispose()
  mapChart?.dispose()
  priceRangeChart?.dispose()
  avgPriceChart?.dispose()
  salesSumChart?.dispose()
  stackChart?.dispose()
  clusterChart?.dispose()
})
</script>

<style scoped>
.dashboard {
  padding: 0;
}

.filter-card {
  margin-bottom: 20px;
}

.filter-bar {
  display: flex;
  gap: 15px;
  align-items: center;
}

.chart-container {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.chart-row {
  display: flex;
  gap: 20px;
}

.chart-card {
  flex: 1;
  margin: 0;
}

.chart-title {
  font-size: 16px;
  font-weight: bold;
  margin-bottom: 10px;
  color: #303133;
}

.chart {
  width: 100%;
  height: 320px;
}

/* K-Means聚类图说明文字 */
.cluster-desc {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
  line-height: 1.5;
}

.map-card {
  width: 100%;
}

.map-chart {
  height: 600px;
  width: 100%;
}

/* KPI概览卡片样式 */
.kpi-row {
  display: flex;
  gap: 15px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}
.kpi-card {
  flex: 1;
  min-width: 130px;
  background: #fff;
  border-radius: 8px;
  padding: 16px 20px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  border-left: 4px solid #5470c6;
  text-align: center;
}
.kpi-card.kpi-blue { border-left-color: #5470c6; }
.kpi-card.kpi-red { border-left-color: #ee6666; }
.kpi-card.kpi-orange { border-left-color: #fac858; }
.kpi-card.kpi-cyan { border-left-color: #73c0de; }
.kpi-card.kpi-green { border-left-color: #91cc75; }
.kpi-card.kpi-purple { border-left-color: #9a60b4; }
.kpi-card.kpi-darkgreen { border-left-color: #3ba272; }
.kpi-title {
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}
.kpi-value {
  font-size: 24px;
  font-weight: bold;
  color: #303133;
}
.kpi-unit {
  font-size: 12px;
  color: #c0c4cc;
  margin-top: 4px;
}
</style>
