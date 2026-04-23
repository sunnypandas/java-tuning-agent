package com.alibaba.cloud.ai.examples.javatuning.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepeatedSamplingRequestTest {

	@Test
	void shouldNormalizeBlankRequestFieldsToDefaults() {
		RepeatedSamplingProperties props = RepeatedSamplingProperties.defaults();
		RepeatedSamplingRequest normalized = new RepeatedSamplingRequest(123L, null, null, true, true, "")
			.normalized(props);

		assertThat(normalized.sampleCount()).isEqualTo(3);
		assertThat(normalized.intervalMillis()).isEqualTo(10_000L);
		assertThat(normalized.includeThreadCount()).isTrue();
		assertThat(normalized.includeClassCount()).isTrue();
	}

	@Test
	void shouldRejectSamplingWindowBeyondConfiguredLimit() {
		RepeatedSamplingProperties props = new RepeatedSamplingProperties(3, 10_000L, 20, 20_000L);

		assertThatThrownBy(() -> new RepeatedSamplingRequest(123L, 5, 10_000L, true, true, "").normalized(props))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("max-total-duration");
	}

}
