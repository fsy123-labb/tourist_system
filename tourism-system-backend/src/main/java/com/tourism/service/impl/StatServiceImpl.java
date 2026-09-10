package com.tourism.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tourism.dto.request.SpotQueryRequest;
import com.tourism.dto.response.ClusterAnalysisResponse;
import com.tourism.dto.response.KpiResponse;
import com.tourism.dto.response.PriceScoreResponse;
import com.tourism.dto.response.ProvinceStackResponse;
import com.tourism.dto.response.StatDecimalResponse;
import com.tourism.dto.response.StatResponse;
import com.tourism.entity.ScenicCity;
import com.tourism.entity.ScenicSpot;
import com.tourism.mapper.ScenicCityMapper;
import com.tourism.mapper.ScenicSpotMapper;
import com.tourism.service.StatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 统计Service实现类
 */
@Service
public class StatServiceImpl implements StatService {

    @Autowired
    private ScenicSpotMapper scenicSpotMapper;

    @Autowired
    private ScenicCityMapper scenicCityMapper;

    @Override
    public List<StatResponse> countByProvince(SpotQueryRequest query) {
        // 聚合下推到数据库: GROUP BY city_id 只返回约100行, 避免全表13617行拉到内存
        QueryWrapper<ScenicSpot> wrapper = new QueryWrapper<>();
        wrapper.select("city_id", "COUNT(*) AS cnt");
        fillConditions(wrapper, query);
        wrapper.groupBy("city_id");
        List<Map<String, Object>> rows = scenicSpotMapper.selectMaps(wrapper);

        // 城市表只有100条, 建立id→省份映射
        Map<Integer, String> cityProvinceMap = new HashMap<>();
        for (ScenicCity city : scenicCityMapper.selectList(null)) {
            cityProvinceMap.put(city.getId(), city.getProvince());
        }

        // 按省份汇总
        Map<String, Long> provinceCountMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Integer cityId = ((Number) row.get("city_id")).intValue();
            Long cnt = ((Number) row.get("cnt")).longValue();
            String province = cityProvinceMap.get(cityId);
            if (province != null) {
                provinceCountMap.merge(province, cnt, Long::sum);
            }
        }

