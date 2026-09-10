package com.tourism.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tourism.common.BusinessException;
import com.tourism.dto.request.CommentCreateRequest;
import com.tourism.dto.response.CommentResponse;
import com.tourism.entity.Admin;
import com.tourism.entity.ScenicSpot;
import com.tourism.entity.SpotComment;
import com.tourism.mapper.AdminMapper;
import com.tourism.mapper.ScenicSpotMapper;
import com.tourism.mapper.SpotCommentMapper;
import com.tourism.service.CommentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 评论Service实现类
 */
@Service
public class CommentServiceImpl implements CommentService {

    @Autowired
    private SpotCommentMapper commentMapper;

    @Autowired
    private AdminMapper adminMapper;

    @Autowired
    private ScenicSpotMapper scenicSpotMapper;

    /**
     * 发表评论
     */
    @Override
    public void addComment(Integer userId, CommentCreateRequest request) {
        // 校验评分范围1-5
        if (request.getRating() < 1 || request.getRating() > 5) {
            throw new BusinessException("评分必须在1-5之间");
        }
        // 校验景点是否存在
        ScenicSpot spot = scenicSpotMapper.selectById(request.getSpotId());
        if (spot == null) {
            throw new BusinessException("景点不存在");
        }
        SpotComment comment = new SpotComment();
        comment.setSpotId(request.getSpotId());
        comment.setUserId(userId);
        comment.setContent(request.getContent());
        comment.setRating(request.getRating());
        commentMapper.insert(comment);
    }

    /**
     * 查询某景点的评论列表(按时间倒序, 分页)
     */
    @Override
    public Page<CommentResponse> listBySpot(Integer spotId, Long current, Long size) {
        Page<SpotComment> page = new Page<>(current, size);
        LambdaQueryWrapper<SpotComment> wrapper = new LambdaQueryWrapper<SpotComment>()
                .eq(SpotComment::getSpotId, spotId)
                .orderByDesc(SpotComment::getCreateTime);
        Page<SpotComment> commentPage = commentMapper.selectPage(page, wrapper);

        // 转换为响应DTO, 批量查询用户名避免N+1
        return convertPage(commentPage, true, false);
    }

    /**
     * 查询某用户的评论列表(个人中心"我的评论")
     */
    @Override
    public Page<CommentResponse> listByUser(Integer userId, Long current, Long size) {
        Page<SpotComment> page = new Page<>(current, size);
        LambdaQueryWrapper<SpotComment> wrapper = new LambdaQueryWrapper<SpotComment>()
                .eq(SpotComment::getUserId, userId)
                .orderByDesc(SpotComment::getCreateTime);
        Page<SpotComment> commentPage = commentMapper.selectPage(page, wrapper);

        return convertPage(commentPage, false, true);
    }

    /**
     * 删除评论(仅作者可删)
     */
    @Override
    public void delete(Integer userId, Long commentId) {
        SpotComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException("评论不存在");
        }
        // 权限校验: 只能删除自己的评论
        if (!comment.getUserId().equals(userId)) {
            throw new BusinessException("无权删除他人评论");
        }
        commentMapper.deleteById(commentId);
    }

    /**
     * 管理员审核: 查询全部评论(支持按景点名称/景点ID/用户ID筛选)
     */
    @Override
    public Page<CommentResponse> listAll(Integer spotId, String spotName, Integer userId, Long current, Long size) {
        Page<SpotComment> page = new Page<>(current, size);
        LambdaQueryWrapper<SpotComment> wrapper = new LambdaQueryWrapper<SpotComment>()
                .orderByDesc(SpotComment::getCreateTime);
        // 按景点名称模糊筛选: 先查出名称匹配的景点ID集合, 再用ID过滤评论
        if (spotName != null && !spotName.trim().isEmpty()) {
            List<Integer> matchedIds = scenicSpotMapper.selectList(
                    new LambdaQueryWrapper<ScenicSpot>()
                            .like(ScenicSpot::getSpotName, spotName.trim())
                            .select(ScenicSpot::getId))
                    .stream().map(ScenicSpot::getId).collect(Collectors.toList());
            // 无匹配景点时直接返回空页, 避免 in(空集合) 生成非法SQL
            if (matchedIds.isEmpty()) {
                return convertPage(page, false, false);
            }
            wrapper.in(SpotComment::getSpotId, matchedIds);
        }
        // 按景点ID筛选
        if (spotId != null) {
            wrapper.eq(SpotComment::getSpotId, spotId);
        }
        // 按用户筛选
        if (userId != null) {
            wrapper.eq(SpotComment::getUserId, userId);
        }
        Page<SpotComment> commentPage = commentMapper.selectPage(page, wrapper);
        // 同时补充用户名和景点名
        return convertPage(commentPage, true, true);
    }

    /**
     * 管理员删除任意评论(无需作者校验)
     */
    @Override
    public void adminDelete(Long commentId) {
        SpotComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException("评论不存在");
        }
        commentMapper.deleteById(commentId);
    }

    /**
     * 分页转换: SpotComment -> CommentResponse, 批量补充用户名/景点名
     * @param needUserName 是否补充用户名(景点评论列表需要)
     * @param needSpotName 是否补充景点名(我的评论列表需要)
     */
    private Page<CommentResponse> convertPage(Page<SpotComment> commentPage, boolean needUserName, boolean needSpotName) {
        Page<CommentResponse> result = new Page<>(commentPage.getCurrent(), commentPage.getSize(), commentPage.getTotal());
        List<SpotComment> records = commentPage.getRecords();
        if (records.isEmpty()) {
            return result;
        }

        // 批量查询用户名(避免N+1)
        java.util.Map<Integer, String> userNameMap = new java.util.HashMap<>();
        if (needUserName) {
            List<Integer> userIds = records.stream().map(SpotComment::getUserId).distinct().collect(Collectors.toList());
            for (Integer uid : userIds) {
                Admin admin = adminMapper.selectById(uid);
                userNameMap.put(uid, admin == null ? "匿名用户" : admin.getAdminName());
            }
        }

        // 批量查询景点名
        java.util.Map<Integer, String> spotNameMap = new java.util.HashMap<>();
        if (needSpotName) {
            List<Integer> spotIds = records.stream().map(SpotComment::getSpotId).distinct().collect(Collectors.toList());
            for (Integer sid : spotIds) {
                ScenicSpot spot = scenicSpotMapper.selectById(sid);
                spotNameMap.put(sid, spot == null ? "已删除景点" : spot.getSpotName());
            }
        }

        List<CommentResponse> list = records.stream().map(c -> {
            CommentResponse r = new CommentResponse();
            r.setId(c.getId());
            r.setSpotId(c.getSpotId());
            r.setSpotName(spotNameMap.get(c.getSpotId()));
            r.setUserId(c.getUserId());
            r.setUserName(userNameMap.get(c.getUserId()));
            r.setContent(c.getContent());
            r.setRating(c.getRating());
            r.setCreateTime(c.getCreateTime());
            return r;
        }).collect(Collectors.toList());
        result.setRecords(list);
        return result;
    }
}
