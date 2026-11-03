package org.strangeforest.tcb.stats.spring;

import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.boot.autoconfigure.cache.CacheManagerCustomizer;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlayerCacheConfig {

	@Bean
	public CacheManagerCustomizer<CaffeineCacheManager> playerCacheExpiry() {
		return playerCacheExpiry(Ticker.systemTicker());
	}

	static CacheManagerCustomizer<CaffeineCacheManager> playerCacheExpiry(Ticker ticker) {
		return manager -> manager.registerCustomCache("Player", Caffeine.newBuilder()
			.maximumSize(1000)
			.expireAfterWrite(30, TimeUnit.MINUTES)
			.ticker(ticker)
			.recordStats()
			.build());
	}
}
