package works.weave.socks.shipping.controllers;

import com.rabbitmq.client.Channel;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import works.weave.socks.shipping.entities.HealthCheck;
import works.weave.socks.shipping.entities.Shipment;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class ShippingController {

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    Tracer tracer;

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
    public @ResponseBody Shipment postShipping(@RequestBody Shipment shipment) {
        // 创建一个专门的 producer span (Micrometer Tracing)
        Span producerSpan = tracer.nextSpan().name("rabbitmq:publish shipping-task");

        System.out.println("Adding shipment to queue...");
        try (Tracer.SpanInScope ws = tracer.withSpan(producerSpan.start())) {
            // 给 span 加一些 tag，后续你做 dataset 很有用
            producerSpan.tag("messaging.system", "rabbitmq");
            producerSpan.tag("messaging.destination", "shipping-task");
            producerSpan.tag("span.kind", "producer");

            rabbitTemplate.convertAndSend("shipping-task", shipment);
        } catch (Exception e) {
            System.out.println("Unable to add to queue (the queue is probably down). Accepting anyway. Don't do this " +
                    "for real!");
            producerSpan.error(e);
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
        } catch (AmqpException e) {
            rabbitmq.setStatus("err");
        }

        healthChecks.add(rabbitmq);
        healthChecks.add(app);

        map.put("health", healthChecks);
        return map;
    }
}