        return provinceCountMap.entrySet().stream()
                .map(entry -> new StatResponse(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .collect(Collectors.toList());
    }

    @Override
    public List<StatResponse> countByType(SpotQueryRequest query) {
        // 聚合下推到数据库: GROUP BY spot_type 只返回几行
        QueryWrapper<ScenicSpot> wrapper = new QueryWrapper<>();
        wrapper.select("spot_type", "COUNT(*) AS cnt");
        fillConditions(wrapper, query);
        wrapper.groupBy("spot_type");
        List<Map<String, Object>> rows = scenicSpotMapper.selectMaps(wrapper);

        return rows.stream()
                .map(row -> new StatResponse(
                        row.get("spot_type") != null ? row.get("spot_type").toString() : "未知类型",
                        ((Number) row.get("cnt")).longValue()))
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .collect(Collectors.toList());
    }

    @Override
    public List<StatResponse> topSpots(SpotQueryRequest query, Integer topN) {
        if (topN == null || topN <= 0) {
            topN = 10;
        }
        // 高销量排行按 sales_volume 降序
        LambdaQueryWrapper<ScenicSpot> wrapper = buildSpotWrapper(query);
        wrapper.orderByDesc(ScenicSpot::getSalesVolume).last("LIMIT " + topN);
        List<ScenicSpot> spots = scenicSpotMapper.selectList(wrapper);

        return spots.stream()
                .map(spot -> new StatResponse(spot.getSpotName(),
                        spot.getSalesVolume() != null ? spot.getSalesVolume().longValue() : 0L))
                .collect(Collectors.toList());
    }

    @Override
    public List<PriceScoreResponse> priceScoreData(SpotQueryRequest query) {
        LambdaQueryWrapper<ScenicSpot> wrapper = buildSpotWrapper(query);
        // 只查询有门票价格和评分的景点, 且只取3个必要列(不拉景点介绍等大文本)
        wrapper.isNotNull(ScenicSpot::getTicketPrice)
                .isNotNull(ScenicSpot::getScore)
                .select(ScenicSpot::getTicketPrice, ScenicSpot::getScore, ScenicSpot::getSpotName);
        // 全量1.3万个点传输慢且视觉上挤成一团, 随机采样2000个点, 分布形状不变
        wrapper.last("ORDER BY RAND() LIMIT 2000");
        List<ScenicSpot> spots = scenicSpotMapper.selectList(wrapper);

        return spots.stream()
                .map(spot -> new PriceScoreResponse(
                        spot.getTicketPrice(),
                        spot.getScore(),
                        spot.getSpotName()))
                .collect(Collectors.toList());
    }

    /**
     * 构建景点查询条件(公用)
     */
    private LambdaQueryWrapper<ScenicSpot> buildSpotWrapper(SpotQueryRequest query) {
        LambdaQueryWrapper<ScenicSpot> wrapper = new LambdaQueryWrapper<>();
        if (query == null) {
            return wrapper;
        }
        // 省份筛选: 查出该省所有城市ID, 按 city_id IN (...) 过滤
        if (StringUtils.hasText(query.getProvince())) {
            List<Integer> cityIds = scenicCityMapper.selectList(
                    new LambdaQueryWrapper<ScenicCity>()
                            .eq(ScenicCity::getProvince, query.getProvince())
            ).stream().map(ScenicCity::getId).collect(Collectors.toList());
            if (cityIds.isEmpty()) {
                wrapper.eq(ScenicSpot::getCityId, -1);
                return wrapper;
            }
            wrapper.in(ScenicSpot::getCityId, cityIds);
            if (query.getCityId() != null && !cityIds.contains(query.getCityId())) {
                wrapper.eq(ScenicSpot::getCityId, -1);
                return wrapper;
            }
        }
        if (query.getCityId() != null) {
            wrapper.eq(ScenicSpot::getCityId, query.getCityId());
        }
        if (StringUtils.hasText(query.getSpotType())) {
            wrapper.eq(ScenicSpot::getSpotType, query.getSpotType());
        }
        if (StringUtils.hasText(query.getSpotName())) {
            wrapper.like(ScenicSpot::getSpotName, query.getSpotName());
        }
        if (query.getMinScore() != null) {
            wrapper.ge(ScenicSpot::getScore, query.getMinScore());
        }
        if (query.getMaxScore() != null) {
            wrapper.le(ScenicSpot::getScore, query.getMaxScore());
        }
        if (query.getMinPrice() != null) {
            wrapper.ge(ScenicSpot::getTicketPrice, query.getMinPrice());
        }
        if (query.getMaxPrice() != null) {
            wrapper.le(ScenicSpot::getTicketPrice, query.getMaxPrice());
        }
        return wrapper;
    }

    /**
     * 聚合查询用的筛选条件(QueryWrapper字符串列名版, 条件与buildSpotWrapper保持一致)
     */
    private void fillConditions(QueryWrapper<ScenicSpot> wrapper, SpotQueryRequest query) {
        if (query == null) {
            return;
        }
        // 省份筛选: 查出该省所有城市ID, 按 city_id IN (...) 过滤
        if (StringUtils.hasText(query.getProvince())) {
            List<Integer> cityIds = scenicCityMapper.selectList(
                    new LambdaQueryWrapper<ScenicCity>()
                            .eq(ScenicCity::getProvince, query.getProvince())
            ).stream().map(ScenicCity::getId).collect(Collectors.toList());
            if (cityIds.isEmpty()) {
                // 该省份无城市, 直接让查询无结果
                wrapper.eq("city_id", -1);
                return;
            }
            wrapper.in("city_id", cityIds);
            // 如果同时指定了cityId, 需要校验是否在该省内
            if (query.getCityId() != null && !cityIds.contains(query.getCityId())) {
                wrapper.eq("city_id", -1);
                return;
            }
        }
        if (query.getCityId() != null) {
            wrapper.eq("city_id", query.getCityId());
        }
        if (StringUtils.hasText(query.getSpotType())) {
            wrapper.eq("spot_type", query.getSpotType());
        }
        if (StringUtils.hasText(query.getSpotName())) {
            wrapper.like("spot_name", query.getSpotName());
        }
        if (query.getMinScore() != null) {
            wrapper.ge("score", query.getMinScore());
        }
        if (query.getMaxScore() != null) {
            wrapper.le("score", query.getMaxScore());
        }
        if (query.getMinPrice() != null) {
            wrapper.ge("ticket_price", query.getMinPrice());
        }
        if (query.getMaxPrice() != null) {
            wrapper.le("ticket_price", query.getMaxPrice());
        }
    }

    // ==================== 大屏新增图表统计方法 ====================

    /**
     * 加载 city_id → province 映射(城市表仅约100行, 一次查全表)
     * 供按省份聚合的方法复用, 避免重复查询
     */
    private Map<Integer, String> getCityProvinceMap() {
        Map<Integer, String> map = new HashMap<>();
        for (ScenicCity city : scenicCityMapper.selectList(null)) {
            map.put(city.getId(), city.getProvince());
        }
        return map;
    }

    /**
     * 安全转换 Object → BigDecimal(处理 SUM/AVG 返回的 BigDecimal/Long/Integer)
     */
    private BigDecimal toBigDecimal(Object obj) {
        if (obj == null) {
            return BigDecimal.ZERO;
        }
        if (obj instanceof BigDecimal) {
            return (BigDecimal) obj;
        }
        return new BigDecimal(obj.toString());
    }

    /**
     * 安全转换 Object → Long(处理 COUNT/SUM(CASE...) 返回的数值)
     */
    private Long toLong(Object obj) {
        if (obj == null) {
            return 0L;
        }
        return toBigDecimal(obj).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    /**
     * 图表1: 门票价格区间分布
     * 用 CASE 分桶聚合到数据库, 避免全表拉取
     */
    @Override
    public List<StatResponse> priceRangeCount(SpotQueryRequest query) {
        QueryWrapper<ScenicSpot> wrapper = new QueryWrapper<>();
        wrapper.select("CASE WHEN ticket_price = 0 THEN '免费' " +
                "WHEN ticket_price < 50 THEN '0-50元' " +
                "WHEN ticket_price < 100 THEN '50-100元' " +
                "WHEN ticket_price < 200 THEN '100-200元' " +
                "ELSE '200元以上' END AS price_range, COUNT(*) AS cnt");
        fillConditions(wrapper, query);
        wrapper.groupBy("price_range");
        List<Map<String, Object>> rows = scenicSpotMapper.selectMaps(wrapper);

        // 固定区间顺序, 保证图表顺序一致
        List<String> order = Arrays.asList("免费", "0-50元", "50-100元", "100-200元", "200元以上");
        Map<String, Long> rangeMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String range = row.get("price_range") != null ? row.get("price_range").toString() : "未知";
            rangeMap.put(range, toLong(row.get("cnt")));
        }
        List<StatResponse> result = new ArrayList<>();
        for (String range : order) {
            result.add(new StatResponse(range, rangeMap.getOrDefault(range, 0L)));
        }
        return result;
    }

    /**
     * 图表2: 各省平均门票价格对比
     * 先按 city_id 聚合 SUM/COUNT, 再用城市映射汇总成省均(加权平均, 避免简单平均失真)
     */
    @Override
    public List<StatDecimalResponse> avgPriceByProvince(SpotQueryRequest query) {
        QueryWrapper<ScenicSpot> wrapper = new QueryWrapper<>();
        wrapper.select("city_id", "SUM(ticket_price) AS sum_price", "COUNT(ticket_price) AS cnt_price");
        fillConditions(wrapper, query);
        wrapper.groupBy("city_id");
        List<Map<String, Object>> rows = scenicSpotMapper.selectMaps(wrapper);

        Map<Integer, String> cityProvinceMap = getCityProvinceMap();
        // province → [总价格, 总数量], 加权平均 = 总和/总数
        Map<String, BigDecimal[]> provinceAgg = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Integer cityId = ((Number) row.get("city_id")).intValue();
            String province = cityProvinceMap.get(cityId);
            if (province == null) {
                continue;
            }
            BigDecimal sum = toBigDecimal(row.get("sum_price"));
            BigDecimal cnt = toBigDecimal(row.get("cnt_price"));
            BigDecimal[] agg = provinceAgg.computeIfAbsent(province, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            agg[0] = agg[0].add(sum);
            agg[1] = agg[1].add(cnt);
        }

        List<StatDecimalResponse> result = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> entry : provinceAgg.entrySet()) {
            BigDecimal sum = entry.getValue()[0];
            BigDecimal cnt = entry.getValue()[1];
            BigDecimal avg = cnt.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : sum.divide(cnt, 2, RoundingMode.HALF_UP);
            result.add(new StatDecimalResponse(entry.getKey(), avg));
        }
        // 按平均价格降序
        result.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        return result;
    }

