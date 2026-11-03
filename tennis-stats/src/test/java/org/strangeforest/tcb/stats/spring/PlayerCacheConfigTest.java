package org.strangeforest.tcb.stats.spring;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerCacheConfigTest {

	@Test
	void popularProfilesExpireDespiteContinuedReads() {
		var time = new AtomicLong();
		var manager = new CaffeineCacheManager();
		manager.setCacheSpecification("maximumSize=1000,expireAfterAccess=30m");
		PlayerCacheConfig.playerCacheExpiry(time::get).customize(manager);
		var cache = manager.getCache("Player");
		cache.put(52602, "$1,089,590");

		for (int minute = 1; minute < 30; minute++) {
			time.set(TimeUnit.MINUTES.toNanos(minute));
			assertThat(cache.get(52602, String.class)).isEqualTo("$1,089,590");
		}
		time.set(TimeUnit.MINUTES.toNanos(30));
		assertThat(cache.get(52602)).isNull();
		cache.put(52602, "$68,050,698");
		assertThat(cache.get(52602, String.class)).isEqualTo("$68,050,698");
	}
}
