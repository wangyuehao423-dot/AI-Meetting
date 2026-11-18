package com.example.interview.infrastructure.singleflight;

import org.springframework.web.bind.annotation.*;

import com.example.interview.infrastructure.singleflight.CallCostTallyService.StatsSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;

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
    private final SingleflightStateMachine stateMachine;

    public SingleflightStatsController(
            CallCostTallyService cost,
            SingleflightStateMachine stateMachine
    ) {
        this.cost = cost;
        this.stateMachine = stateMachine;
    }

    @GetMapping("/stats")
    public StatsSnapshot stats() {
        return cost.stats();
    }

    /**
     * 读某个业务 key 的执行态：状态 + 心跳年龄 + 执行方是否还活着。
     *
     * <p>为什么把它暴露出来：心跳机制如果只有一个时间戳藏在 Redis 里，
     * 出问题时没人看得见"到底是还在跑，还是已经死透了"。做成接口后，
     * 排障时一条 curl 就能回答这个问题，也方便演示"等待方凭据什么做出接管决定"。
     */
    @GetMapping("/state")
    public Map<String, Object> state(@RequestParam String key) {
        Map<String, Object> view = new LinkedHashMap<>();
        SingleflightStateMachine.State state = stateMachine.current(key);
        long age = stateMachine.heartbeatAgeMs(key);
        view.put("key", key);
        view.put("state", state == null ? "ABSENT" : state.name());
        view.put("heartbeatAgeMs", age);
        view.put("alive", stateMachine.executorAlive(key));
        view.put("failureReason", stateMachine.reason(key));
        return view;
    }

    @PostMapping("/stats/reset")
    public StatsSnapshot reset() {
        cost.reset();
        return cost.stats();
    }
}