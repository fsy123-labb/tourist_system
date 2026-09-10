package com.tourism.service;

import com.tourism.dto.request.SpotQueryRequest;
import com.tourism.dto.response.ClusterAnalysisResponse;
import com.tourism.dto.response.KpiResponse;
import com.tourism.dto.response.PriceScoreResponse;
import com.tourism.dto.response.ProvinceStackResponse;
import com.tourism.dto.response.StatDecimalResponse;
import com.tourism.dto.response.StatResponse;

import java.util.List;

/**
 * 统计Service接口(给ECharts大屏使用)
 */
public interface StatService {

    /**
     * 按省份统计景点数量
     * @param query 查询条件(支持省份、类型筛选)
     * @return 统计结果
     */
    List<StatResponse> countByProvince(SpotQueryRequest query);

    /**
     * 按景点类型统计数量占比
     * @param query 查询条件
     * @return 统计结果
     */
    List<StatResponse> countByType(SpotQueryRequest query);

    /**
     * 高销量景点TOP10(按销量降序)
     * @param query 查询条件
     * @param topN TOP数量
     * @return 统计结果
     */
    List<StatResponse> topSpots(SpotQueryRequest query, Integer topN);

    /**
     * 获取门票-评分散点图数据
     * @param query 查询条件
     * @return 散点数据
     */
    List<PriceScoreResponse> priceScoreData(SpotQueryRequest query);

    /**
     * 门票价格区间分布(免费/0-50/50-100/100-200/200元以上)
     * @param query 查询条件
     * @return 各区间景点数量
     */
    List<StatResponse> priceRangeCount(SpotQueryRequest query);

    /**
     * 各省平均门票价格对比
     * @param query 查询条件
     * @return 各省平均价格(保留2位小数)
     */
    List<StatDecimalResponse> avgPriceByProvince(SpotQueryRequest query);

    /**
     * 各省总销量排行(反映旅游热度)
     * @param query 查询条件
     * @return 各省销量总和, 按降序排列
     */
    List<StatResponse> salesSumByProvince(SpotQueryRequest query);

    /**
     * 等级×省份堆叠柱状图(取景点总数TOP12省份)
     * @param query 查询条件
     * @return 省份列表 + 各等级series数据
     */
    ProvinceStackResponse provinceLevelStack(SpotQueryRequest query);

    /**
     * KPI概览(总数/均价/均分/总销量/免费数/5A数/4A以上数)
     * @param query 查询条件
     * @return KPI聚合数据
     */
    KpiResponse kpiOverview(SpotQueryRequest query);

    /**
     * K-Means聚类分析(无监督学习): 以(门票价格,评分)两维为特征,
     * 用K-Means算法将景点划分为K个簇, 用于大屏聚类散点图
     * @param query 查询条件(支持省份、类型筛选)
     * @param sampleSize 参与聚类的随机采样样本数
     * @param k 聚类簇数
     * @return 聚类点 + 中心点 + 各簇规模
     */
    ClusterAnalysisResponse clusterAnalysis(SpotQueryRequest query, Integer sampleSize, Integer k);
}

