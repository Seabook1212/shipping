package works.weave.socks.shipping.middleware;

import io.prometheus.client.Histogram;
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

public class HTTPMonitoringInterceptor implements HandlerInterceptor {
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

    @Override
    public boolean preHandle(HttpServletRequest httpServletRequest, HttpServletResponse
            httpServletResponse, Object o) throws Exception {
        httpServletRequest.setAttribute(startTimeKey, System.nanoTime());
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest httpServletRequest, HttpServletResponse
            httpServletResponse, Object o, ModelAndView modelAndView) throws Exception {
        // Skip monitoring for actuator/monitoring endpoints
        String path = httpServletRequest.getServletPath();
        if (path.equals("/metrics") || path.equals("/health") || path.equals("/prometheus")) {
            return;
        }

        long start = (long) httpServletRequest.getAttribute(startTimeKey);
        long elapsed = System.nanoTime() - start;
        double seconds = (double) elapsed / 1000000000.0;
        String matchedUrl = getMatchingURLPattern(httpServletRequest);
        if (!matchedUrl.equals("")) {
            requestLatency.labels(
                    serviceName,
                    httpServletRequest.getMethod(),
                    matchedUrl,
                    Integer.toString(httpServletResponse.getStatus())
            ).observe(seconds);
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest httpServletRequest, HttpServletResponse
            httpServletResponse, Object o, Exception e) throws Exception {
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
