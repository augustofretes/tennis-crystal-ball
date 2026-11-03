package org.strangeforest.tcb.stats.spring;

import io.micrometer.core.instrument.*;
import org.aspectj.lang.annotation.*;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.actuate.metrics.cache.*;
import org.springframework.cache.*;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.*;

import static org.springframework.beans.factory.config.BeanDefinition.*;

@Aspect
@Component @Role(ROLE_INFRASTRUCTURE)
@Profile("!dev")
public class RegisterCaffeineCachesAspect implements SmartInitializingSingleton {

	@Autowired private ObjectProvider<CacheMetricsRegistrar> metricsRegistrars;
	@Autowired private ObjectProvider<CacheManager> cacheManagers;
	private volatile CacheMetricsRegistrar metricsRegistrar;

	@Override public void afterSingletonsInstantiated() {
		// Boot binds preconfigured caches while constructing the registrar itself.
		// Dynamic cache registration must wait until that construction is complete.
		metricsRegistrar = metricsRegistrars.getObject();
		cacheManagers.orderedStream().forEach(manager -> manager.getCacheNames().forEach(
			name -> registerCaffeineCache(manager.getCache(name))));
	}

	@AfterReturning(pointcut="execution(* org.springframework.cache.caffeine.CaffeineCacheManager.getCache(java.lang.String))", returning="cache")
	public void registerCaffeineCache(Cache cache) {
		var registrar = metricsRegistrar;
		if (registrar != null && cache != null)
			registrar.bindCacheToRegistry(cache, Tag.of("cacheManager", "cacheManager"));
	}
}
