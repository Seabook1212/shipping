package works.weave.socks.shipping.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import io.micrometer.observation.ObservationPredicate;

/**
 * Configuration to filter out actuator endpoints from distributed tracing.
 * This prevents /health, /metrics, and /prometheus endpoints from generating
 * traces in Jaeger.
 */
@Configuration
public class TracingConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(TracingConfiguration.class);

    @Bean
    public ObservationPredicate skipHealthCheckTracing() {
        return (name, context) -> {
            // Only filter HTTP server observations
            if (context instanceof ServerRequestObservationContext) {
                ServerRequestObservationContext serverContext = (ServerRequestObservationContext) context;
                String uri = serverContext.getCarrier().getRequestURI();

                // List of endpoints to exclude from tracing
                boolean shouldSkip = uri != null && (uri.equals("/health") ||
                        uri.equals("/metrics") ||
                        uri.equals("/prometheus") ||
                        uri.startsWith("/actuator/"));

                if (shouldSkip) {
                    logger.trace("Skipping trace for endpoint: {}", uri);
                    return false; // Don't observe (skip tracing)
                }
            }

            return true; // Observe (create trace)
        };
    }
}
