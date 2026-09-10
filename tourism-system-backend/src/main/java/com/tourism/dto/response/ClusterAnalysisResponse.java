package com.tourism.dto.response;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * K-Means聚类分析响应DTO
 * 用于大屏"景点聚类分析"散点图: 每个景点按(门票价格,评分)两个特征进行无监督聚类,
 * 返回每个景点的聚类标签、各类簇中心点及簇内样本数量
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClusterAnalysisResponse {

    /**
     * 参与聚类的景点坐标点列表(已随机采样, 控制规模)
     */
    private List<Point> points;

    /**
     * 聚类中心列表(每个簇一个中心, 用于散点图标绘"中心点")
     */
    private List<Centroid> centroids;

    /**
     * 每个簇包含的景点数(反映各聚类的规模占比)
     */
    private List<Integer> clusterSizes;

    /**
     * 单个景点聚类结果点
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Point {
        /**
         * 景点名称
         */
        private String name;
        /**
         * 门票价格(原始值, x轴)
         */
        private Double price;
        /**
         * 评分(原始值, y轴)
         */
        private Double score;
        /**
         * 所属簇编号(0 ~ K-1)
         */
        private Integer clusterId;
    }

    /**
     * 聚类中心(以原始量纲返回, 便于与散点叠加)
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Centroid {
        /**
         * 簇编号
         */
        private Integer clusterId;
        /**
         * 簇名(如"高性价比")由前端根据中心点含义自行描述
         */
        private String clusterName;
        /**
         * 中心价格
         */
        private Double price;
        /**
         * 中心评分
         */
        private Double score;
    }
}