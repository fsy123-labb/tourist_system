package com.tourism.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tourism.dto.response.RecommendItemResponse;
import com.tourism.dto.response.SimilarityGraphResponse;
import com.tourism.entity.ScenicCity;
import com.tourism.entity.ScenicSpot;
import com.tourism.entity.UserBehavior;
import com.tourism.mapper.ScenicCityMapper;
import com.tourism.mapper.ScenicSpotMapper;
import com.tourism.mapper.UserBehaviorMapper;
import com.tourism.service.RecommendService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 推荐Service实现类
 *
 * 算法组成:
 * 1. 基于内容推荐(Content-Based): 景点特征向量(等级+价格+评分)余弦相似度 + 介绍文本Jaccard相似度 + 同省份加权
 * 2. 协同过滤(Collaborative Filtering): 物品-物品CF, 基于用户行为计算景点共现相似度
 * 3. 混合推荐: finalSim = 0.6*内容相似度 + 0.4*CF相似度, 无行为数据时退化为纯内容推荐
 * 4. 冷启动: 新用户无行为记录时, 退化为热门推荐(高评分+高销量)
 */
@Service
public class RecommendServiceImpl implements RecommendService {

    @Autowired
    private ScenicSpotMapper scenicSpotMapper;

    @Autowired
    private ScenicCityMapper scenicCityMapper;

    @Autowired
    private UserBehaviorMapper userBehaviorMapper;

    /**
     * 混合推荐中内容推荐的权重
     */
    private static final double CONTENT_WEIGHT = 0.6;

    /**
     * 混合推荐中协同过滤的权重
     */
    private static final double CF_WEIGHT = 0.4;

    /**
     * 内容相似度内部各子项权重: 特征向量余弦 / 同省份 / 文本Jaccard
     */
    private static final double W_FEATURE = 0.6;
    private static final double W_PROVINCE = 0.2;
    private static final double W_TEXT = 0.2;

    // ==================== 行为采集 ====================

    /**
     * 记录用户行为(浏览/收藏)
     * 使用唯一索引(user_id, spot_id, behavior_type)防重复, 已存在则忽略
     */
    @Override
    public void recordBehavior(Integer userId, Integer spotId, Integer behaviorType) {
        // 先查是否已存在相同行为(避免唯一约束异常)
        QueryWrapper<UserBehavior> qw = new QueryWrapper<>();
        qw.eq("user_id", userId)
                .eq("spot_id", spotId)
                .eq("behavior_type", behaviorType);
        Long cnt = userBehaviorMapper.selectCount(qw);
        if (cnt != null && cnt > 0) {
            return;
        }
        UserBehavior behavior = new UserBehavior();
        behavior.setUserId(userId);
        behavior.setSpotId(spotId);
        behavior.setBehaviorType(behaviorType);
        userBehaviorMapper.insert(behavior);
    }

    // ==================== 基于内容的相似推荐 + 协同过滤混合 ====================

    /**
     * 相似景点推荐(混合: 内容相似度 + 协同过滤)
     */
    @Override
    public List<RecommendItemResponse> similarSpots(Integer spotId, Integer topN) {
        // 一次性加载全部数据并预计算特征/分词(只做一次全表扫描)
        return computeSimilarFromContext(spotId, topN, loadContext());
    }

    // ==================== 猜你喜欢(用户个性化推荐) ====================

