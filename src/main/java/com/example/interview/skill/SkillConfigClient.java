package com.example.interview.skill;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.alibaba.nacos.api.exception.NacosException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

// ============================================================
// Skill 远程配置客户端 —— 直连 Nacos 配置中心，拉取 + 监听提示词
// ============================================================
// 职责边界：只管"把一段字符串从 Nacos 拿下来"和"内容变了通知我"，
// 不负责解析 Markdown、不负责存注册表——那些是 SkillLoader / SkillRegistry 的事。
// 这样分开的好处：以后要从 Nacos 换成 Apollo / Consul，只换这一个类。
//
// 三条关键行为：
//   1) 构造阶段任何失败都不抛异常。Nacos 不在、地址写错、容器没起，都只是"拉不到"，
//      由上层回退到 classpath 打包版本，应用照常启动。
//   2) 拉不到的返回 null，而不是空串。null 表示"没这份配置"，空串表示"配置被删空了"，
//      后者绝不能当成"规则为空"去用——那等于把提示词清空了。
//   3) 监听回调里抛出的异常一律吞掉并打日志。Nacos 客户端的回调线程是它自己的，
//      在里面抛异常会把该线程的后续通知链路搞乱，进而静默丢失后面所有更新。
// ============================================================
@Component
public class SkillConfigClient {

    private static final Logger log = LoggerFactory.getLogger(SkillConfigClient.class);

    private final SkillNacosProperties properties;

    // Nacos 原生客户端。null 表示"没启用"或"初始化失败"，此时所有读取都返回 null，
    // 上层自动回退 classpath。用 null 而不是抛异常，是为了让降级路径只有一条。
    private final ConfigService configService;

    // 启动时并行拉取 5 份规则用的线程池。
    // 为什么并行：Nacos 不可达时每次 getConfig 要等满 timeoutMs，串行 5 次最坏 15 秒，
    // 并行之后总耗时约等于一次超时，启动不会被拖垮。
    private final ExecutorService fetchers;

    public SkillConfigClient(SkillNacosProperties properties) {
        this.properties = properties;
        this.configService = initQuietly(properties);
        int size = Math.max(2, SkillCatalog.NAMES.size());
        this.fetchers = Executors.newFixedThreadPool(size, runnable -> {
            Thread thread = new Thread(runnable, "skill-config-fetch");
            thread.setDaemon(true);
            return thread;
        });
    }

    // ============================================================
    // 初始化：失败只降级，不阻断启动
    // ============================================================
    private ConfigService initQuietly(SkillNacosProperties props) {
        if (!props.isEnabled()) {
            log.info("Skill 远程配置未启用（interview.skill.nacos.enabled=false），全部使用 classpath 打包版本");
            return null;
        }
        Properties nacos = new Properties();
        nacos.put(PropertyKeyConst.SERVER_ADDR, props.getServerAddr());
        // namespace 留空表示 public；传空串进去 Nacos 客户端也能正确处理，这里显式判一下更清楚。
        if (props.getNamespace() != null && !props.getNamespace().isBlank()) {
            nacos.put(PropertyKeyConst.NAMESPACE, props.getNamespace());
        }
        try {
            // createConfigService 本身不发起网络请求，只是构造客户端对象，
            // 真正的连接发生在第一次 getConfig / addListener。
            ConfigService service = NacosFactory.createConfigService(nacos);
            log.info("Skill 远程配置已启用：serverAddr={}, namespace={}, group={}",
                    props.getServerAddr(),
                    props.getNamespace() == null || props.getNamespace().isBlank() ? "public" : props.getNamespace(),
                    props.getGroup());
            return service;
        } catch (NacosException e) {
            log.warn("Nacos 配置客户端初始化失败，全部使用 classpath 打包版本：serverAddr={}, reason={}",
                    props.getServerAddr(), e.getErrMsg());
            return null;
        }
    }

