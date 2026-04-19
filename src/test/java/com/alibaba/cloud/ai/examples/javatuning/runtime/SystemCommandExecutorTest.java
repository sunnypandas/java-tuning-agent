package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemCommandExecutorTest {

	@Test
	void shouldAllowWhitelistedJvmCommands() {
		SystemCommandExecutor executor = new SystemCommandExecutor(List.of("jps", "jcmd", "jstat"));

		assertThat(executor.validate(List.of("jcmd", "123", "VM.version"))).isTrue();
	}

	@Test
	void shouldRejectNonWhitelistedCommands() {
		SystemCommandExecutor executor = new SystemCommandExecutor(List.of("jps", "jcmd", "jstat"));

		assertThatThrownBy(() -> executor.run(List.of("bash", "-lc", "rm -rf /")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not in whitelist");
	}

}
