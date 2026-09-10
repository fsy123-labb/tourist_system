package com.tourism.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tourism.entity.ScenicCity;
import com.tourism.entity.ScenicSpot;
import com.tourism.mapper.ScenicCityMapper;
import com.tourism.mapper.ScenicSpotMapper;
import com.tourism.service.AiService;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI旅游助手实现类
 * 思路(轻量RAG):
 * 1. 从数据库查出热门景点(TOP20), 拼进系统提示词, 让大模型基于"真实数据"回答
 * 2. 调用智谱GLM-4-Flash(OpenAI兼容协议, stream=true)获得SSE流
 * 3. 解析上游每个增量块, 只提取delta.content, 重新包装成SSE事件推给前端
 */
@Slf4j
@Service
public class AiServiceImpl implements AiService {

    @Value("${ai.api-key:}")
    private String apiKey;

    @Value("${ai.api-url:https://open.bigmodel.cn/api/paas/v4/chat/completions}")
    private String apiUrl;

    @Value("${ai.model:glm-4-flash}")
    private String model;

    @Autowired
    private ScenicSpotMapper scenicSpotMapper;

    @Autowired
    private ScenicCityMapper scenicCityMapper;

    /**
     * 流式对话主流程
     */
    @Override
    public void chatStream(String message, List<Map<String, String>> history, HttpServletResponse response) {
        try {
            // 0.统一设置SSE响应头(必须在写任何内容之前, 否则中文会按ISO-8859-1编码乱码)
            response.setContentType("text/event-stream;charset=UTF-8");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("X-Accel-Buffering", "no");

            // 1.校验API Key是否已配置
            if (apiKey == null || apiKey.isBlank() || apiKey.contains("粘贴")) {
                sendSseEvent(response, "AI服务尚未配置API Key, 请在application.yml的ai.api-key中填入智谱APIKey后重启后端");
                sendSseDone(response);
                return;
            }

            // 2.组装对话消息: 系统提示词(含景点数据) + 历史对话 + 本次提问
            List<Map<String, String>> messages = buildMessages(message, history);

            // 3.构造OpenAI兼容协议请求体
            JSONObject reqBody = new JSONObject();
            reqBody.put("model", model);
            reqBody.put("messages", messages);
            reqBody.put("stream", true);
            reqBody.put("temperature", 0.7);
            reqBody.put("max_tokens", 2048);

            // 4.调用智谱API(JDK17 HttpClient, 流式读取响应)
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(reqBody.toJSONString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> upstream =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            // 5.上游返回非200, 读取错误信息通知前端
            if (upstream.statusCode() != 200) {
                String errorBody = new BufferedReader(
                        new InputStreamReader(upstream.body(), StandardCharsets.UTF_8))
                        .lines().reduce("", (a, b) -> a + b);
                log.error("智谱API调用失败: status={}, body={}", upstream.statusCode(), errorBody);
                sendSseEvent(response, "AI服务调用失败(" + upstream.statusCode() + "), 请稍后再试");
                sendSseDone(response);
                return;
            }

            // 6.逐行解析上游SSE, 把delta.content实时推给前端
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(upstream.body(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                // SSE格式: "data: {...}", 只处理data开头的行
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                // [DONE]表示流结束
                if ("[DONE]".equals(payload)) {
                    break;
                }
                JSONObject chunk = JSON.parseObject(payload);
                JSONArray choices = chunk.getJSONArray("choices");
                if (choices == null || choices.isEmpty()) {
                    continue;
                }
                JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                if (delta == null) {
                    continue;
                }
                String content = delta.getString("content");
                if (content != null && !content.isEmpty()) {
                    sendSseEvent(response, JSON.toJSONString(Map.of("content", content)));
                }
            }
            reader.close();
            sendSseDone(response);
        } catch (Exception e) {
            log.error("AI对话异常", e);
            try {
                sendSseEvent(response, "AI助手开小差了, 请稍后再试");
                sendSseDone(response);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 组装对话消息: 系统提示词(注入丰富景点+城市数据) + 历史对话 + 本次提问
     */
    private List<Map<String, String>> buildMessages(String message, List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt()));

        // 保留前端传来的最近几轮对话(最多6条), 维持上下文
        if (history != null) {
            int from = Math.max(0, history.size() - 6);
            for (int i = from; i < history.size(); i++) {
                Map<String, String> h = history.get(i);
                String role = "assistant".equals(h.get("role")) ? "assistant" : "user";
                String content = h.get("content");
                if (content != null && !content.isBlank()) {
                    messages.add(Map.of("role", role, "content", content));
                }
            }
        }
        messages.add(Map.of("role", "user", "content", message));
        return messages;
    }

    /**
     * 系统提示词: 定义助手角色 + 注入多维度数据库数据(深度RAG) + 思考过程规范
     */
    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();

        // ═══════════════════════════════════════════════════════════
        // 第一部分：角色定义与行为准则
        // ═══════════════════════════════════════════════════════════
        sb.append("# 🤖 角色定义\n\n");
        sb.append("你是**\"全国旅游景点信息管理系统\"的AI旅游助手小旅**，由智谱GLM-4-Flash驱动。\n\n");
        sb.append("## 核心定位\n");
        sb.append("- 你是这个系统的智能窗口，所有回答必须**基于系统数据库中的真实数据**\n");
        sb.append("- 你服务的用户是这个系统的管理员和普通游客\n");
        sb.append("- 你的数据来源于项目内置的景点数据库，共 **678个景点**，覆盖 **225个城市**\n\n");

        // ═══════════════════════════════════════════════════════════
        // 第二部分：严格的数据边界规则
        // ═══════════════════════════════════════════════════════════
        sb.append("---\n\n");
        sb.append("## ⚠️ 数据边界规则（严格执行）\n\n");
        sb.append("1. **你必须以下面\"【系统数据库】\"部分提供的真实数据为唯一依据回答问题**\n");
        sb.append("2. **严禁编造、推测或引入数据库之外的景点名称、价格、评分等具体信息**\n");
        sb.append("3. **对于数据库中不存在的景点，明确告知用户\"该景点不在系统数据范围内\"**\n");
        sb.append("4. **可以结合通用旅游常识做宏观建议（如出行季节、打包提示），但必须标注\"这是通用建议，非系统数据\"**\n");
        sb.append("5. **所有涉及具体数值（门票、评分、销量等）的回答，必须与数据库完全一致**\n\n");

        // ═══════════════════════════════════════════════════════════
        // 第三部分：回答格式规范
        // ═══════════════════════════════════════════════════════════
        sb.append("---\n\n");
        sb.append("## 📝 回答格式规范\n\n");
        sb.append("你的回答分为**两个部分**，必须严格按以下结构输出：\n\n");
        sb.append("### 第一部分：思考过程（以固定标记开头）\n\n");
        sb.append("在给出正式回答之前，你必须先输出思考过程，格式如下：\n\n");
        sb.append("```\n");
        sb.append("### 💭 思考中...\n\n");
        sb.append("- **理解问题**：用户问的是...\n");
        sb.append("- **数据检索**：我需要从数据库中查找...\n");
        sb.append("- **分析推理**：根据数据，我发现...\n");
        sb.append("- **生成回答**：我将用表格/列表/要点来回答...\n");
        sb.append("```\n\n");
        sb.append("思考过程输出完毕后，紧接着输出正式回答（不含思考标记）。\n\n");
        sb.append("### 第二部分：正式回答（Markdown格式）\n\n");
        sb.append("- 使用 `#` / `##` / `###` 标题分层组织内容\n");
        sb.append("- 推荐类问题用 **有序列表** 或 **表格** 展示\n");
        sb.append("- 关键信息（如价格、评分、星级）用 **加粗** 强调\n");
        sb.append("- 重要提示用 `>` 引用块\n");
        sb.append("- 涉及数据统计时用 Markdown 表格\n");
        sb.append("- 总字数控制在 **300-500字**\n\n");

        // ═══════════════════════════════════════════════════════════
        // 第四部分：系统数据库 - 全量景点数据
        // ═══════════════════════════════════════════════════════════
        sb.append("---\n\n");
        sb.append("## 📊 系统数据库全景（共678个景点，225个城市）\n\n");

        // 4.1 按星级统计
        sb.append("### 景点星级分布\n\n");
        sb.append("| 星级 | 数量 | 说明 |\n| --- | --- | --- |\n");
        LambdaQueryWrapper<ScenicSpot> starWrapper = new LambdaQueryWrapper<>();
        starWrapper.select(ScenicSpot::getStarLevel)
                .isNotNull(ScenicSpot::getStarLevel);
        List<ScenicSpot> allSpotsWithStar = scenicSpotMapper.selectList(starWrapper);
        Map<Integer, Long> starCount = allSpotsWithStar.stream()
                .filter(s -> s.getStarLevel() != null)
                .collect(Collectors.groupingBy(ScenicSpot::getStarLevel, Collectors.counting()));
        starCount.forEach((star, count) ->
                sb.append("| ").append(star).append("★").append(" | ").append(count).append(" | ")
                        .append(star >= 4 ? "高等级景区" : star >= 3 ? "中等级景区" : "普通景区").append(" |\n"));
        sb.append("\n");

        // 4.2 热门景点 TOP30（含简介）
        sb.append("### 🔥 热门景点 TOP30（按销量排序，含景点简介）\n\n");
        sb.append("| # | 景点名称 | 类型 | 星级 | 评分 | 门票(元) | 销量 | 简介 |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        LambdaQueryWrapper<ScenicSpot> hotWrapper = new LambdaQueryWrapper<>();
        hotWrapper.select(ScenicSpot::getSpotName, ScenicSpot::getSpotType, ScenicSpot::getStarLevel,
                        ScenicSpot::getScore, ScenicSpot::getTicketPrice, ScenicSpot::getSalesVolume,
                        ScenicSpot::getSpotIntro, ScenicSpot::getAddress)
                .orderByDesc(ScenicSpot::getSalesVolume)
                .last("LIMIT 30");
        List<ScenicSpot> hotSpots = scenicSpotMapper.selectList(hotWrapper);
        for (int i = 0; i < hotSpots.size(); i++) {
            ScenicSpot s = hotSpots.get(i);
            String name = s.getSpotName() != null ? s.getSpotName() : "";
            String type = s.getSpotType() != null ? s.getSpotType() : "";
            String star = s.getStarLevel() != null ? s.getStarLevel() + "★" : "-";
            String score = s.getScore() != null ? s.getScore().toString() : "-";
            String price = s.getTicketPrice() != null ? s.getTicketPrice().toString() : "-";
            String sales = s.getSalesVolume() != null ? s.getSalesVolume().toPlainString() : "-";
            String intro = s.getSpotIntro() != null ? s.getSpotIntro() : "";
            if (intro.length() > 35) intro = intro.substring(0, 35) + "...";
            sb.append("| ").append(i + 1).append(" | ").append(name)
                    .append(" | ").append(type).append(" | ").append(star)
                    .append(" | ").append(score).append(" | ").append(price)
                    .append(" | ").append(sales).append(" | ").append(intro)
                    .append(" |\n");
        }

        // 4.3 各城市景点数量（TOP15）
        sb.append("\n### 🏙️ 各城市景点数量 TOP15\n\n");
        sb.append("| 省份 | 城市 | 景点数 |\n| --- | --- | --- |\n");
        LambdaQueryWrapper<ScenicSpot> cityWrapper = new LambdaQueryWrapper<>();
        cityWrapper.select(ScenicSpot::getCityId);
        List<ScenicSpot> allSpots = scenicSpotMapper.selectList(cityWrapper);
        Map<Integer, Long> cityCount = allSpots.stream()
                .filter(s -> s.getCityId() != null)
                .collect(Collectors.groupingBy(ScenicSpot::getCityId, Collectors.counting()));

        List<ScenicCity> allCities = scenicCityMapper.selectList(new LambdaQueryWrapper<>());
        Map<Integer, ScenicCity> cityMap = new HashMap<>();
        for (ScenicCity c : allCities) {
            cityMap.put(c.getId(), c);
        }

        cityCount.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(15)
                .forEach(entry -> {
                    ScenicCity city = cityMap.get(entry.getKey());
                    if (city != null) {
                        String province = city.getProvince() != null ? city.getProvince() : "";
                        String cityName = city.getCity() != null ? city.getCity() : "";
                        sb.append("| ").append(province).append(" | ").append(cityName)
                                .append(" | ").append(entry.getValue()).append(" |\n");
                    }
                });

        // 4.4 免费景点（门票=0）
        sb.append("\n### 🎁 免费景点（门票为0，共").append(
                (long) allSpots.stream().filter(s -> s.getTicketPrice() != null && s.getTicketPrice().compareTo(java.math.BigDecimal.ZERO) == 0).collect(Collectors.counting())
        ).append("个）\n\n");
        sb.append("| 景点名称 | 类型 | 星级 | 评分 | 地址 |\n| --- | --- | --- | --- | --- |\n");
        List<ScenicSpot> freeSpots = allSpots.stream()
                .filter(s -> s.getTicketPrice() != null && s.getTicketPrice().compareTo(java.math.BigDecimal.ZERO) == 0)
                .limit(15)
                .collect(Collectors.toList());
        for (ScenicSpot s : freeSpots) {
            String name = s.getSpotName() != null ? s.getSpotName() : "";
            String type = s.getSpotType() != null ? s.getSpotType() : "";
            String star = s.getStarLevel() != null ? s.getStarLevel() + "★" : "-";
            String score = s.getScore() != null ? s.getScore().toString() : "-";
            String addr = s.getAddress() != null ? s.getAddress() : "";
            if (addr.length() > 22) addr = addr.substring(0, 22) + "…";
            sb.append("| ").append(name).append(" | ").append(type)
                    .append(" | ").append(star).append(" | ").append(score)
                    .append(" | ").append(addr).append(" |\n");
        }

        // 4.5 最高评分景点 TOP10
        sb.append("\n### ⭐ 评分最高景点 TOP10\n\n");
        sb.append("| 排名 | 景点名称 | 评分 | 星级 | 类型 | 门票(元) |\n| --- | --- | --- | --- | --- | --- |\n");
        LambdaQueryWrapper<ScenicSpot> scoreWrapper = new LambdaQueryWrapper<>();
        scoreWrapper.select(ScenicSpot::getSpotName, ScenicSpot::getScore, ScenicSpot::getStarLevel,
                        ScenicSpot::getSpotType, ScenicSpot::getTicketPrice)
                .isNotNull(ScenicSpot::getScore)
                .orderByDesc(ScenicSpot::getScore)
                .last("LIMIT 10");
        List<ScenicSpot> topScoreSpots = scenicSpotMapper.selectList(scoreWrapper);
        for (int i = 0; i < topScoreSpots.size(); i++) {
            ScenicSpot s = topScoreSpots.get(i);
            String name = s.getSpotName() != null ? s.getSpotName() : "";
            String score = s.getScore() != null ? s.getScore().toString() : "-";
            String star = s.getStarLevel() != null ? s.getStarLevel() + "★" : "-";
            String type = s.getSpotType() != null ? s.getSpotType() : "";
            String price = s.getTicketPrice() != null ? s.getTicketPrice().toString() : "-";
            sb.append("| ").append(i + 1).append(" | ").append(name)
                    .append(" | ").append(score).append(" | ").append(star)
                    .append(" | ").append(type).append(" | ").append(price).append(" |\n");
        }

        sb.append("\n---\n\n");
        sb.append("**以上为系统数据库全部可查询数据的摘要。请严格基于上述数据回答用户问题。**\n");
        return sb.toString();
    }

    /**
     * 向前端写一个SSE事件: data: {json}\n\n
     */
    private void sendSseEvent(HttpServletResponse response, String json) throws Exception {
        response.getWriter().write("data: " + json + "\n\n");
        response.getWriter().flush();
    }

    /**
     * 通知前端流结束
     */
    private void sendSseDone(HttpServletResponse response) throws Exception {
        response.getWriter().write("data: [DONE]\n\n");
        response.getWriter().flush();
    }
}
