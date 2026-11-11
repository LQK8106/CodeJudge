package com.codejudge.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 网关统一生成/透传 requestId，并写入下游请求头与响应头。
 *
 * <p>架构上 requestId 应由网关生成，但此前网关只在异常响应里读取、正常请求从未生成：
 * 进入 submission 后才由拦截器补一个 —— 客户端看到的 id 与网关日志无关联，
 * 跨 MQ 后 worker 侧更是完全丢失。本过滤器把起点前移到网关：
 * <ol>
 *   <li>客户端带合法 requestId 则透传（便于前端/压测串联），否则生成 32 位 hex；</li>
 *   <li>写回响应头，客户端日志可直接与后端对齐；</li>
 *   <li>注入下游请求头，submission 的 RequestIdInterceptor 会沿用而不是重新生成。</li>
 * </ol>
 *
 * <p>注意：WebFlux 的 MDC 不会随 Reactor 线程切换自动传播，这里只保证网关自身
 * 同步日志（[req=]）有值；异步链路的关联依赖请求头 + MQ 用户属性传递，不依赖 MDC 穿透。
 */
@Slf4j
@Component
public class RequestIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String REQUEST_ID_HEADER = "requestId";

    /** 与后端一致的安全字符集：防日志注入，也保证能写进 RocketMQ 用户属性。 */
    private static final String SAFE_ID_PATTERN = "[A-Za-z0-9_-]{1,64}";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        if (requestId == null || !requestId.matches(SAFE_ID_PATTERN)) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }
        String rid = requestId;

        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(REQUEST_ID_HEADER, rid)
                .build();
        // 在响应提交前 set（而不是 add）：代理层会把下游服务写入的同名响应头一并拷贝，
        // set 可将其折叠为单值，避免客户端收到 "id, id" 的重复头。
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, rid);
            return Mono.empty();
        });

        MDC.put(REQUEST_ID_HEADER, rid);
        try {
            log.info("请求进入：{} {}", exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath().value());
        } finally {
            MDC.remove(REQUEST_ID_HEADER);
        }
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
