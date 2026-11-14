package dev.cnpay.provider;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ProviderRegistry {

    private final Map<Provider, PaymentProvider> providers = new EnumMap<>(Provider.class);

    public ProviderRegistry(List<PaymentProvider> all) {
        all.forEach(p -> providers.put(p.id(), p));
    }

    public PaymentProvider get(Provider provider) {
        PaymentProvider p = providers.get(provider);
        if (p == null) {
            throw new IllegalStateException("No adapter for " + provider);
        }
        return p;
    }
}
