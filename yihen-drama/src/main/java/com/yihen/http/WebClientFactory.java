package com.yihen.http;


import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class WebClientFactory {

    private final Map<String, WebClient> cache = new ConcurrentHashMap<>();

    private HttpClient createLongTimeoutHttpClient() {
        return HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
                .responseTimeout(Duration.ofMinutes(10)) // 允许最多等10分钟
                .doOnConnected(conn -> 
                    conn.addHandlerLast(new ReadTimeoutHandler(10, TimeUnit.MINUTES))
                        .addHandlerLast(new WriteTimeoutHandler(10, TimeUnit.MINUTES))
                );
    }

    public WebClient getWebClient(String baseUrl) {
        return cache.computeIfAbsent(baseUrl,
                url -> WebClient.builder()
                        .baseUrl(url)
                        .clientConnector(new ReactorClientHttpConnector(createLongTimeoutHttpClient()))
                        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .build()
                );
    }

    // 获取图片http请求发送器
    public WebClient getImgWebClient() {
        return cache.computeIfAbsent("IMG",
                url -> {

                    return WebClient.builder()
                            .clientConnector(new ReactorClientHttpConnector(createLongTimeoutHttpClient()))
                            .defaultHeader("User-Agent", "Mozilla/5.0")
                            .defaultHeader("Accept", "image/*")
                            .build();
                }
        );
    }

    // 获取视频 http 请求发送器
    public WebClient getVideoWebClient() {
        return cache.computeIfAbsent("VIDEO",
                key -> WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(createLongTimeoutHttpClient()))
                        .defaultHeader("User-Agent", "Mozilla/5.0")
                        .defaultHeader("Accept", "video/*")
                        .build()
        );
    }


}