    // ============================================================
    // 读取：拿不到就返回 null（不是空串）
    // ============================================================
    /**
     * 按 skill 名从 Nacos 取规则原文。
     *
     * @return Markdown 原文；Nacos 未启用 / 不可达 / 该 dataId 不存在时返回 null
     */
    public String fetch(String name) {
        if (configService == null) {
            return null;
        }
        String dataId = dataId(name);
        try {
            String content = configService.getConfig(dataId, properties.getGroup(), properties.getTimeoutMs());
            if (content == null || content.isBlank()) {
                // 配置不存在，或被人为清空。两种情况都当"没有远程版本"处理，
                // 由上层回退 classpath —— 绝不能把空串当成一份空规则注入提示词。
                log.warn("Nacos 上未找到可用的 Skill 配置，将回退 classpath：dataId={}, group={}",
                        dataId, properties.getGroup());
                return null;
            }
            return content;
        } catch (NacosException e) {
            log.warn("从 Nacos 拉取 Skill 失败，将回退 classpath：dataId={}, reason={}",
                    dataId, e.getErrMsg());
            return null;
        }
    }

    /**
     * 批量并行拉取，供启动阶段一次性填充注册表。
     * 返回的 Map 里只包含"真的拉到了"的条目，拉不到的 key 直接不出现，
     * 上层按"map 里没有 → 用 classpath"处理。
     */
    public Map<String, String> fetchAll(Collection<String> names) {
        Map<String, String> result = new HashMap<>();
        if (configService == null) {
            return result;
        }
        Map<String, Future<String>> futures = new HashMap<>();
        for (String name : names) {
            futures.put(name, fetchers.submit(() -> fetch(name)));
        }
        for (Map.Entry<String, Future<String>> entry : futures.entrySet()) {
            try {
                String content = entry.getValue().get(properties.getTimeoutMs() + 1000L, TimeUnit.MILLISECONDS);
                if (content != null) {
                    result.put(entry.getKey(), content);
                }
            } catch (Exception e) {
                log.warn("并行拉取 Skill 超时或被中断，该条回退 classpath：name={}", entry.getKey(), e);
            }
        }
        return result;
    }

    // ============================================================
    // 订阅：Nacos 上内容一变，回调里拿到新全文
    // ============================================================
    /**
     * 订阅某个 skill 的变更。
     *
     * <p>注意回调是 Nacos 客户端线程触发的，回调内部必须自己兜住所有异常，
     * 否则会影响该线程后续的其它配置通知。
     *
     * @param onUpdate 收到新内容时的处理逻辑（参数是 Markdown 全文）
     */
    public void subscribe(String name, Consumer<String> onUpdate) {
        if (configService == null) {
            return;
        }
        String dataId = dataId(name);
        try {
            configService.addListener(dataId, properties.getGroup(), new AbstractListener() {
                @Override
                public void receiveConfigInfo(String content) {
                    if (content == null || content.isBlank()) {
                        // 有人在 Nacos 上把配置删了或清空了：不替换线上规则，继续用当前生效版本。
                        // 为什么不像启动那样回退 classpath：运行中突然换回打包版本，
                        // 会让线上行为在无人察觉的情况下跳变，比"保持不动 + 告警"危险得多。
                        log.error("Nacos 上 Skill 配置被清空，已忽略，线上继续使用当前版本：dataId={}", dataId);
                        return;
                    }
                    try {
                        onUpdate.accept(content);
                    } catch (RuntimeException e) {
                        log.error("Skill 变更回调处理失败，已忽略本次更新：dataId={}", dataId, e);
                    }
                }
            });
            log.info("已订阅 Skill 变更：dataId={}, group={}", dataId, properties.getGroup());
        } catch (NacosException e) {
            log.warn("订阅 Skill 变更失败（不影响已有规则）：dataId={}, reason={}", dataId, e.getErrMsg());
        }
    }

    /**
     * Nacos 是否真的可用。用于对外暴露当前提示词的托管状态。
     */
    public boolean isAvailable() {
        if (configService == null) {
            return false;
        }
        try {
            return "UP".equals(configService.getServerStatus());
        } catch (RuntimeException e) {
            return false;
        }
    }

    public String describeTarget() {
        return properties.getServerAddr()
                + " / "
                + (properties.getNamespace() == null || properties.getNamespace().isBlank()
                        ? "public"
                        : properties.getNamespace())
                + " / "
                + properties.getGroup();
    }

    // dataId 规则集中在这里，发布脚本必须与它保持一致，否则会出现"推到 A、拉的是 B"。
    private String dataId(String name) {
        return properties.getDataIdPrefix() + name + ".md";
    }

    @PreDestroy
    public void shutdown() {
        fetchers.shutdownNow();
    }
}
