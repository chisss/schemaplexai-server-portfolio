package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.quality.CrossReviewCreateRequest;
import com.schemaplexai.model.dto.quality.DeviationDetectRequest;
import com.schemaplexai.model.dto.quality.DeviationQueryRequest;
import com.schemaplexai.model.dto.quality.DeviationStatusUpdateRequest;
import com.schemaplexai.model.dto.quality.IntentDefectAnalyzeRequest;
import com.schemaplexai.model.dto.quality.IntentDefectQueryRequest;
import com.schemaplexai.model.vo.quality.AnalyzeTaskVO;
import com.schemaplexai.model.vo.quality.CrossReviewVO;
import com.schemaplexai.model.vo.quality.DetectTaskVO;
import com.schemaplexai.model.vo.quality.DeviationStatisticsVO;
import com.schemaplexai.model.vo.quality.DeviationVO;
import com.schemaplexai.model.vo.quality.IntentDefectVO;
import com.schemaplexai.service.quality.CrossReviewService;
import com.schemaplexai.service.quality.DeviationService;
import com.schemaplexai.service.quality.IntentDefectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 质量保障控制器
 */
@RestController
@RequestMapping("/quality")
@RequiredArgsConstructor
@Tag(name = "质量保障")
public class QualityController {

    private final DeviationService deviationService;
    private final IntentDefectService intentDefectService;
    private final CrossReviewService crossReviewService;

    // ==================== 偏离检测 ====================

    @PostMapping("/deviations/detect")
    @Operation(summary = "触发偏离检测")
    public R<DetectTaskVO> detect(@Valid @RequestBody DeviationDetectRequest request) {
        return R.ok(deviationService.detect(request));
    }

    @GetMapping("/deviations")
    @Operation(summary = "分页查询偏离记录")
    public R<PageResult<DeviationVO>> pageDeviations(DeviationQueryRequest request) {
        return R.ok(deviationService.page(request));
    }

    @GetMapping("/deviations/statistics")
    @Operation(summary = "偏离统计")
    public R<DeviationStatisticsVO> statistics(
            @RequestParam(required = false) String specId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return R.ok(deviationService.statistics(specId, startDate, endDate));
    }

    @GetMapping("/deviations/{id}")
    @Operation(summary = "获取偏离详情")
    public R<DeviationVO> getDeviation(@PathVariable String id) {
        return R.ok(deviationService.getById(id));
    }

    @PatchMapping("/deviations/{id}/status")
    @Operation(summary = "更新偏离状态")
    public R<Void> updateDeviationStatus(@PathVariable String id,
                                         @Valid @RequestBody DeviationStatusUpdateRequest request) {
        deviationService.updateStatus(id, request);
        return R.ok();
    }

    // ==================== 意图缺陷 ====================

    @PostMapping("/intent-defects/analyze")
    @Operation(summary = "触发意图缺陷分析")
    public R<AnalyzeTaskVO> analyze(@Valid @RequestBody IntentDefectAnalyzeRequest request) {
        return R.ok(intentDefectService.analyze(request));
    }

    @GetMapping("/intent-defects")
    @Operation(summary = "分页查询意图缺陷")
    public R<PageResult<IntentDefectVO>> pageIntentDefects(IntentDefectQueryRequest request) {
        return R.ok(intentDefectService.page(request));
    }

    @GetMapping("/intent-defects/{id}")
    @Operation(summary = "获取意图缺陷详情")
    public R<IntentDefectVO> getIntentDefect(@PathVariable String id) {
        return R.ok(intentDefectService.getById(id));
    }

    @PatchMapping("/intent-defects/{id}/status")
    @Operation(summary = "更新意图缺陷状态")
    public R<Void> updateIntentDefectStatus(@PathVariable String id,
                                            @RequestParam String status) {
        intentDefectService.updateStatus(id, status);
        return R.ok();
    }

    // ==================== 交叉审查 ====================

    @PostMapping("/cross-reviews")
    @Operation(summary = "创建交叉审查")
    public R<CrossReviewVO> createCrossReview(@Valid @RequestBody CrossReviewCreateRequest request) {
        return R.ok(crossReviewService.create(request));
    }

    @GetMapping("/cross-reviews")
    @Operation(summary = "分页查询交叉审查")
    public R<PageResult<CrossReviewVO>> pageCrossReviews(
            @RequestParam(required = false) String specId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return R.ok(crossReviewService.page(specId, status, page, size));
    }

    @GetMapping("/cross-reviews/{id}")
    @Operation(summary = "获取交叉审查详情")
    public R<CrossReviewVO> getCrossReview(@PathVariable String id) {
        return R.ok(crossReviewService.getById(id));
    }
}
