package com.alibaba.cloud.ai.examples.javatuning.discovery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessDisplayNameResolverTest {

	@Test
	void shouldPreferSpringApplicationNameProperty() {
		ProcessDisplayNameResolver resolver = new ProcessDisplayNameResolver();
		String displayName = resolver.resolveDisplayName(
				"org.springframework.boot.loader.launch.JarLauncher -Dspring.application.name=inventory");

		assertThat(displayName).isEqualTo("inventory");
	}

}
