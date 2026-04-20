package com.alibaba.cloud.ai.examples.javatuning.runtime;

/**
 * One row from a shallow-by-class aggregation over a heap dump (see {@link HeapDumpShallowSummary}).
 *
 * @param approxSharePercent share of {@link HeapDumpShallowSummary#totalTrackedShallowBytes()} (0–100)
 */
public record HeapShallowClassEntry(String className, long shallowBytes, double approxSharePercent) {

}
