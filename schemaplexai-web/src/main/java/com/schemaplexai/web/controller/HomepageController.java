package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.vo.homepage.HomepageAggregateVO;
import com.schemaplexai.service.homepage.HomepageAggregateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页控制器
 */
@RestController
@RequestMapping("/homepage")
@RequiredArgsConstructor
@Tag(name = "首页")
public class HomepageController {

    private final HomepageAggregateService homepageAggregateService;

    @GetMapping("/aggregate")
    @Operation(summary = "获取首页聚合数据")
    public R<HomepageAggregateVO> aggregate(
            @RequestParam(required = false) String mode) {
        return R.ok(homepageAggregateService.aggregate(mode));
    }
}
