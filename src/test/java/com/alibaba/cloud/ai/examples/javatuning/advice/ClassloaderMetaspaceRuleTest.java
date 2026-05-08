package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassloaderMetaspaceEntry;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassloaderMetaspaceSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.NativeMemorySummary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClassloaderMetaspaceRuleTest {

	@Test
	void shouldReportRepeatedGeneratedClassloaderPattern() {
		MemoryGcEvidencePack evidence = baseEvidence().withClassloaderMetaspaceSummary(
				new ClassloaderMetaspaceSummary(List.of(entry("com.example.ProxyClassLoader", 120L, 8_388_608L),
						entry("com.example.ProxyClassLoader", 110L, 8_388_608L),
						entry("com.example.ProxyClassLoader", 105L, 8_388_608L)), 335L, 25_165_824L, List.of()));
		DiagnosisScratch scratch = new DiagnosisScratch();

		new ClassloaderMetaspaceRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

		assertThat(scratch.findings()).extracting(TuningFinding::title)
			.contains("Suspected classloader retention or churn");
		assertThat(scratch.findings().get(0).evidence()).contains("repeatedLoaderPattern=proxy");
	}

	@Test
	void shouldReportNmtClassGrowthCorroboration() {
		NativeMemorySummary nativeSummary = new NativeMemorySummary(1024L * 1024L * 1024L,
				512L * 1024L * 1024L, null, null, 128L * 1024L * 1024L, 96L * 1024L * 1024L,
				Map.of("class", new NativeMemorySummary.CategoryUsage(128L * 1024L * 1024L, 96L * 1024L * 1024L)),
				Map.of("class", new NativeMemorySummary.CategoryGrowth(64L * 1024L * 1024L, 48L * 1024L * 1024L)),
				List.of());
		MemoryGcEvidencePack evidence = baseEvidence().withNativeMemorySummary(nativeSummary)
			.withClassloaderMetaspaceSummary(new ClassloaderMetaspaceSummary(
					List.of(entry("com.example.PluginClassLoader", 100L, 2_097_152L)), 100L, 2_097_152L,
					List.of()));
		DiagnosisScratch scratch = new DiagnosisScratch();

		new ClassloaderMetaspaceRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

		assertThat(scratch.findings()).extracting(TuningFinding::title)
			.contains("Suspected classloader retention or churn");
		assertThat(scratch.findings().get(0).evidence()).contains("nmtClassGrowthCorroborated=true");
	}

	private static ClassloaderMetaspaceEntry entry(String name, long classCount, long bytes) {
		return new ClassloaderMetaspaceEntry(name, "0x0", classCount, bytes, true, name);
	}

	private static MemoryGcEvidencePack baseEvidence() {
		JvmRuntimeSnapshot snapshot = new JvmRuntimeSnapshot(1961L,
				new JvmMemorySnapshot(128L * 1024L * 1024L, 256L * 1024L * 1024L, 1024L * 1024L * 1024L, null,
						null, 96L * 1024L * 1024L, 120L * 1024L * 1024L, 256L * 1024L * 1024L, null, null),
				new JvmGcSnapshot("G1", 0L, 0L, 0L, 0L, 20.0d, 70.0d, 65.0d), List.of(), "", null, 1_000L,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
		return new MemoryGcEvidencePack(snapshot, null, null, List.of(), List.of(), null, null);
	}

}
