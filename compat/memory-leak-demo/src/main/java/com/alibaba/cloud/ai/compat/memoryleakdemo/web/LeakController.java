package com.alibaba.cloud.ai.compat.memoryleakdemo.web;

import java.util.Optional;

import com.alibaba.cloud.ai.compat.memoryleakdemo.churn.EphemeralChurnService;
import com.alibaba.cloud.ai.compat.memoryleakdemo.churn.EphemeralChurnService.ChurnResult;
import com.alibaba.cloud.ai.compat.memoryleakdemo.deadlock.DeadlockDemoTrigger;
import com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationSummary;
import com.alibaba.cloud.ai.compat.memoryleakdemo.leak.LeakStore;
import com.alibaba.cloud.ai.compat.memoryleakdemo.leak.RetainedByteArrayStore;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/leak")
public class LeakController {

	private static final String CHURN_HINT = "Agent young-GC churn rule needs very high YGC; repeat churn, use smaller heap, or run for a long soak.";

	private final LeakStore leakStore;

	private final RetainedByteArrayStore retainedByteArrayStore;

	private final EphemeralChurnService churnService;

	private final Optional<DeadlockDemoTrigger> deadlockDemoTrigger;

	public LeakController(LeakStore leakStore, RetainedByteArrayStore retainedByteArrayStore,
			EphemeralChurnService churnService,
			@Autowired(required = false) DeadlockDemoTrigger deadlockDemoTrigger) {
		this.leakStore = leakStore;
		this.retainedByteArrayStore = retainedByteArrayStore;
		this.churnService = churnService;
		this.deadlockDemoTrigger = Optional.ofNullable(deadlockDemoTrigger);
	}

	@PostMapping("/allocate")
	public LeakStatsResponse allocate(@Valid @RequestBody AllocateRequest request) {
		AllocationSummary summary = leakStore.allocate(request.entries(), request.payloadKb(), request.tag());
		return toStats(summary);
	}

	@GetMapping("/stats")
	public LeakStatsResponse stats() {
		return toStats(leakStore.currentSummary());
	}

	@PostMapping("/clear")
	public ClearResponse clear() {
		AllocationSummary summary = leakStore.clear();
		return toClear(summary);
	}

	@PostMapping("/raw/allocate")
	public LeakStatsResponse allocateRaw(@Valid @RequestBody AllocateRequest request) {
		retainedByteArrayStore.allocate(request.entries(), request.payloadKb(), request.tag());
		return toStats(retainedByteArrayStore.currentSummary());
	}

	@GetMapping("/raw/stats")
	public LeakStatsResponse rawStats() {
		return toStats(retainedByteArrayStore.currentSummary());
	}

	@PostMapping("/raw/clear")
	public ClearResponse clearRaw() {
		AllocationSummary summary = retainedByteArrayStore.clear();
		return toClear(summary);
	}

	@PostMapping("/churn")
	public ChurnResponse churn(@Valid @RequestBody ChurnRequest request) {
		ChurnResult result = churnService.run(request.iterations(), request.payloadBytes());
		return new ChurnResponse(result.iterations(), result.payloadBytes(), result.elapsedMs(), result.approxAllocatedMb(),
				CHURN_HINT);
	}

	@PostMapping("/deadlock/trigger")
	public ResponseEntity<DeadlockTriggerResponse> triggerDeadlock() {
		if (deadlockDemoTrigger.isEmpty()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new DeadlockTriggerResponse(false, false,
						"Deadlock demo disabled (set memory-leak-demo.features.deadlock-demo=true)"));
		}
		DeadlockDemoTrigger trigger = deadlockDemoTrigger.get();
		boolean first = trigger.triggerOnce();
		String message = first ? "Started two daemon threads in a lock cycle; capture Thread.print"
				: "Deadlock demo already ran in this JVM; restart the process to repeat";
		return ResponseEntity.ok(new DeadlockTriggerResponse(first, !first, message));
	}

	@GetMapping("/deadlock/status")
	public DeadlockStatusResponse deadlockStatus() {
		if (deadlockDemoTrigger.isEmpty()) {
			return new DeadlockStatusResponse(false, false);
		}
		DeadlockDemoTrigger trigger = deadlockDemoTrigger.get();
		return new DeadlockStatusResponse(true, trigger.alreadyTriggered());
	}

	@GetMapping("/validation-guide")
	public AgentValidationGuideResponse validationGuide() {
		return AgentValidationGuideResponse.buildDefault();
	}

	private static LeakStatsResponse toStats(AllocationSummary summary) {
		return new LeakStatsResponse(summary.retainedEntries(), summary.retainedBytesEstimate(),
				summary.allocationRequests(), summary.recentAllocations());
	}

	private static ClearResponse toClear(AllocationSummary summary) {
		return new ClearResponse(summary.clearedEntries(), summary.clearedBytesEstimate(), summary.retainedEntries());
	}
}
