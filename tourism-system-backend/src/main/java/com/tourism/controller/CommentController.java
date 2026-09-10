package com.tourism.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tourism.common.Result;
import com.tourism.dto.request.CommentCreateRequest;
import com.tourism.dto.response.CommentResponse;
import com.tourism.service.CommentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 评论Controller
 * 所有接口需要token鉴权
 */
@RestController
@RequestMapping("/api/comment")
public class CommentController {

    @Autowired
    private CommentService commentService;

    /**
     * 发表评论
     * POST /api/comment
     */
    @PostMapping
    public Result<Void> addComment(@RequestAttribute("adminId") Integer adminId,
                                   @Valid @RequestBody CommentCreateRequest request) {
        commentService.addComment(adminId, request);
        return Result.success("评论成功", null);
    }

    /**
     * 查询某景点的评论列表(按时间倒序)
     * GET /api/comment/spot/{spotId}
     */
    @GetMapping("/spot/{spotId}")
    public Result<Page<CommentResponse>> listBySpot(
            @PathVariable Integer spotId,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "10") Long size) {
        Page<CommentResponse> page = commentService.listBySpot(spotId, current, size);
        return Result.success(page);
    }

    /**
     * 查询当前用户的评论列表(个人中心"我的评论")
     * GET /api/comment/my
     */
    @GetMapping("/my")
    public Result<Page<CommentResponse>> listMyComments(
            @RequestAttribute("adminId") Integer adminId,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "10") Long size) {
        Page<CommentResponse> page = commentService.listByUser(adminId, current, size);
        return Result.success(page);
    }

    /**
     * 删除评论(仅作者可删)
     * DELETE /api/comment/{commentId}
     */
    @DeleteMapping("/{commentId}")
    public Result<Void> delete(@RequestAttribute("adminId") Integer adminId,
                               @PathVariable Long commentId) {
        commentService.delete(adminId, commentId);
        return Result.success("删除成功", null);
    }

    // ==================== 管理员审核接口 ====================

    /**
     * 管理员查询全部评论(支持按景点名称/景点ID/用户ID筛选)
     * GET /api/comment/all
     */
    @GetMapping("/all")
    public Result<Page<CommentResponse>> listAll(
            @RequestParam(required = false) Integer spotId,
            @RequestParam(required = false) String spotName,
            @RequestParam(required = false) Integer userId,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "10") Long size) {
        Page<CommentResponse> page = commentService.listAll(spotId, spotName, userId, current, size);
        return Result.success(page);
    }

    /**
     * 管理员删除任意评论(无需作者校验)
     * DELETE /api/comment/admin/{commentId}
     */
    @DeleteMapping("/admin/{commentId}")
    public Result<Void> adminDelete(@PathVariable Long commentId) {
        commentService.adminDelete(commentId);
        return Result.success("删除成功", null);
    }
}
