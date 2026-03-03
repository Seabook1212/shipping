package works.weave.socks.shipping.middleware;

import io.prometheus.client.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class HTTPMonitoringInterceptor implements HandlerInterceptor {
    private static final Logger logger = LoggerFactory.getLogger(HTTPMonitoringInterceptor.class);
    static final Histogram requestLatency = Histogram.build()
            .name("request_duration_seconds")
            .help("Request duration in seconds.")
            .labelNames("service", "method", "route", "status_code")
            .register();

    private static final String startTimeKey = "startTime";

    @Autowired
    RequestMappingHandlerMapping requestMappingHandlerMapping;

    private Set<PatternsRequestCondition> urlPatterns;

    @Value("${spring.application.name:shipping}")
    private String serviceName;

    @Value("${observability.http.slow-request-threshold-ms:1000}")
    private long slowRequestThresholdMs;

    @Override
    public boolean preHandle(HttpServletRequest httpServletRequest, HttpServletResponse
            httpServletResponse, Object o) throws Exception {
        httpServletRequest.setAttribute(startTimeKey, System.nanoTime());
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest httpServletRequest, HttpServletResponse
            httpServletResponse, Object o, ModelAndView modelAndView) throws Exception {
    }

    @Override
    public void afterCompletion(HttpServletRequest httpServletRequest, HttpServletResponse
            httpServletResponse, Object o, Exception e) throws Exception {
        String path = httpServletRequest.getServletPath();
        if (path.equals("/metrics") || path.equals("/health") || path.equals("/prometheus")) {
            return;
        }

        Object startTime = httpServletRequest.getAttribute(startTimeKey);
        if (!(startTime instanceof Long)) {
            return;
        }

        long elapsedNanos = System.nanoTime() - (Long) startTime;
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        String matchedUrl = getMatchingURLPattern(httpServletRequest);
        if (!matchedUrl.equals("")) {
            requestLatency.labels(
                    serviceName,
                    httpServletRequest.getMethod(),
                    matchedUrl,
                    Integer.toString(httpServletResponse.getStatus())
            ).observe((double) elapsedNanos / 1000000000.0);
        }

        if (elapsedMs >= slowRequestThresholdMs) {
            logger.warn(
                    "Slow request detected operation=http_request path={} route={} method={} status={} latency_ms={} error_class={}",
                    httpServletRequest.getRequestURI(),
                    matchedUrl,
                    httpServletRequest.getMethod(),
                    httpServletResponse.getStatus(),
                    elapsedMs,
                    e != null ? e.getClass().getSimpleName() : "none"
            );
        }
    }

    private String getMatchingURLPattern(HttpServletRequest httpServletRequest) {
        String res = "";
        for (PatternsRequestCondition pattern : getUrlPatterns()) {
            // Add null check for pattern to avoid NullPointerException
            if (pattern != null) {
                PatternsRequestCondition matchingCondition = pattern.getMatchingCondition(httpServletRequest);
                if (matchingCondition != null && !httpServletRequest.getServletPath().equals("/error")) {
                    res = matchingCondition.getPatterns().iterator().next();
                    break;
                }
            }
        }
        return res;
    }

    private Set<PatternsRequestCondition> getUrlPatterns() {
        if (this.urlPatterns == null) {
            this.urlPatterns = new HashSet<>();
            // Collect all URL patterns from standard Spring MVC request mappings
            requestMappingHandlerMapping.getHandlerMethods().forEach((mapping, handlerMethod) ->
                    urlPatterns.add(mapping.getPatternsCondition()));
        }
        return this.urlPatterns;
    }
}
