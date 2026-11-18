package com.example.interview.skill;

import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ============================================================
// Skill 查询接口 —— 线上生效规则的"可观测窗口"
// ============================================================
// 提示词搬进 Nacos 之后，必须能随时回答三个问题：
//   1) 现在生效的规则是哪一份？（列表 / 单个查询，返回内容 + version + source + updatedAt）
//   2) 它是从 Nacos 来的还是从 jar 里来的？（source 字段）
//   3) Nacos 到底是通的还是不通的？（/remote/status，避免"以为在改、其实没生效"）
//
// 第三个接口不是可有可无：Nacos 连不上时程序会降级到 classpath，
// 这个降级是对的（不能因为配置中心挂了就不让服务跑），但"静默"本身有问题——
// 运维在 Nacos 上改了半天发现线上没变，如果没有这个接口就只能翻日志。
// ============================================================
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRegistry registry;
    private final SkillConfigClient remote;

    public SkillController(SkillRegistry registry, SkillConfigClient remote) {
        this.registry = registry;
        this.remote = remote;
    }

    // 全部规则的概览。不返回正文（正文可能有几千字），只给定位信息。
    @GetMapping
    public List<Map<String, Object>> list() {
        return registry.all().stream()
                .map(definition -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", definition.name());
                    item.put("version", definition.version());
                    item.put("source", definition.source());
                    item.put("updatedAt", String.valueOf(definition.updatedAt()));
                    item.put("purpose", definition.purpose());
                    return item;
                })
                .toList();
    }

    // 单个规则的完整定义，含正文。想确认"线上到底在用什么规则"，看这个。
    @GetMapping("/{name}")
    public SkillDefinition get(@PathVariable String name) {
        // 名字不存在时抛 IllegalStateException，由 ApiExceptionHandler 转成 409 + message。
        return registry.get(name);
    }

    // 手动从 Nacos 重拉。正常情况下用不到（有变更监听会自动生效），
    // 只在"怀疑监听掉了 / 想立刻确认线上已经是新版"时用。
    @PostMapping("/{name}/reload")
    public SkillDefinition reload(@PathVariable String name) {
        return registry.reload(name);
    }

    // Nacos 连接状态。用于回答"我改了怎么没生效"——先看这个是不是 false。
    @GetMapping("/remote/status")
    public Map<String, Object> remoteStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("remote", remote.describeTarget());
        status.put("available", remote.isAvailable());
        // 这句话写细一点是有必要的：Nacos 连不上时，getConfig 会命中客户端的本地快照文件，
        // 也就是"上次成功拉到的那一份"。所以此时线上跑的既不是服务端最新版，也不一定是 jar 里那份，
        // 而是"最后一次拉取时的样子"。运维排查"改了没生效"，必须知道这个中间态。
        status.put("note", remote.isAvailable()
                ? "Nacos 正常，规则以 Nacos 为准，改动发布后自动生效"
                : "Nacos 不可达：改配置不会生效。客户端会用本地快照（最后一次成功拉取的副本）继续提供内容，"
                + "本地快照也缺失时才退回 jar 内 classpath 版本");
        return status;
    }
}
