package com.aircraftapi.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

@Component
@Order(1)
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final long capacity;

    public RateLimitFilter(
            StringRedisTemplate stringRedisTemplate,
            @Value("${rate-limit.capacity}") long capacity) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.capacity = capacity;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {

        HttpServletRequest  httpRequest  = (HttpServletRequest)  request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String clientIp = extractClientIp(httpRequest);
        String key      = buildRateLimitKey(clientIp);

        Long count;
        try {
            count = stringRedisTemplate.opsForValue().increment(key);
        } catch (Exception e) {
            log.warn("Redis unavailable for rate limiting, allowing request: {}", e.getMessage());
            chain.doFilter(request, response);
            return;
        }

        if (count == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            if (count == 1) {
                stringRedisTemplate.expire(key, Duration.ofSeconds(60));
            }
        } catch (Exception e) {
            log.warn("Redis expire failed: {}", e.getMessage());
        }

        httpResponse.addHeader("X-Rate-Limit-Remaining",
                String.valueOf(Math.max(0, capacity - count)));

        if (count > capacity) {
            log.warn("Rate limit exceeded for IP: {} (count={})", clientIp, count);

            httpResponse.setStatus(429);
            httpResponse.setContentType("application/json");
            httpResponse.addHeader("Retry-After", "60");
            httpResponse.getWriter().write(
                    "{\"status\":429,\"error\":\"Too Many Requests\"," +
                    "\"message\":\"Limit of " + capacity + " requests/min exceeded. " +
                    "Retry after 60 seconds.\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    private String buildRateLimitKey(String clientIp) {
        long currentMinute = Instant.now().getEpochSecond() / 60;
        return "rate:" + clientIp + ":" + currentMinute;
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
