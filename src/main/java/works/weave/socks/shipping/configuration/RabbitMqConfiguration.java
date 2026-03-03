package works.weave.socks.shipping.configuration;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMqConfiguration.class);
    final static String queueName = "shipping-task";

    @Value("${spring.rabbitmq.host}")
    private String host;

    @Value("${shipping.rabbitmq.connection-timeout-ms:4000}")
    private int rabbitMqConnectionTimeoutMs;

    @Value("${shipping.rabbitmq.warmup.enabled:true}")
    private boolean rabbitWarmupEnabled;

    @Value("${shipping.rabbitmq.warmup.fail-fast:false}")
    private boolean rabbitWarmupFailFast;

    @Autowired
    private Tracer tracer;

    @Bean
    public ConnectionFactory connectionFactory() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(host);
        connectionFactory.setCloseTimeout(rabbitMqConnectionTimeoutMs);
        connectionFactory.setConnectionTimeout(rabbitMqConnectionTimeoutMs);
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        connectionFactory.setUsername("guest");
        connectionFactory.setPassword("guest");
        return connectionFactory;
    }

    @Bean
    public AmqpAdmin amqpAdmin() {
        return new RabbitAdmin(connectionFactory());
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate() {
        RabbitTemplate template = new RabbitTemplate(connectionFactory());
        template.setMessageConverter(jsonMessageConverter());
        template.setMandatory(true);

        template.setBeforePublishPostProcessors(new MessagePostProcessor() {
            @Override
            public Message postProcessMessage(Message message) {
                Span current = tracer.currentSpan();
                if (current != null) {
                    TraceContext context = current.context();
                    String traceId = context.traceId();
                    String spanId = context.spanId();

                    message.getMessageProperties().setHeader("X-B3-TraceId", traceId);
                    message.getMessageProperties().setHeader("X-B3-SpanId", spanId);
                    message.getMessageProperties().setHeader("X-B3-Sampled", "1");
                }
                return message;
            }
        });
        template.setReturnsCallback(returned ->
                logger.error(
                        "RabbitMQ returned message dependency=rabbitmq operation=publish exchange={} routing_key={} reply_code={} reply_text={} error_class=mq_unroutable",
                        returned.getExchange(),
                        returned.getRoutingKey(),
                        returned.getReplyCode(),
                        returned.getReplyText()
                )
        );
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                logger.error(
                        "RabbitMQ publish nack dependency=rabbitmq operation=publish correlation_id={} error_class=mq_nack cause={}",
                        correlationData != null ? correlationData.getId() : null,
                        cause
                );
            }
        });

        return template;
    }

    @Bean
    public ApplicationRunner rabbitStartupWarmup(AmqpAdmin amqpAdmin, RabbitTemplate rabbitTemplate) {
        return args -> {
            if (!rabbitWarmupEnabled) {
                return;
            }

            long startNanos = System.nanoTime();
            try {
                rabbitTemplate.execute(channel -> null);
                amqpAdmin.initialize();
                logger.info(
                        "RabbitMQ warmup completed dependency=rabbitmq operation=startup_warmup host={} queue={} latency_ms={} timeout_ms={}",
                        host,
                        queueName,
                        (System.nanoTime() - startNanos) / 1_000_000,
                        rabbitMqConnectionTimeoutMs
                );
            } catch (RuntimeException exception) {
                logger.error(
                        "RabbitMQ warmup failed dependency=rabbitmq operation=startup_warmup host={} queue={} latency_ms={} timeout_ms={} error_class={}",
                        host,
                        queueName,
                        (System.nanoTime() - startNanos) / 1_000_000,
                        rabbitMqConnectionTimeoutMs,
                        classifyRabbitWarmupFailure(exception),
                        exception
                );
                if (rabbitWarmupFailFast) {
                    throw exception;
                }
            }
        };
    }

    @Bean
    Queue queue() {
        return new Queue(queueName, false);
    }

    @Bean
    TopicExchange exchange() {
        return new TopicExchange("shipping-task-exchange");
    }

    @Bean
    Binding binding(Queue queue, TopicExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(queueName);
    }

    private String classifyRabbitWarmupFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String simpleName = current.getClass().getSimpleName().toLowerCase();
            if (simpleName.contains("timeout")) {
                return "timeout";
            }
            if (simpleName.contains("unknownhost")) {
                return "dns_failure";
            }
            if (simpleName.contains("connect")) {
                return "connection_refused";
            }
            current = current.getCause();
        }
        return "mq_startup_failure";
    }
}