    /**
     * 猜你喜欢: 基于用户历史浏览, 融合内容+CF推荐
     * 冷启动(无浏览记录): 退化为热门推荐
     *
     * 性能优化: 对用户的所有浏览景点共享同一份预计算数据(景点特征/分词/城市映射/CF数据),
     * 只做一次全表扫描, 而不是每个浏览景点都重新加载全量表(避免 10×1.3万 的重复计算)。
     */
    @Override
    public List<RecommendItemResponse> guessYouLike(Integer userId, Integer topN) {
        // 1. 查用户浏览过的景点
        QueryWrapper<UserBehavior> qw = new QueryWrapper<>();
        qw.eq("user_id", userId).eq("behavior_type", 1)
                .orderByDesc("create_time").last("LIMIT 10");
        List<UserBehavior> behaviors = userBehaviorMapper.selectList(qw);

        // 冷启动: 无浏览记录, 返回热门推荐(高评分+高销量)
        if (behaviors.isEmpty()) {
            return hotRecommended(topN);
        }

        // 2. 一次性加载全部推荐数据并预计算特征/分词, 供所有浏览景点复用
        RecommendContext ctx = loadContext();

        // 3. 对用户浏览过的每个景点, 取其相似TOP20候选, 聚合打分
        // candidateId -> 累计推荐分
        Map<Integer, Double> candidateScores = new HashMap<>();
        Set<Integer> viewedSpotIds = new HashSet<>();
        for (UserBehavior b : behaviors) {
            viewedSpotIds.add(b.getSpotId());
            // 复用预计算上下文, 取每个浏览景点的TOP20相似
            List<RecommendItemResponse> simList = computeSimilarFromContext(b.getSpotId(), 20, ctx);
            for (RecommendItemResponse sim : simList) {
                // 排除已浏览的
                if (viewedSpotIds.contains(sim.getId())) {
                    continue;
                }
                // 累加相似度作为推荐分(被多个浏览景点推荐则加分)
                candidateScores.merge(sim.getId(), sim.getSimilarity(), Double::sum);
            }
        }

        // 4. 按累计推荐分排序, 取TOP N, 并用已缓存数据补全景点信息
        List<Map.Entry<Integer, Double>> sorted = candidateScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topN)
                .collect(Collectors.toList());

        List<RecommendItemResponse> result = new ArrayList<>();
        for (Map.Entry<Integer, Double> entry : sorted) {
            ScenicSpot spot = ctx.spotMap.get(entry.getKey());
            if (spot != null) {
                result.add(toRecommendItem(spot, ctx.cityMap, entry.getValue()));
            }
        }