    /**
     * 图表3: 各省总销量排行(反映旅游热度)
     * 按 city_id 聚合 SUM(销量), 再用城市映射汇总到省份
     */
    @Override
    public List<StatResponse> salesSumByProvince(SpotQueryRequest query) {
        QueryWrapper<ScenicSpot> wrapper = new QueryWrapper<>();
        wrapper.select("city_id", "SUM(sales_volume) AS sum_sales");
        fillConditions(wrapper, query);
        wrapper.groupBy("city_id");
        List<Map<String, Object>> rows = scenicSpotMapper.selectMaps(wrapper);

        Map<Integer, String> cityProvinceMap = getCityProvinceMap();
        Map<String, Long> provinceSales = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Integer cityId = ((Number) row.get("city_id")).intValue();
            String province = cityProvinceMap.get(cityId);
            if (province == null) {
                continue;
            }
            long sales = toLong(row.get("sum_sales"));
            provinceSales.merge(province, sales, Long::sum);
        }

        return provinceSales.entrySet().stream()
                .map(e -> new StatResponse(e.getKey(), e.getValue()))
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * 图表4: 等级×省份堆叠柱状图(取景点总数TOP12省份)
     * 按 city_id+spot_type 聚合, 再汇总到省份, 取TOP12避免柱子过密
     */
    @Override
    public ProvinceStackResponse provinceLevelStack(SpotQueryRequest query) {
        QueryWrapper<ScenicSpot> wrapper = new QueryWrapper<>();
        wrapper.select("city_id", "spot_type", "COUNT(*) AS cnt");
        fillConditions(wrapper, query);
        wrapper.groupBy("city_id", "spot_type");
        List<Map<String, Object>> rows = scenicSpotMapper.selectMaps(wrapper);

        Map<Integer, String> cityProvinceMap = getCityProvinceMap();
        // province → (等级 → 数量)
        Map<String, Map<String, Long>> provinceLevelMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Integer cityId = ((Number) row.get("city_id")).intValue();
            String province = cityProvinceMap.get(cityId);
            if (province == null) {
                continue;
            }
            String type = row.get("spot_type") != null ? row.get("spot_type").toString() : "未评级";
            Long cnt = toLong(row.get("cnt"));
            provinceLevelMap.computeIfAbsent(province, k -> new HashMap<>())
                    .merge(type, cnt, Long::sum);
        }

