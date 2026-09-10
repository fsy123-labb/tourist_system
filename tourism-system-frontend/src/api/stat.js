import request from '@/utils/request.js'

/**
 * 按省份统计景点数量
 */
export function getProvinceCount(params) {
  return request({
    url: '/api/stat/provinceCount',
    method: 'get',
    params
  })
}

/**
 * 按类型统计数量
 */
export function getTypeCount(params) {
  return request({
    url: '/api/stat/typeCount',
    method: 'get',
    params
  })
}

/**
 * 高销量TOP10
 */
export function getHotSalesTop(params) {
  return request({
    url: '/api/stat/hotSalesSpot',
    method: 'get',
    params
  })
}

/**
 * 门票评分数据
 */
export function getPriceScoreData(params) {
  return request({
    url: '/api/stat/priceScoreData',
    method: 'get',
    params
  })
}

/**
 * 门票价格区间分布(免费/0-50/50-100/100-200/200元以上)
 */
export function getPriceRange(params) {
  return request({
    url: '/api/stat/priceRange',
    method: 'get',
    params
  })
}

/**
 * 各省平均门票价格对比
 */
export function getAvgPriceByProvince(params) {
  return request({
    url: '/api/stat/avgPriceByProvince',
    method: 'get',
    params
  })
}

/**
 * 各省总销量排行(反映旅游热度)
 */
export function getSalesSumByProvince(params) {
  return request({
    url: '/api/stat/salesSumByProvince',
    method: 'get',
    params
  })
}

/**
 * 等级×省份堆叠柱状图(省份列表 + 各等级series)
 */
export function getProvinceLevelStack(params) {
  return request({
    url: '/api/stat/provinceLevelStack',
    method: 'get',
    params
  })
}

/**
 * KPI概览(总数/均价/均分/总销量/免费数/5A数/4A以上数)
 */
export function getKpiOverview(params) {
  return request({
    url: '/api/stat/kpiOverview',
    method: 'get',
    params
  })
}

/**
 * K-Means聚类分析(景点按价格-评分无监督聚类)
 * @param params { sampleSize, k, ...筛选条件 }
 */
export function getClusterAnalysis(params) {
  return request({
    url: '/api/stat/clusterAnalysis',
    method: 'get',
    params
  })
}