        // 候选不足时用热门景点补齐
        if (result.size() < topN) {
            Set<Integer> existIds = result.stream()
                    .map(RecommendItemResponse::getId).collect(Collectors.toSet());
            existIds.addAll(viewedSpotIds);
            List<RecommendItemResponse> hot = hotRecommended(topN);
            for (RecommendItemResponse h : hot) {
                if (result.size() >= topN) break;
                if (!existIds.contains(h.getId())) {
                    result.add(h);
                    existIds.add(h.getId());
                }
            }
        }
        return result;
    }

    // ==================== 推荐热度榜 ====================

    /**
     * 推荐热度榜: 按用户行为次数(浏览+收藏)降序
     */
    @Override
    public List<RecommendItemResponse> hotRecommended(Integer topN) {
        // 行为表按spot_id聚合计数, 降序取TOP N
        QueryWrapper<UserBehavior> qw = new QueryWrapper<>();
        qw.select("spot_id", "COUNT(*) AS cnt")
                .groupBy("spot_id")
                .orderByDesc("cnt")
                .last("LIMIT " + topN);
        List<Map<String, Object>> rows = userBehaviorMapper.selectMaps(qw);

        // 若无行为数据, 退化为景点自身热度(评分*0.5 + 销量归一化*0.5)
        if (rows.isEmpty()) {
            return hotBySpotData(topN);
        }

        Map<Integer, String[]> cityMap = loadCityMap();
        List<RecommendItemResponse> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Integer spotId = ((Number) row.get("spot_id")).intValue();
            Long cnt = ((Number) row.get("cnt")).longValue();
            ScenicSpot spot = scenicSpotMapper.selectById(spotId);
            if (spot != null) {
                RecommendItemResponse item = toRecommendItem(spot, cityMap, cnt.doubleValue());
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 无行为数据时的热度兜底: 用景点自身评分+销量计算热度分
     */
    private List<RecommendItemResponse> hotBySpotData(Integer topN) {
        List<ScenicSpot> allSpots = scenicSpotMapper.selectList(null);
        Map<Integer, String[]> cityMap = loadCityMap();
        // 找最大销量用于归一化
        double maxSales = allSpots.stream()
                .mapToDouble(s -> s.getSalesVolume() == null ? 0 : s.getSalesVolume().doubleValue())
                .max().orElse(1);

        List<RecommendItemResponse> result = new ArrayList<>();
        for (ScenicSpot spot : allSpots) {
            double score = spot.getScore() == null ? 0 : spot.getScore().doubleValue();
            double sales = spot.getSalesVolume() == null ? 0 : spot.getSalesVolume().doubleValue();
            // 热度分 = 评分(0~5)归一化*0.5 + 销量归一化*0.5, 最终映射到0~5区间
            double hotScore = (score / 5.0) * 0.5 + (sales / maxSales) * 0.5;
            result.add(toRecommendItem(spot, cityMap, hotScore * 5));
        }
        result.sort((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()));
        if (result.size() > topN) {
            result = result.subList(0, topN);
        }
        return result;
    }

    // ==================== 浏览历史 ====================

    /**
     * 查询用户浏览历史: 查behavior_type=1, 按create_time倒序, 对相同景点去重保留最近时间
     */
    @Override
    public List<RecommendItemResponse> browseHistory(Integer userId, Integer topN) {
        LambdaQueryWrapper<UserBehavior> wrapper = new LambdaQueryWrapper<UserBehavior>()
                .eq(UserBehavior::getUserId, userId)
                .eq(UserBehavior::getBehaviorType, 1)
                .orderByDesc(UserBehavior::getCreateTime);
        List<UserBehavior> allBehaviors = userBehaviorMapper.selectList(wrapper);
        if (allBehaviors.isEmpty()) {
            return Collections.emptyList();
        }
        // 按spotId去重(保留首次出现=最近时间)
        Map<Integer, UserBehavior> uniqueMap = new LinkedHashMap<>();
        for (UserBehavior b : allBehaviors) {
            uniqueMap.putIfAbsent(b.getSpotId(), b);
            if (uniqueMap.size() >= topN) break;
        }
        // 批量查景点
        List<Integer> spotIds = new ArrayList<>(uniqueMap.keySet());
        List<ScenicSpot> spots = scenicSpotMapper.selectBatchIds(spotIds);
        Map<Integer, ScenicSpot> spotMap = spots.stream()
                .collect(Collectors.toMap(ScenicSpot::getId, s -> s));
        Map<Integer, String[]> cityMap = loadCityMap();
        List<RecommendItemResponse> result = new ArrayList<>();
        for (Integer spotId : spotIds) {
            ScenicSpot spot = spotMap.get(spotId);
            if (spot != null) {
                result.add(toRecommendItem(spot, cityMap, 0));
            }
        }
        return result;
    }

    // ==================== 相似度网络图 ====================

    /**
     * 相似度网络图: 中心景点 + TOP N相似景点, 生成ECharts graph结构
     */
    @Override
    public SimilarityGraphResponse similarityGraph(Integer spotId, Integer topN) {
        // 复用similarSpots拿到相似景点
        List<RecommendItemResponse> simList = similarSpots(spotId, topN);
        ScenicSpot center = scenicSpotMapper.selectById(spotId);
        if (center == null) {
            return new SimilarityGraphResponse(Collections.emptyList(), Collections.emptyList());
        }

        List<SimilarityGraphResponse.Node> nodes = new ArrayList<>();
        List<SimilarityGraphResponse.Edge> edges = new ArrayList<>();

        // 中心节点(分类0, 较大)
        nodes.add(new SimilarityGraphResponse.Node(center.getSpotName(), 50, 0));
        // 相似节点(分类1, 较小)
        for (RecommendItemResponse sim : simList) {
            nodes.add(new SimilarityGraphResponse.Node(sim.getSpotName(), 25, 1));
            // 边: 中心 -> 相似, 粗细=相似度*10
            edges.add(new SimilarityGraphResponse.Edge(
                    center.getSpotName(), sim.getSpotName(), sim.getSimilarity()));
        }
        return new SimilarityGraphResponse(nodes, edges);
    }

    // ==================== 算法核心: 相似度计算 ====================

    /**
     * 推荐上下文: 一次性加载所有景点及预计算数据, 供多次相似计算复用
     * 避免在"猜你喜欢"中对每个浏览景点都重新全表扫描+重复分词, 大幅降低耗时
     */
    private static class RecommendContext {
        List<ScenicSpot> allSpots;
        Map<Integer, ScenicSpot> spotMap;
        Map<Integer, String[]> cityMap;
        Map<Integer, Set<Integer>> spotUsersMap;
        // 预计算缓存: spotId -> 特征向量 / 分词结果(全流程只算一遍)
        Map<Integer, double[]> vecCache;
        Map<Integer, Set<String>> tokCache;
    }

    /**
     * 加载并预计算推荐上下文(只做一次DB全表查询 + 一次特征/分词计算)
     */
    private RecommendContext loadContext() {
        RecommendContext ctx = new RecommendContext();
        ctx.allSpots = scenicSpotMapper.selectList(null);
        ctx.cityMap = loadCityMap();
        ctx.spotUsersMap = loadSpotUsersMap();
        ctx.spotMap = new HashMap<>();
        ctx.vecCache = new HashMap<>();
        ctx.tokCache = new HashMap<>();
        for (ScenicSpot s : ctx.allSpots) {
            ctx.spotMap.put(s.getId(), s);
            ctx.vecCache.put(s.getId(), buildFeatureVector(s));
            ctx.tokCache.put(s.getId(), tokenize(s.getSpotIntro()));
        }
        return ctx;
    }

    /**
     * 基于共享上下文计算某景点的相似景点TOP N(混合内容+CF), 算法与内容与重构前完全一致
     */
    private List<RecommendItemResponse> computeSimilarFromContext(Integer spotId, Integer topN, RecommendContext ctx) {
        ScenicSpot target = ctx.spotMap.get(spotId);
        if (target == null) {
            return Collections.emptyList();
        }
        // 直接取预计算的目标景点特征/分词/省份
        double[] targetVec = ctx.vecCache.get(spotId);
        Set<String> targetTokens = ctx.tokCache.get(spotId);
        String targetProvince = getProvince(target.getCityId(), ctx.cityMap);
        Set<Integer> targetUsers = ctx.spotUsersMap.getOrDefault(spotId, Collections.emptySet());

        // 遍历全量景点, 计算混合相似度(特征/分词均命中缓存, 不再重复计算)
        List<RecommendItemResponse> result = new ArrayList<>();
        for (ScenicSpot candidate : ctx.allSpots) {
            if (candidate.getId().equals(spotId)) {
                continue;
            }
            double contentSim = contextContentSim(candidate, targetVec, targetTokens,
                    targetProvince, ctx);
            double cfSim = cfSimilarity(targetUsers, ctx.spotUsersMap.get(candidate.getId()));
            double finalSim = CONTENT_WEIGHT * contentSim + CF_WEIGHT * cfSim;
            if (finalSim > 0) {
                result.add(toRecommendItem(candidate, ctx.cityMap, finalSim));
            }
        }

        result.sort((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()));
        if (result.size() > topN) {
            result = result.subList(0, topN);
        }
        return result;
    }

    /**
     * 内容相似度 = W_FEATURE*特征余弦 + W_PROVINCE*同省份 + W_TEXT*文本Jaccard
     * 特征向量与分词结果直接取上下文缓存
     */
    private double contextContentSim(ScenicSpot candidate, double[] targetVec,
                                     Set<String> targetTokens, String targetProvince,
                                     RecommendContext ctx) {
        double featureSim = cosine(ctx.vecCache.get(candidate.getId()), targetVec);
        double provinceSim = targetProvince.equals(getProvince(candidate.getCityId(), ctx.cityMap)) ? 1.0 : 0.0;
        double textSim = jaccard(ctx.tokCache.get(candidate.getId()), targetTokens);
        return W_FEATURE * featureSim + W_PROVINCE * provinceSim + W_TEXT * textSim;
    }

    /**
     * 协同过滤相似度(物品-物品): 两景点被同一用户浏览过的比例
     * CF_sim = |交集用户| / sqrt(|A用户数| * |B用户数|), 无行为数据返回0
     */
    private double cfSimilarity(Set<Integer> usersA, Set<Integer> usersB) {
        if (usersA == null || usersB == null || usersA.isEmpty() || usersB.isEmpty()) {
            return 0.0;
        }
        // 计算交集
        Set<Integer> intersection = new HashSet<>(usersA);
        intersection.retainAll(usersB);
        if (intersection.isEmpty()) {
            return 0.0;
        }
        // 归一化余弦相似度
        return intersection.size() / (Math.sqrt(usersA.size()) * Math.sqrt(usersB.size()));
    }

    /**
     * 构造景点特征向量(14维):
     * 等级one-hot(4) + 价格分桶one-hot(5) + 评分分桶one-hot(5)
     */
    private double[] buildFeatureVector(ScenicSpot spot) {
        double[] v = new double[14];
        // 等级 4维 (0~3)
        String type = spot.getSpotType();
        if ("5A景区".equals(type)) v[0] = 1;
        else if ("4A景区".equals(type)) v[1] = 1;
        else if ("3A景区".equals(type)) v[2] = 1;
        else v[3] = 1;
        // 价格 5维 (4~8)
        double price = spot.getTicketPrice() == null ? 0 : spot.getTicketPrice().doubleValue();
        if (price == 0) v[4] = 1;
        else if (price < 50) v[5] = 1;
        else if (price < 100) v[6] = 1;
        else if (price < 200) v[7] = 1;
        else v[8] = 1;
        // 评分 5维 (9~13)
        double score = spot.getScore() == null ? 0 : spot.getScore().doubleValue();
        if (score == 0) v[9] = 1;
        else if (score < 3) v[10] = 1;
        else if (score < 4) v[11] = 1;
        else if (score < 4.5) v[12] = 1;
        else v[13] = 1;
        return v;
    }

    /**
     * 余弦相似度: cos(A,B) = A·B / (|A| * |B|)
     */
    private double cosine(double[] a, double[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 中文文本分词(2字滑动窗口, 无需分词库)
     * 例: "故宫博物院" -> {故宫, 宫博, 博物, 物院}
     */
    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.length() < 2) {
            return tokens;
        }
        for (int i = 0; i < text.length() - 1; i++) {
            tokens.add(text.substring(i, i + 2));
        }
        return tokens;
    }

    /**
     * Jaccard相似度: |交集| / |并集|
     */
    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    // ==================== 工具方法 ====================

    /**
     * 加载城市映射: cityId -> [province, city]
     */
    private Map<Integer, String[]> loadCityMap() {
        Map<Integer, String[]> map = new HashMap<>();
        for (ScenicCity city : scenicCityMapper.selectList(null)) {
            map.put(city.getId(), new String[]{city.getProvince(), city.getCity()});
        }
        return map;
    }

    /**
     * 根据cityId获取省份
     */
    private String getProvince(Integer cityId, Map<Integer, String[]> cityMap) {
        String[] info = cityMap.get(cityId);
        return info == null ? "" : info[0];
    }

    /**
     * 加载景点->用户集合映射(用于协同过滤), spotId -> 浏览过该景点的userId集合
     */
    private Map<Integer, Set<Integer>> loadSpotUsersMap() {
        // 只查浏览行为(behavior_type=1)
        QueryWrapper<UserBehavior> qw = new QueryWrapper<>();
        qw.eq("behavior_type", 1);
        List<UserBehavior> behaviors = userBehaviorMapper.selectList(qw);
        Map<Integer, Set<Integer>> map = new HashMap<>();
        for (UserBehavior b : behaviors) {
            map.computeIfAbsent(b.getSpotId(), k -> new HashSet<>()).add(b.getUserId());
        }
        return map;
    }

    /**
     * ScenicSpot -> RecommendItemResponse 转换, 补充省份/城市/相似度
     */
    private RecommendItemResponse toRecommendItem(ScenicSpot spot, Map<Integer, String[]> cityMap, double similarity) {
        RecommendItemResponse item = new RecommendItemResponse();
        item.setId(spot.getId());
        item.setSpotName(spot.getSpotName());
        item.setSpotType(spot.getSpotType());
        item.setScore(spot.getScore());
        item.setTicketPrice(spot.getTicketPrice());
        String[] info = cityMap.get(spot.getCityId());
        item.setProvince(info == null ? "" : info[0]);
        item.setCity(info == null ? "" : info[1]);
        item.setAddress(spot.getAddress());
        item.setSpotIntro(spot.getSpotIntro());
        // 相似度保留4位小数
        item.setSimilarity(BigDecimal.valueOf(similarity)
                .setScale(4, RoundingMode.HALF_UP).doubleValue());
        return item;
    }
}
