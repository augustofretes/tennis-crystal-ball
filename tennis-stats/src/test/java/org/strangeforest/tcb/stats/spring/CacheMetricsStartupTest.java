package org.strangeforest.tcb.stats.spring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.strangeforest.tcb.stats.service.DataService;
import org.strangeforest.tcb.stats.service.MatchPredictionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CacheMetricsStartupTest {

	@Test
	void namedPlayerCacheAndDynamicCachesRegisterWithoutStartupCycle() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class,
				CacheAutoConfiguration.class, CacheMetricsAutoConfiguration.class))
			.withUserConfiguration(Caching.class, PlayerCacheConfig.class, AnalyticsCacheConfig.class, RegisterCaffeineCachesAspect.class)
			.withPropertyValues("spring.cache.type=caffeine", "spring.cache.caffeine.spec=maximumSize=1000,recordStats")
			.run(context -> {
				assertThat(context).hasNotFailed();
				var manager = context.getBean(CacheManager.class);
				var registry = context.getBean(MeterRegistry.class);
				manager.getCache("Player").put(52602, "$68,050,698");
				assertThat(registry.find("cache.size").tag("cache", "Player").gauge()).isNotNull();
				assertThat(manager.getCache("Analytics.Era")).isNotNull();
				assertThat(registry.find("cache.size").tag("cache", "Analytics.Era").gauge()).isNotNull();
				assertThat(registry.find("cache.size").tag("cache", "Prediction.Players").gauge()).isNotNull();
				manager.getCache("NewDynamicCache").put(1, "value");
				assertThat(registry.find("cache.size").tag("cache", "NewDynamicCache").gauge()).isNotNull();
				assertThat(registry.find("cache.size").tag("cache", "NewDynamicCache")
					.tag("cacheManager", "cacheManager").gauge()).isNotNull();
				assertThat(((PrometheusMeterRegistry)registry).scrape()).contains("cache_gets_total");
			});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableCaching
	static class Caching {
		@Bean MeterRegistry meterRegistry() { return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT); }
		@Bean JdbcTemplate jdbcTemplate() { return mock(JdbcTemplate.class); }
		@Bean NamedParameterJdbcTemplate namedJdbcTemplate() { return mock(NamedParameterJdbcTemplate.class); }
		@Bean MatchPredictionService matchPredictionService() { return new MatchPredictionService(false); }
		@Bean DataService dataService() { return new DataService(); }
	}
}
