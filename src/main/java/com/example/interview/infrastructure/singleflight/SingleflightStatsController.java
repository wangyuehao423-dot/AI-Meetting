package com.example.interview.infrastructure.singleflight;

import org.springframework.web.bind.annotation.*;

import com.example.interview.infrastructure.singleflight.CallCostTallyService.StatsSnapshot;

// ============================================================
// SingleflightStatsController —— 成本统计的只读 / 重置端点
// ============================================================
// 把 CallCostTallyService 里那份"真实 AI 调用 / 缓存回放 / 配额拦截"的成本账
// 暴露成 HTTP 接口，运维和 Code Agent 都能程序化地读取与重置：
//   GET  /api/singleflight/stats  读全量成本统计（含分 key 明细）
//   POST /api/singleflight/stats/reset  清空统计与窗口
// 至此亮点3里的"显式成本统计 + 配额"不再只是代码里的内部数字，而是
// 一个可查询、可下钻、可验证成本控制效果的硬接口。
// ============================================================
@RestController
@RequestMapping("/api/singleflight")
public class SingleflightStatsController {

    private final CallCostTallyService cost;

    public SingleflightStatsController(CallCostTallyService cost) {
        this.cost = cost;
    }

    @GetMapping("/stats")
    public StatsSnapshot stats() {
        return cost.stats();
    }

    @PostMapping("/stats/reset")
    public StatsSnapshot reset() {
        cost.reset();
        return cost.stats();
    }
}