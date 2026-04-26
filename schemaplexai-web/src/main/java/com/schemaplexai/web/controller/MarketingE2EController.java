package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.vo.e2e.MarketingReadinessVO;
import com.schemaplexai.service.e2e.MarketingE2EReadinessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 营销场景 E2E 回归检查控制器
 */
@RestController
@RequestMapping("/e2e/marketing")
@RequiredArgsConstructor
@Tag(name = "营销场景E2E")
public class MarketingE2EController {

    private final MarketingE2EReadinessService readinessService;

    @GetMapping("/readiness")
    @Operation(summary = "检查营销场景回归准备度")
    public R<MarketingReadinessVO> readiness(@RequestParam(defaultValue = "5") Integer from,
                                             @RequestParam(defaultValue = "15") Integer to) {
        return R.ok(readinessService.checkMarketingScenarios(from, to));
    }
}
