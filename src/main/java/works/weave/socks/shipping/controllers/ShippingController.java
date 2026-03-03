package works.weave.socks.shipping.controllers;

import com.rabbitmq.client.Channel;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import works.weave.socks.shipping.entities.HealthCheck;
import works.weave.socks.shipping.entities.Shipment;

import jakarta.validation.Valid;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
public class ShippingController {

    private static final Logger logger = LoggerFactory.getLogger(ShippingController.class);
    private static final String QUEUE_NAME = "shipping-task";

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    Tracer tracer;

    @Value("${spring.rabbitmq.host}")
    private String rabbitMqHost;

    @Value("${shipping.rabbitmq.connection-timeout-ms:4000}")
    private long rabbitMqConnectionTimeoutMs;

    private final AtomicBoolean rabbitMqAvailable = new AtomicBoolean(true);

    @RequestMapping(value = "/shipping", method = RequestMethod.GET)
    public String getShipping() {
        return "GET ALL Shipping Resource.";
    }

    @RequestMapping(value = "/shipping/{id}", method = RequestMethod.GET)
    public String getShippingById(@PathVariable String id) {
        return "GET Shipping Resource with id: " + id;
    }

    @ResponseStatus(HttpStatus.CREATED)
    @RequestMapping(value = "/shipping", method = RequestMethod.POST)
    public @ResponseBody Shipment postShipping(@Valid @RequestBody Shipment shipment) {
        Span producerSpan = tracer.spanBuilder()
                .name("rabbitmq:publish " + QUEUE_NAME)
                .kind(Span.Kind.PRODUCER)
                .start();
        long startNanos = System.nanoTime();
        String shipmentId = shipment.getId();
        logger.info(
                "Shipment publish requested dependency=rabbitmq operation=publish queue={} host={} shipmentId={} timeout_ms={}",
                QUEUE_NAME,
                rabbitMqHost,
                shipmentId,
                rabbitMqConnectionTimeoutMs);

        try (Tracer.SpanInScope ws = tracer.withSpan(producerSpan)) {
            producerSpan.tag("messaging.system", "rabbitmq");
            producerSpan.tag("messaging.destination", QUEUE_NAME);

            rabbitTemplate.convertAndSend(QUEUE_NAME, shipment);
        } catch (MessageConversionException e) {
            tagException(producerSpan, e);
            logger.warn(
                    "Shipment publish rejected dependency=rabbitmq operation=publish queue={} host={} shipmentId={} latency_ms={} timeout_ms={} error_class=serialization",
                    QUEUE_NAME,
                    rabbitMqHost,
                    shipmentId,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos),
                    rabbitMqConnectionTimeoutMs,
                    e);
        } catch (AmqpException e) {
            tagException(producerSpan, e);
            logger.error(
                    "RabbitMQ publish failed dependency=rabbitmq operation=publish queue={} host={} shipmentId={} latency_ms={} timeout_ms={} error_class={}",
                    QUEUE_NAME,
                    rabbitMqHost,
                    shipmentId,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos),
                    rabbitMqConnectionTimeoutMs,
                    classifyRabbitFailure(e),
                    e);
        } catch (RuntimeException e) {
            tagException(producerSpan, e);
            logger.error(
                    "Unexpected shipment publish failure dependency=rabbitmq operation=publish queue={} host={} shipmentId={} latency_ms={} error_class=unexpected_runtime",
                    QUEUE_NAME,
                    rabbitMqHost,
                    shipmentId,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos),
                    e);
        } finally {
            producerSpan.end();
        }
        return shipment;
    }

    @ResponseStatus(HttpStatus.OK)
    @RequestMapping(method = RequestMethod.GET, path = "/health")
    public @ResponseBody Map<String, List<HealthCheck>> getHealth() {
        Map<String, List<HealthCheck>> map = new HashMap<String, List<HealthCheck>>();
        List<HealthCheck> healthChecks = new ArrayList<HealthCheck>();
        Date dateNow = Calendar.getInstance().getTime();
        long startNanos = System.nanoTime();

        HealthCheck rabbitmq = new HealthCheck("shipping-rabbitmq", "OK", dateNow);
        HealthCheck app = new HealthCheck("shipping", "OK", dateNow);

        try {
            this.rabbitTemplate.execute(new ChannelCallback<String>() {
                @Override
                public String doInRabbit(Channel channel) throws Exception {
                    Map<String, Object> serverProperties = channel.getConnection().getServerProperties();
                    return serverProperties.get("version").toString();
                }
            });
            logRabbitHealthRecoveryIfNeeded(startNanos);
        } catch (AmqpException e) {
            rabbitmq.setStatus("err");
            logRabbitHealthFailureIfNeeded(startNanos, e);
        } catch (RuntimeException e) {
            rabbitmq.setStatus("err");
            logRabbitHealthFailureIfNeeded(startNanos, e);
        }

        healthChecks.add(rabbitmq);
        healthChecks.add(app);

        map.put("health", healthChecks);
        return map;
    }

    private void logRabbitHealthFailureIfNeeded(long startNanos, Exception error) {
        if (rabbitMqAvailable.compareAndSet(true, false)) {
            logger.warn(
                    "RabbitMQ health check failed dependency=rabbitmq operation=health_check host={} latency_ms={} timeout_ms={} error_class={}",
                    rabbitMqHost,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos),
                    rabbitMqConnectionTimeoutMs,
                    classifyRabbitFailure(error),
                    error);
        }
    }

    private void logRabbitHealthRecoveryIfNeeded(long startNanos) {
        if (rabbitMqAvailable.compareAndSet(false, true)) {
            logger.info(
                    "RabbitMQ health check recovered dependency=rabbitmq operation=health_check host={} latency_ms={}",
                    rabbitMqHost,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
        }
    }

    private String classifyRabbitFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof UnknownHostException) {
                return "dns_failure";
            }
            if (current instanceof SocketTimeoutException) {
                return "timeout";
            }
            if (current instanceof ConnectException) {
                return "connection_refused";
            }
            current = current.getCause();
        }

        if (error instanceof MessageConversionException) {
            return "serialization";
        }

        String simpleName = error.getClass().getSimpleName().toLowerCase();
        if (simpleName.contains("timeout")) {
            return "timeout";
        }

        return "mq_publish_failure";
    }

    private void tagException(Span span, Throwable error) {
        span.error(error);
        String exceptionType = error.getClass().getSimpleName();
        String exceptionMessage = error.getMessage() != null ? error.getMessage() : "";
        span.tag("error.type", exceptionType);
        span.tag("error.message", exceptionMessage);
        span.tag("exception.type", exceptionType);
        span.tag("exception.message", exceptionMessage);
    }
}
