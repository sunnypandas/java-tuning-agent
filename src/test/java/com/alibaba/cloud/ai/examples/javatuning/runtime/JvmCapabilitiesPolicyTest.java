package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class JvmCapabilitiesPolicyTest {

	private final JvmCapabilitiesPolicy policy = new JvmCapabilitiesPolicy();

	@ParameterizedTest
	@MethodSource("matrix")
	void shouldNotGateNmtOnCollectorJdkMatrix(String collector, String version, boolean warningExpected) {
		JvmCapabilitiesPolicy.NativeMemoryCapability capability = policy.nativeMemoryCapability(collector, version);
		assertThat(capability.nmtSupported()).isTrue();
		assertThat(capability.missingData()).isEmpty();
		if (warningExpected) {
			assertThat(capability.warnings()).isNotEmpty();
		}
	}

	private static Stream<Arguments> matrix() {
		return Stream.of(Arguments.of("G1", "11.0.22", false), Arguments.of("Parallel", "17.0.10", false),
				Arguments.of("ZGC", "21.0.3", false), Arguments.of("Serial", "25.0.0", false),
				Arguments.of("CMS", "11.0.22", false), Arguments.of("CMS", "17.0.10", true),
				Arguments.of("unknown", "21.0.3", true));
	}

	@Test
	void shouldSupportConfiguredCollectorAndJdkCombos() {
		assertThat(policy.nativeMemoryCapability("G1", "17.0.10").nmtSupported()).isTrue();
		assertThat(policy.nativeMemoryCapability("Parallel", "21.0.2").nmtSupported()).isTrue();
		assertThat(policy.nativeMemoryCapability("ZGC", "25.0.1").nmtSupported()).isTrue();
		assertThat(policy.nativeMemoryCapability("Serial", "11.0.21").nmtSupported()).isTrue();
	}

	@Test
	void shouldWarnForCmsOnNewJdksWithoutDisablingNmt() {
		JvmCapabilitiesPolicy.NativeMemoryCapability cap = policy.nativeMemoryCapability("CMS", "21.0.2");
		assertThat(cap.nmtSupported()).isTrue();
		assertThat(cap.missingData()).isEmpty();
		assertThat(cap.warnings()).anyMatch(w -> w.contains("CMS is unsupported on JDK 21"));
	}

	@Test
	void shouldParseLegacyJavaEightVersionAsLegacyWarningOnly() {
		JvmCapabilitiesPolicy.NativeMemoryCapability cap = policy.nativeMemoryCapability("G1", "1.8.0_392");

		assertThat(cap.nmtSupported()).isTrue();
		assertThat(cap.warnings()).contains("Native memory evidence may be partial: unknown/legacy JDK capabilities");
	}

}
