package com.tickmap.upstream;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(UpstreamProperties.class)
public class UpstreamConfig {

    @Bean
    RestClient tickApiRestClient(UpstreamProperties props) {
        return restClientBuilder(props).build();
    }

    @Bean
    RetryExecutor upstreamRetryExecutor(UpstreamProperties props) {
        return new RetryExecutor(props.retry());
    }

    // Without a read timeout, a server that accepts the connection but never replies hangs the sync.
    public static RestClient.Builder restClientBuilder(UpstreamProperties props) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.readTimeout());
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .defaultHeader("User-Agent", "tickmap-service/1.0");
    }
}
