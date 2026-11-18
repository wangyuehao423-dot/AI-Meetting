package com.example.interview.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// ============================================================
// Skill 远程配置属性 —— 提示词托管到 Nacos 的连接参数
// ============================================================
// 为什么单独一套前缀（interview.skill.nacos）而不复用 spring.cloud.nacos.config：
//   1) spring.cloud.nacos.config 那一套是"启动期把配置当成 Environment 的一部分",
//      它有个硬性要求：classpath 上有 nacos-config starter 时就必须写 spring.config.import=nacos:，
//      否则直接 APPLICATION FAILED TO START。这等于"Nacos 不在就起不来"，与下面第 2 条的降级策略冲突。
//   2) 本项目的诉求很窄：只托管 5 份 Markdown 规则，不需要把整个配置文件搬上去。
//      用原生 ConfigService 按 dataId 精确拉取 + 注册监听，粒度更小、行为更可控。
//   3) Nacos 不可用时必须能降级到 jar 内打包的 classpath 版本照常启动，
//      否则从 GitHub clone 下来、没起 Nacos 的人第一步就跑不起来。
// ============================================================
@Component
@ConfigurationProperties(prefix = "interview.skill.nacos")
public class SkillNacosProperties {

    // 总开关：false 时完全不连 Nacos，5 份规则全部走 classpath（等同于改造前的行为）。
    private boolean enabled = true;

    // Nacos 服务地址。本机 Docker 把 8848 映射到了 18848，所以默认值不是 8848。
    private String serverAddr = "localhost:8848";

    // 命名空间：public 用空串表示。生产上建议单独开一个 namespace 放提示词，
    // 与业务配置隔离，也方便按 namespace 做权限控制。
    private String namespace = "";

    // 分组：5 份提示词统一放在这一个 group 下，Nacos 控制台一眼能筛出来。
    private String group = "INTERVIEW_SKILL";

    // dataId 前缀：最终 dataId = 前缀 + skill 名 + ".md"，例如 interview-skill-interview-scoring.md。
    // 带 .md 后缀是有意的——Nacos 控制台按 dataId 排序时，一串同名前缀的文件能连在一起。
    private String dataIdPrefix = "interview-skill-";

    // 单次拉取超时（毫秒）。启动时会并行拉 5 份，所以总耗时约等于这个值而不是 5 倍。
    private long timeoutMs = 3000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerAddr() {
        return serverAddr;
    }

    public void setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getDataIdPrefix() {
        return dataIdPrefix;
    }

    public void setDataIdPrefix(String dataIdPrefix) {
        this.dataIdPrefix = dataIdPrefix;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
