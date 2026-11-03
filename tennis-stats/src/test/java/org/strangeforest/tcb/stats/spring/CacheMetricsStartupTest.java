package org.strangeforest.tcb.stats.spring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.cache.CacheMetricsAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class CacheMetricsStartupTest {

	@Test
	void namedPlayerCacheAndDynamicCachesRegisterWithoutStartupCycle() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class,
				CacheAutoConfiguration.class, CacheMetricsAutoConfiguration.class))
			.withUserConfiguration(Caching.class, PlayerCacheConfig.class, RegisterCaffeineCachesAspect.class)
			.withPropertyValues("spring.cache.type=caffeine", "spring.cache.caffeine.spec=maximumSize=1000,recordStats")
			.run(context -> {
				assertThat(context).hasNotFailed();
				var manager = context.getBean(CacheManager.class);
				var registry = context.getBean(MeterRegistry.class);
				manager.getCache("Player").put(52602, "$68,050,698");
				assertThat(registry.find("cache.size").tag("cache", "Player").gauge()).isNotNull();
				manager.getCache("NewDynamicCache").put(1, "value");
				assertThat(registry.find("cache.size").tag("cache", "NewDynamicCache").gauge()).isNotNull();
			});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableCaching
	static class Caching {
		@Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
	}
}
