package com.example.interview.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service 
public class AliyunNlsTokenService
{
    private static final String TOKEN_URL =
            "https://nls-meta.cn-shanghai.aliyuncs.com/oauth/token";

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String appKey;
    private final String accessKeyId;
    private final String accessKeySecret;

    // ---- Token 缓存 ----
    // volatile：保证多线程下读到的缓存值是内存中的最新值
    private volatile String cachedToken;
    // 缓存 Token 的过期时间戳（epoch 秒），到达后必须重新换取
    private volatile long expireAt;

    public AliyunNlsTokenService(
            RestClient.Builder builder,
            ObjectMapper mapper,
            @Value("${aliyun.nls.app-key}") String appKey,
            @Value("${aliyun.nls.access-key-id}") String accessKeyId,
            @Value("${aliyun.nls.access-key-secret}") String accessKeySecret
    ) {
        this.restClient = builder.build();
        this.mapper = mapper;
        this.appKey = appKey;
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
    }
    public String getToken()
    {
        if(cachedToken != null && expireAt > System.currentTimeMillis() / 1000)
        {
            return cachedToken;
        }
        synchronized(this)
        {
            if(cachedToken != null && expireAt > System.currentTimeMillis() / 1000)
            {
                return cachedToken;
            }
            refreshToken();
            return cachedToken;
        }
    }
    public String getAppKey() {
        return appKey;
    }
    private void refreshToken() {
        String body = restClient.get()
                .uri(
                        TOKEN_URL
                                + "?app_key={appKey}"
                                + "&access_key_id={accessKeyId}"
                                + "&access_key_secret={accessKeySecret}",
                        appKey,
                        accessKeyId,
                        accessKeySecret
                )
                .retrieve()
                .body(String.class);

        try {
            JsonNode node = mapper.readTree(body);
            cachedToken = node.path("token").asText();
            expireAt = node.path("expire_time").asLong();
        } catch (Exception error) {
            // 换 Token 失败（网络异常、密钥错误等）不允许静默通过，
            // 直接抛出，让前端提示用户稍后重试。
            throw new IllegalStateException(
                    "cannot acquire aliyun nls token",
                    error
            );
        }
    }
}