package com.tourism.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tourism.dto.request.CommentCreateRequest;
import com.tourism.dto.response.CommentResponse;

/**
 * 评论Service接口
 */
public interface CommentService {

    /**
     * 发表评论
     * @param userId 当前用户ID
     * @param request 评论请求
     */
    void addComment(Integer userId, CommentCreateRequest request);

    /**
     * 分页查询某景点的评论列表(按时间倒序)
     * @param spotId 景点ID
     * @param current 当前页
     * @param size 每页大小
     * @return 评论分页(含用户名)
     */
    Page<CommentResponse> listBySpot(Integer spotId, Long current, Long size);

    /**
     * 分页查询某用户发表的评论(个人中心"我的评论")
     * @param userId 用户ID
     * @param current 当前页
     * @param size 每页大小
     * @return 评论分页(含景点名)
     */
    Page<CommentResponse> listByUser(Integer userId, Long current, Long size);

    /**
     * 删除评论(仅评论作者可删)
     * @param userId 当前用户ID(用于权限校验)
     * @param commentId 评论ID
     */
    void delete(Integer userId, Long commentId);

    /**
     * 管理员审核: 分页查询全部评论(支持按景点名称/景点ID/用户ID筛选)
     * @param spotId 景点ID(可选, null=不限)
     * @param spotName 景点名称(可选, 模糊匹配, null=不限)
     * @param userId 用户ID(可选, null=不限)
     * @param current 当前页
     * @param size 每页大小
     * @return 评论分页(含用户名+景点名)
     */
    Page<CommentResponse> listAll(Integer spotId, String spotName, Integer userId, Long current, Long size);

    /**
     * 管理员删除任意评论(无需作者校验)
     * @param commentId 评论ID
     */
    void adminDelete(Long commentId);
}
