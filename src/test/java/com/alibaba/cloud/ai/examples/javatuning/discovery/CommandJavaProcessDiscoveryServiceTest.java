package com.alibaba.cloud.ai.examples.javatuning.discovery;

import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.CommandExecutor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class CommandJavaProcessDiscoveryServiceTest {

	@Test
	void shouldParseJpsOutputIntoDescriptors() {
		CommandExecutor executor = mock(CommandExecutor.class);
		given(executor.run(List.of("jps", "-lvm"))).willReturn("""
				12345 org.springframework.boot.loader.launch.JarLauncher -Dspring.application.name=orders --server.port=8081
				22334 com.example.jobs.JobApplication --spring.profiles.active=prod
				""");

		CommandJavaProcessDiscoveryService service = new CommandJavaProcessDiscoveryService(executor,
				new ProcessDisplayNameResolver());

		List<JavaApplicationDescriptor> applications = service.listJavaApplications();

		assertThat(applications)
				.extracting(JavaApplicationDescriptor::displayName)
				.containsExactly("orders", "JobApplication");

		assertThat(applications)
				.extracting(JavaApplicationDescriptor::profilesHint)
				.containsExactly(List.of(), List.of("prod"));

		assertThat(applications.get(0).portHints()).containsExactly(8081);
		assertThat(applications.get(0).applicationTypeHint()).isEqualTo("spring-boot");
		assertThat(applications.get(0).discoveryConfidence()).isEqualTo("medium");
	}

}
