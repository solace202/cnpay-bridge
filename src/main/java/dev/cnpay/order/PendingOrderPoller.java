package dev.cnpay.order;

import dev.cnpay.config.CnpayProperties;
import dev.cnpay.provider.ProviderException;
import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Notifications are "at least once, eventually" — or never, if the merchant was down for all retries.
 * This sweeper queries PENDING orders that should have heard back by now and closes expired ones.
 */
@Component
public class PendingOrderPoller {

    private static final Logger log = LoggerFactory.getLogger(PendingOrderPoller.class);

    private final OrderRepository orders;
    private final OrderService service;
    private final CnpayProperties properties;
    private final Clock clock;

    public PendingOrderPoller(OrderRepository orders, OrderService service, CnpayProperties properties, Clock clock) {
        this.orders = orders;
        this.service = service;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${cnpay.poller.interval:30s}", initialDelayString = "${cnpay.poller.initial-delay:20s}")
    public void scheduled() {
        sweep();
    }

    /** @return how many orders left PENDING */
    public int sweep() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(properties.poller().minAge());
        int changed = 0;
        for (Order order : orders.pendingOlderThan(cutoff, properties.poller().batchSize())) {
            try {
                if (service.sync(order).status() != OrderStatus.PENDING) {
                    changed++;
                }
            } catch (ProviderException e) {
                log.warn("Query for {} failed, will retry next sweep: {}", order.outTradeNo(), e.getMessage());
            }
        }
        if (changed > 0) {
            log.info("Pending-order sweep settled {} order(s)", changed);
        }
        return changed;
    }
}
