package org.strangeforest.tcb.stats.spring;

import java.util.*;
import java.util.concurrent.*;

import com.github.benmanes.caffeine.cache.*;
import org.springframework.boot.autoconfigure.cache.*;
import org.springframework.cache.caffeine.*;
import org.springframework.context.annotation.*;

@Configuration
public class AnalyticsCacheConfig {

	@Bean
	public CacheManagerCustomizer<CaffeineCacheManager> analyticsCaches() {
		return manager -> {
			manager.registerCustomCache("PublicApi.Coverage", Caffeine.newBuilder().maximumSize(1)
				.expireAfterWrite(5, TimeUnit.MINUTES).recordStats().build());
			for (var name : List.of("Coverage", "Era", "Performance", "RelativeStats", "SurfaceConvergence", "Peaks", "Calendar", "Matchups", "Draw", "Calibration"))
				manager.registerCustomCache("Analytics." + name, Caffeine.newBuilder().maximumSize(8)
					.expireAfterWrite(15, TimeUnit.MINUTES).recordStats().build());
		};
	}
}
