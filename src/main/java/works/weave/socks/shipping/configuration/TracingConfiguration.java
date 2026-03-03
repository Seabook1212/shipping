package works.weave.socks.shipping.configuration;

import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    @Bean
    public SpanHandler kubernetesTagSpanHandler(
            @Value("${tracing.tags.container:${CONTAINER_NAME:${spring.application.name}}}") String container,
            @Value("${tracing.tags.pod:${POD_NAME:unknown}}") String pod,
            @Value("${tracing.tags.namespace:${POD_NAMESPACE:default}}") String namespace,
            @Value("${tracing.tags.node:${NODE_NAME:unknown}}") String node) {
        return new SpanHandler() {
            @Override
            public boolean end(TraceContext context, MutableSpan span, Cause cause) {
                addTag(span, "container", container);
                addTag(span, "pod", pod);
                addTag(span, "namespace", namespace);
                addTag(span, "node", node);
                return true;
            }
        };
    }

    private static void addTag(MutableSpan span, String key, String value) {
        if (value != null && !value.isBlank()) {
            span.tag(key, value);
        }
    }
}
