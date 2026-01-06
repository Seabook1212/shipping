package works.weave.socks.shipping.configuration;

import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 3.x has built-in Prometheus support via Micrometer.
 * This configuration class is kept for compatibility but is no longer needed.
 * Prometheus metrics are automatically available at /actuator/prometheus
 * when micrometer-registry-prometheus is on the classpath.
 */
@Configuration
class PrometheusAutoConfiguration {
    // No beans needed - Spring Boot 3.x auto-configures Prometheus support
}
