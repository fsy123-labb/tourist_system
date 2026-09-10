package com.tourism.controller;

import com.tourism.common.Result;
import com.tourism.dto.request.SpotQueryRequest;
import com.tourism.dto.response.ClusterAnalysisResponse;
import com.tourism.dto.response.KpiResponse;
import com.tourism.dto.response.PriceScoreResponse;
import com.tourism.dto.response.ProvinceStackResponse;
import com.tourism.dto.response.StatDecimalResponse;
import com.tourism.dto.response.StatResponse;
import com.tourism.service.StatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 统计Controller(给ECharts大屏用)
 * 所有接口需要token鉴权
 */
@RestController
@RequestMapping("/api/stat")
public class StatController {

    @Autowired
    private StatService statService;

    /**
     * 按省份统计景点数量(柱状图)
     * GET /api/stat/provinceCount
     */
    @GetMapping("/provinceCount")
    public Result<List<StatResponse>> countByProvince(SpotQueryRequest query) {
        List<StatResponse> result = statService.countByProvince(query);
        return Result.success(result);
    }

    /**
     * 按类型统计数量占比(饼图)
     * GET /api/stat/typeCount
     */
    @GetMapping("/typeCount")
    public Result<List<StatResponse>> countByType(SpotQueryRequest query) {
        List<StatResponse> result = statService.countByType(query);
        return Result.success(result);
    }

    /**
     * 高销量TOP10(条形图)
     * GET /api/stat/hotSalesSpot
     */
    @GetMapping("/hotSalesSpot")
    public Result<List<StatResponse>> topSpots(
            SpotQueryRequest query,
            @RequestParam(defaultValue = "10") Integer topN) {
        List<StatResponse> result = statService.topSpots(query, topN);
        return Result.success(result);
    }

    /**
     * 门票-评分散点图数据
     * GET /api/stat/priceScoreData
     */
    @GetMapping("/priceScoreData")
    public Result<List<PriceScoreResponse>> priceScoreData(SpotQueryRequest query) {
        List<PriceScoreResponse> result = statService.priceScoreData(query);
        return Result.success(result);
    }

    /**
     * 门票价格区间分布(饼图/柱状图)
     * GET /api/stat/priceRange
     */
    @GetMapping("/priceRange")
    public Result<List<StatResponse>> priceRangeCount(SpotQueryRequest query) {
        List<StatResponse> result = statService.priceRangeCount(query);
        return Result.success(result);
    }

    /**
     * 各省平均门票价格对比(柱状图)
     * GET /api/stat/avgPriceByProvince
     */
    @GetMapping("/avgPriceByProvince")
    public Result<List<StatDecimalResponse>> avgPriceByProvince(SpotQueryRequest query) {
        List<StatDecimalResponse> result = statService.avgPriceByProvince(query);
        return Result.success(result);
    }

    /**
     * 各省总销量排行(横向条形图)
     * GET /api/stat/salesSumByProvince
     */
    @GetMapping("/salesSumByProvince")
    public Result<List<StatResponse>> salesSumByProvince(SpotQueryRequest query) {
        List<StatResponse> result = statService.salesSumByProvince(query);
        return Result.success(result);
    }

    /**
     * 等级×省份堆叠柱状图
     * GET /api/stat/provinceLevelStack
     */
    @GetMapping("/provinceLevelStack")
    public Result<ProvinceStackResponse> provinceLevelStack(SpotQueryRequest query) {
        ProvinceStackResponse result = statService.provinceLevelStack(query);
        return Result.success(result);
    }

    /**
     * KPI概览(数字卡片)
     * GET /api/stat/kpiOverview
     */
    @GetMapping("/kpiOverview")
    public Result<KpiResponse> kpiOverview(SpotQueryRequest query) {
        KpiResponse result = statService.kpiOverview(query);
        return Result.success(result);
    }

    /**
     * K-Means聚类分析(景点按价格-评分聚类, 无监督学习算法)
     * GET /api/stat/clusterAnalysis?sampleSize=800&k=4
     */
    @GetMapping("/clusterAnalysis")
    public Result<ClusterAnalysisResponse> clusterAnalysis(
            SpotQueryRequest query,
            @RequestParam(defaultValue = "800") Integer sampleSize,
            @RequestParam(defaultValue = "4") Integer k) {
        ClusterAnalysisResponse result = statService.clusterAnalysis(query, sampleSize, k);
        return Result.success(result);
    }
}

