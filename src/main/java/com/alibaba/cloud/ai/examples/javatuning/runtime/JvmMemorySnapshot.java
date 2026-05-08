package com.alibaba.cloud.ai.examples.javatuning.runtime;

public record JvmMemorySnapshot(long heapUsedBytes, long heapCommittedBytes, long heapMaxBytes,
		Long oldGenUsedBytes, Long oldGenCommittedBytes, Long metaspaceUsedBytes, Long metaspaceCommittedBytes,
		Long metaspaceReservedBytes, Long xmsBytes, Long xmxBytes) {

	public JvmMemorySnapshot(long heapUsedBytes, long heapCommittedBytes, long heapMaxBytes, Long oldGenUsedBytes,
			Long oldGenCommittedBytes, Long metaspaceUsedBytes, Long xmsBytes, Long xmxBytes) {
		this(heapUsedBytes, heapCommittedBytes, heapMaxBytes, oldGenUsedBytes, oldGenCommittedBytes,
				metaspaceUsedBytes, null, null, xmsBytes, xmxBytes);
	}

}
