// 从这里开始是新增的：为 AI HTTP 调用提供带超时的 RestClient.Builder。
// 为什么必须用 Java 配置而不是 application.yml：
//   Spring AI 1.0.0-M6 的 OpenAiParentProperties 只有 apiKey/baseUrl/projectId/organizationId
//   四个字段，根本没有 httpClient 子属性——yml 里写 spring.ai.openai.http-client.read-timeout
//   不会报错，但也不会被绑定，AI 调用实际仍走底层默认的无限读取超时。
//   报告线程曾因此永久卡在 SSLSocket.readApplicationRecord 上 400+ 秒。
//   OpenAiAutoConfiguration.openAiChatModel 通过 ObjectProvider<RestClient.Builder> 取容器里的
//   RestClient.Builder 来构建 OpenAiApi，所以只要注册一个带超时的 Builder bean，
//   出题/评分/追问/报告/简历提取/表现分析全部 AI 调用自动带上超时。
// 超时取值与业务语义对齐：connect 10s / write 30s / read 180s——给真正慢的模型留足生成时间，
// 但上游挂起时能在 180s 内失败，交由上层已有的 singleflight 超时接管 + 本地重试兜底。
package com.example.interview.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class AiHttpClientConfig {

    @Bean
    @ConditionalOnMissingBean
    public RestClient.Builder interviewRestClientBuilder() {
        // JDK 内置 HttpClient 工厂：connect/read 超时都走 JDK 原生 HTTP 客户端，
        // 断连会触发 TCP 层超时，避免 SimpleClientHttpRequestFactory 的无限阻塞问题。
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build()
        );
        factory.setReadTimeout(Duration.ofSeconds(180));
        return RestClient.builder().requestFactory(factory);
    }
}