        // 计算各省景点总数, 取TOP12
        List<String> levels = Arrays.asList("5A景区", "4A景区", "3A景区", "未评级");
        List<String> provinces = provinceLevelMap.entrySet().stream()
                .map(e -> new AbstractMap.SimpleEntry<>(
                        e.getKey(),
                        e.getValue().values().stream().mapToLong(Long::longValue).sum()))
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(12)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // 构造4个等级的series, data顺序与provinces对齐
        List<ProvinceStackResponse.SeriesItem> series = new ArrayList<>();
        for (String level : levels) {
            List<Long> data = new ArrayList<>();
            for (String province : provinces) {
                Map<String, Long> levelMap = provinceLevelMap.get(province);
                data.add(levelMap != null ? levelMap.getOrDefault(level, 0L) : 0L);
            }
            series.add(new ProvinceStackResponse.SeriesItem(level, data));
        }

        return new ProvinceStackResponse(provinces, series);
    }

    /**
     * 图表5: KPI概览
     * 单条聚合SQL一次性算出全部指标, fillConditions保证在筛选范围内统计
     */
    @Override
    public KpiResponse kpiOverview(SpotQueryRequest query) {
        QueryWrapper<ScenicSpot> wrapper = new QueryWrapper<>();
        wrapper.select(
                "COUNT(*) AS total_cnt",
                "AVG(ticket_price) AS avg_price",
                "AVG(score) AS avg_score",
                "SUM(sales_volume) AS total_sales",
                "SUM(CASE WHEN ticket_price = 0 THEN 1 ELSE 0 END) AS free_cnt",
                "SUM(CASE WHEN spot_type = '5A景区' THEN 1 ELSE 0 END) AS level5a_cnt",
                "SUM(CASE WHEN spot_type IN ('4A景区','5A景区') THEN 1 ELSE 0 END) AS level4a_plus_cnt");
        fillConditions(wrapper, query);
        List<Map<String, Object>> rows = scenicSpotMapper.selectMaps(wrapper);

        KpiResponse kpi = new KpiResponse();
        // 筛选无结果时返回全0, 不返回null避免前端报错
        Map<String, Object> row = rows.isEmpty() ? Collections.emptyMap() : rows.get(0);
        kpi.setTotalCount(toLong(row.get("total_cnt")));
        kpi.setAvgPrice(toBigDecimal(row.get("avg_price")).setScale(2, RoundingMode.HALF_UP));
        kpi.setAvgScore(toBigDecimal(row.get("avg_score")).setScale(2, RoundingMode.HALF_UP));
        kpi.setTotalSales(toBigDecimal(row.get("total_sales")).setScale(0, RoundingMode.HALF_UP));
        kpi.setFreeCount(toLong(row.get("free_cnt")));
        kpi.setLevel5ACount(toLong(row.get("level5a_cnt")));
        kpi.setLevel4APlusCount(toLong(row.get("level4a_plus_cnt")));
        return kpi;
    }

    // ==================== 数据分析算法: K-Means 景点聚类 ====================

    /**
     * K-Means聚类分析(无监督学习)
     *
     * 算法思路(标准的K-Means两阶段迭代):
     * 1) 特征选取: 用"门票价格(ticket_price)"与"评分(score)"两个数值维度刻画每个景点;
     * 2) 数据归一化: 两维量纲差异大(价格0~几百, 评分0~5), 先做 Min-Max 归一化到[0,1],
     *    避免价格维度主导距离计算, 导致评分维度失效;
     * 3) 初始化: 随机选取K个样本作为初始聚类中心(centroids);
     * 4) E步(分配): 计算每个点到所有中心的欧氏距离, 划入距离最近的中心所在簇;
     * 5) M步(更新): 重新计算每个簇内样本的均值作为新中心;
     * 6) 重复4/5直到中心不再变化(或达到最大迭代次数), 算法收敛;
     * 7) 结果: 返回每个景点的簇编号、簇中心(还原为原始量纲)及各簇规模。
     *
     * 数据规模控制: 全量1.3万景点迭代开销大, 且聚类散点过密影响视觉,
     * 故随机采样 sampleSize 个点参与聚类, 分布形状基本保持不变(与散点图口径一致)。
     *
     * @param query      筛选条件(省份/类型等, 在筛选范围内聚类)
     * @param sampleSize 采样样本数(默认800)
     * @param k          聚类簇数(默认4)
     */
    @Override
    public ClusterAnalysisResponse clusterAnalysis(SpotQueryRequest query, Integer sampleSize, Integer k) {
        int sample = (sampleSize == null || sampleSize <= 0) ? 800 : Math.min(sampleSize, 5000);
        int K = (k == null || k <= 0) ? 4 : Math.max(2, Math.min(k, 8));

        // 1) 查询参与聚类的景点(只取价格/评分/名称, 随机采样)
        LambdaQueryWrapper<ScenicSpot> wrapper = buildSpotWrapper(query);
        wrapper.isNotNull(ScenicSpot::getTicketPrice)
                .isNotNull(ScenicSpot::getScore)
                .select(ScenicSpot::getTicketPrice, ScenicSpot::getScore, ScenicSpot::getSpotName)
                .last("ORDER BY RAND() LIMIT " + sample);
        List<ScenicSpot> spots = scenicSpotMapper.selectList(wrapper);
        if (spots.isEmpty()) {
            return new ClusterAnalysisResponse(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        int n = spots.size();
        // 原始特征数组
        double[] price = new double[n];
        double[] score = new double[n];
        for (int i = 0; i < n; i++) {
            price[i] = spots.get(i).getTicketPrice() == null ? 0.0 : spots.get(i).getTicketPrice().doubleValue();
            score[i] = spots.get(i).getScore() == null ? 0.0 : spots.get(i).getScore().doubleValue();
        }

        // 2) Min-Max 归一化(找到两维各自最小/最大值)
        double minPrice = Double.MAX_VALUE, maxPrice = -Double.MAX_VALUE;
        double minScore = Double.MAX_VALUE, maxScore = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            minPrice = Math.min(minPrice, price[i]);
            maxPrice = Math.max(maxPrice, price[i]);
            minScore = Math.min(minScore, score[i]);
            maxScore = Math.max(maxScore, score[i]);
        }
        // 防止某维全相等导致分母为0
        double priceSpan = maxPrice - minPrice;
        double scoreSpan = maxScore - minScore;
        double[][] norm = new double[n][2]; // 归一化后的特征(价格,评分)
        for (int i = 0; i < n; i++) {
            norm[i][0] = priceSpan == 0 ? 0.5 : (price[i] - minPrice) / priceSpan;
            norm[i][1] = scoreSpan == 0 ? 0.5 : (score[i] - minScore) / scoreSpan;
        }

        // 3) 随机初始化K个聚类中心(直接从样本里挑K个不同的点)
        List<Integer> initIdx = new ArrayList<>();
        Random rand = new Random();
        while (initIdx.size() < Math.min(K, n)) {
            int idx = rand.nextInt(n);
            if (!initIdx.contains(idx)) {
                initIdx.add(idx);
            }
        }
        double[][] centroids = new double[initIdx.size()][2];
        for (int c = 0; c < initIdx.size(); c++) {
            int idx = initIdx.get(c);
            centroids[c][0] = norm[idx][0];
            centroids[c][1] = norm[idx][1];
        }
        int actualK = initIdx.size();

        // 4) 迭代: E步(分配) + M步(更新中心)
        int[] assignment = new int[n];
        for (int iter = 0; iter < 100; iter++) {
            boolean changed = false;
            // E步: 每个样本划入最近的中心
            for (int i = 0; i < n; i++) {
                int best = 0;
                double bestDist = Double.MAX_VALUE;
                for (int c = 0; c < actualK; c++) {
                    double dx = norm[i][0] - centroids[c][0];
                    double dy = norm[i][1] - centroids[c][1];
                    double d = dx * dx + dy * dy;
                    if (d < bestDist) {
                        bestDist = d;
                        best = c;
                    }
                }
                if (assignment[i] != best) {
                    assignment[i] = best;
                    changed = true;
                }
            }
            if (!changed) {
                break; // 分配不再变化, 已收敛
            }
            // M步: 重新计算每个簇的均值中心
            double[][] sum = new double[actualK][2];
            int[] cnt = new int[actualK];
            for (int i = 0; i < n; i++) {
                int c = assignment[i];
                sum[c][0] += norm[i][0];
                sum[c][1] += norm[i][1];
                cnt[c]++;
            }
            for (int c = 0; c < actualK; c++) {
                if (cnt[c] > 0) {
                    centroids[c][0] = sum[c][0] / cnt[c];
                    centroids[c][1] = sum[c][1] / cnt[c];
                }
            }
        }

        // 5) 组装结果
        List<ClusterAnalysisResponse.Point> points = new ArrayList<>();
        int[] sizes = new int[actualK];
        for (int i = 0; i < n; i++) {
            int c = assignment[i];
            sizes[c]++;
            points.add(new ClusterAnalysisResponse.Point(
                    spots.get(i).getSpotName(),
                    price[i], score[i], c));
        }

        // 中心点还原为原始量纲, 并按"价格×评分"大致命名簇(便于大屏图例理解)
        List<ClusterAnalysisResponse.Centroid> centroidList = new ArrayList<>();
        for (int c = 0; c < actualK; c++) {
            double centerPrice = priceSpan == 0 ? minPrice : minPrice + centroids[c][0] * priceSpan;
            double centerScore = scoreSpan == 0 ? minScore : minScore + centroids[c][1] * scoreSpan;
            centroidList.add(new ClusterAnalysisResponse.Centroid(
                    c,
                    describeCluster(centerPrice, centerScore),
                    Math.round(centerPrice * 100) / 100.0,
                    Math.round(centerScore * 100) / 100.0));
        }
        // 按簇规模从大到小排序返回, 前端据此给颜色
        List<Integer> sizeList = new ArrayList<>();
        for (int c = 0; c < actualK; c++) {
            sizeList.add(sizes[c]);
        }
        return new ClusterAnalysisResponse(points, centroidList, sizeList);
    }

    /**
     * 根据簇中心(价格,评分)给出业务语义描述, 便于答辩/展示解释聚类含义
     */
    private String describeCluster(double price, double score) {
        StringBuilder sb = new StringBuilder();
        sb.append(score >= 4.2 ? "高评分" : score >= 3.5 ? "中评分" : "低评分");
        sb.append("·");
        sb.append(price >= 150 ? "高消费" : price >= 50 ? "中档消费" : "实惠");
        return sb.toString();
    }
}
