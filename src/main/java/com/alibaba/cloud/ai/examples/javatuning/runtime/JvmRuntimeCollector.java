package com.alibaba.cloud.ai.examples.javatuning.runtime;

public interface JvmRuntimeCollector {

	JvmRuntimeSnapshot collect(long pid, RuntimeCollectionPolicy.CollectionRequest request);

	default MemoryGcEvidencePack collectMemoryGcEvidence(MemoryGcEvidenceRequest request) {
		throw new UnsupportedOperationException(
				"Upgraded memory/GC evidence collection is not supported by this collector implementation");
	}

	default RepeatedSamplingResult collectRepeated(RepeatedSamplingRequest request) {
		throw new UnsupportedOperationException("Repeated sampling is not supported by this collector implementation");
	}
}
