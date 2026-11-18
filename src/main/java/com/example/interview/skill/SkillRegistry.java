package com.example.interview.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// ============================================================
// Skill 注册表 —— 全项目取规则正文的唯一入口
// ============================================================
// 改造前后对比：
//   改造前：启动时读 5 份 classpath 文件，之后不再变化。改一个字 → 重新打包发布。
//   改造后：启动时优先从 Nacos 拉，拉不到的用 classpath 兜底；之后订阅变更，
//           Nacos 上点发布 → 本类收到回调 → 原子替换 → 下一条 AI 请求立即用新规则。
//
// 三条设计约束（都是踩过坑之后定下来的）：
//
//   1) 启动必须能起来。Nacos 不在、配置没推、网络不通，一律降级 classpath，
//      绝不允许"配置中心挂了导致应用起不来"。所以构造阶段没有任何地方向外抛异常。
//
//   2) 运行中的替换必须是"要么全换、要么不换"。SkillDefinition 是不可变 record，
//      替换时整体 put 一个新对象，读的线程要么拿到旧的完整定义、要么拿到新的完整定义，
//      不会读到"元数据是新的、正文是旧的"这种半截状态。
//
//   3) 新内容解析失败时保留旧版本，绝不落地。有人在 Nacos 上把 front matter 改坏、
//      或者误删了正文，如果直接替换，线上 AI 立刻按一份残缺规则工作，而且不报错。
//      这里的处理是：打 ERROR、拒绝本次更新、继续用上一版——宁可规则旧，不能规则坏。
// ============================================================
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final SkillLoader loader;
    private final SkillConfigClient remote;
    private final Map<String, SkillDefinition> definitions = new ConcurrentHashMap<>();

    public SkillRegistry(SkillLoader loader, SkillConfigClient remote) {
        this.loader = loader;
        this.remote = remote;
        loadAll();
        subscribeAll();
    }

    // ============================================================
    // 启动：先并行拉远程，再逐条解析
    // ============================================================
    private void loadAll() {
        // 一次并行拉完 5 份，避免 Nacos 不可达时串行等 5 个超时。
        Map<String, String> remoteContents = remote.fetchAll(SkillCatalog.NAMES);

        int fromNacos = 0;
        int fromClasspath = 0;
        for (String name : SkillCatalog.NAMES) {
            SkillDefinition definition = build(name, remoteContents.get(name));
            definitions.put(name, definition);
            if (SkillLoader.SOURCE_NACOS.equals(definition.source())) {
                fromNacos += 1;
            } else {
                fromClasspath += 1;
            }
            log.info("Skill 已加载：name={}, source={}, version={}",
                    name, definition.source(), definition.version());
        }
        log.info("Skill 加载完成：共 {} 份，Nacos 托管 {} 份，classpath 兜底 {} 份",
                definitions.size(), fromNacos, fromClasspath);
        if (fromNacos > 0 && !remote.isAvailable()) {
            // 启动时 Nacos 已经连不上，但这几份居然"读到了"——它们来自客户端的本地快照，
            // 不是服务端最新版。不把这件事说清楚，很容易把"读到了"误当成"Nacos 正常"。
            log.warn("Nacos 当前不可达，上述 {} 份规则来自客户端本地快照（最后一次成功拉取的副本），可能不是最新版本", fromNacos);
        }
    }

    /**
     * 构造一份可用定义：远程有就用远程，远程没有或解析不了就退回 classpath。
     *
     * <p>只在启动阶段使用。运行中的更新走 {@link #refresh}，那里不允许降级——
     * 原因见该类头部第 3 条约束的说明。
     */
    private SkillDefinition build(String name, String remoteContent) {
        if (remoteContent != null && !remoteContent.isBlank()) {
            try {
                return loader.parse(name, remoteContent, SkillLoader.SOURCE_NACOS);
            } catch (RuntimeException e) {
                // Nacos 上有配置但解析不了：退回包里那份，并大声报错。
                // 这类问题最典型的原因是 front matter 的 --- 被人删了，或者正文被清空。
                log.error("Nacos 上的 Skill 无法解析，已回退 classpath 版本：name={}", name, e);
            }
        }
        return loader.loadFromClasspath(name);
    }

    // ============================================================
    // 订阅：Nacos 改了 → 这里换掉
    // ============================================================
    private void subscribeAll() {
        for (String name : SkillCatalog.NAMES) {
            remote.subscribe(name, content -> refresh(name, content));
        }
    }

    /**
     * 用一段新的规则原文替换当前生效版本。
     *
     * <p>解析失败时不替换、只记 ERROR：线上继续用上一版。
     * 这比"替换成一份残缺规则"安全得多——后者会让 AI 行为悄悄变差，且没有任何报错。
     */
    private void refresh(String name, String content) {
        SkillDefinition fresh;
        try {
            fresh = loader.parse(name, content, SkillLoader.SOURCE_NACOS);
        } catch (RuntimeException e) {
            // 新内容解析不了：不替换，只报错。这是本类最重要的一个分支——
            // 一旦这里改成"替换成默认值"或者"直接往外抛"，都会变成线上事故。
            log.error("Skill 热更新被拒绝，线上继续使用当前版本：name={}", name, e);
            return;
        }
        SkillDefinition old = definitions.put(name, fresh);

        if (old != null && old.version().equals(fresh.version())) {
            // 内容指纹没变：Nacos 客户端在注册监听时会先回推一次当前值，
            // 这里吞掉这次无变化的通知，避免每次启动都刷一条"热更新"日志把人吓到。
            log.debug("Skill 内容未变化，跳过替换：name={}, version={}", name, fresh.version());
            return;
        }
        log.info("Skill 热更新生效：name={}, version {} -> {}, 无需重启",
                name,
                old == null ? "无" : old.version(),
                fresh.version());
    }

    // ============================================================
    // 取用
    // ============================================================
    public SkillDefinition get(String name) {
        SkillDefinition definition = definitions.get(name);
        if (definition == null) {
            // 抛异常而不是返回空：返回空等于提示词里少一整段规则，
            // 模型照样给出一个"看起来正常"的答案，错误被静默吞掉。
            throw new IllegalStateException("skill not found: " + name);
        }
        return definition;
    }

    public List<SkillDefinition> all() {
        List<SkillDefinition> result = new ArrayList<>(SkillCatalog.NAMES.size());
        for (String name : SkillCatalog.NAMES) {
            SkillDefinition definition = definitions.get(name);
            if (definition != null) {
                result.add(definition);
            }
        }
        return result;
    }

    /**
     * 手动从 Nacos 重拉一份（运维接口用）。
     *
     * <p>与启动时的 {@link #build} 有意不同：这里不降级。
     * 手动刷新是一个"我明确想让它变"的动作，要么成功、要么报错让人看见；
     * 静默退回 classpath 会让人以为刷新成功了，实际线上还是老规则。
     *
     * @throws IllegalStateException 取不到内容或内容解析失败（由调用方转成 5xx 暴露出来）
     */
    public SkillDefinition reload(String name) {
        if (!SkillCatalog.NAME_SET.contains(name)) {
            throw new IllegalArgumentException("unknown skill: " + name);
        }
        String content = remote.fetch(name);
        if (content == null || content.isBlank()) {
            throw new IllegalStateException(
                    "Nacos 上取不到该 Skill 的配置：name=" + name + "，目标=" + remote.describeTarget());
        }
        SkillDefinition fresh = loader.parse(name, content, SkillLoader.SOURCE_NACOS);
        SkillDefinition old = definitions.put(name, fresh);
        log.info("Skill 手动刷新完成：name={}, version {} -> {}",
                name,
                old == null ? "无" : old.version(),
                fresh.version());
        return fresh;
    }
}
