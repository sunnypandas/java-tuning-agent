package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("""
		Structured hints so advice can correlate JVM evidence with local source. \
		sourceRoots should be existing directories containing .java files (absolute paths are most reliable). \
		candidatePackages lists application base package prefixes used to prioritize histogram classes.""")
public record CodeContextSummary(
		@JsonPropertyDescription("Optional Maven/Gradle coordinates or dependency names as free-form strings.") List<String> dependencies,
		@JsonPropertyDescription("Optional key/value configuration snippets relevant to tuning (profiles, pool sizes, etc.).") Map<String, String> configuration,
		@JsonPropertyDescription("Spring @SpringBootApplication or other main class simple names if known.") List<String> applicationNames,
		@JsonPropertyDescription("Local source tree roots to search for .java files when mapping class histogram entries.") List<String> sourceRoots,
		@JsonPropertyDescription("FQCN package prefixes (e.g. com.example.app) to prefer when ranking suspected hotspots.") List<String> candidatePackages) {

	public CodeContextSummary {
		sourceRoots = sourceRoots == null ? List.of() : List.copyOf(sourceRoots);
		candidatePackages = candidatePackages == null ? List.of() : List.copyOf(candidatePackages);
	}

	/** Backward-compatible factory when source roots are unknown. */
	public static CodeContextSummary withoutSource(List<String> dependencies, Map<String, String> configuration,
			List<String> applicationNames) {
		return new CodeContextSummary(dependencies, configuration, applicationNames, List.of(), List.of());
	}
}
