package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.alibaba.cloud.ai.examples.javatuning.runtime.GcRootHint;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionConfidence;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RetainedTypeSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RetentionChainSegment;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RetentionChainSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.SuspectedHolderSummary;

import shark.CloseableHeapGraph;
import shark.GcRoot;
import shark.HeapField;
import shark.HeapObject;
import shark.HeapValue;
import shark.HprofHeapGraph;
import shark.HprofRecordTag;

public final class DominatorStyleHeapRetentionAnalyzer implements HeapRetentionAnalyzer {

	private static final String ENGINE = "dominator-style";

	private static final long PSEUDO_ROOT_OBJECT_ID = Long.MIN_VALUE;

	private static final int CANDIDATE_MULTIPLIER = 8;

	private static final int REVERSE_DEPTH_LIMIT = 24;

	private static final int FORWARD_DEPTH_LIMIT = 8;

	private static final int PATH_SEARCH_NODE_LIMIT = 1024;

	private static final String NOTES = "Retained-style bytes come from a bounded local dominator approximation, not MAT exact retained size.";

	private final int defaultTopObjects;

	private final int defaultMaxOutputChars;

	private final HeapRetentionMarkdownRenderer markdownRenderer;

	public DominatorStyleHeapRetentionAnalyzer(int defaultTopObjects, int defaultMaxOutputChars) {
		this(defaultTopObjects, defaultMaxOutputChars, new HeapRetentionMarkdownRenderer());
	}

	DominatorStyleHeapRetentionAnalyzer(int defaultTopObjects, int defaultMaxOutputChars,
			HeapRetentionMarkdownRenderer markdownRenderer) {
		this.defaultTopObjects = defaultTopObjects > 0 ? defaultTopObjects : 20;
		this.defaultMaxOutputChars = defaultMaxOutputChars > 0 ? defaultMaxOutputChars : 16_000;
		this.markdownRenderer = markdownRenderer;
	}

	@Override
	public HeapRetentionAnalysisResult analyze(Path heapDumpPath, Integer topObjectLimit, Integer maxOutputChars,
			String analysisDepth, List<String> focusTypes, List<String> focusPackages) {
		int topN = normalizePositive(topObjectLimit, defaultTopObjects);
		int maxChars = normalizePositive(maxOutputChars, defaultMaxOutputChars);
		if (heapDumpPath == null) {
			return failure("heapDumpPath is null");
		}

		Path normalized = heapDumpPath.toAbsolutePath().normalize();
		if (!Files.isRegularFile(normalized)) {
			return failure("Not a regular file: " + normalized);
		}

		Set<String> normalizedFocusTypes = normalizedFocusTypes(focusTypes);
		List<String> normalizedFocusPackages = normalizeFocusPackages(focusPackages);
		try (CloseableHeapGraph graph = openGraph(normalized)) {
			GraphIndex index = indexGraph(graph);
			List<Candidate> candidates = selectCandidates(index, normalizedFocusTypes, normalizedFocusPackages, topN);
			Slice slice = buildSlice(index, candidates, topN);
			DominatorIndex dominatorIndex = computeDominators(index, slice);
			AnalysisPass analysisPass = analyzeCandidates(index, slice, dominatorIndex, candidates,
					depthLimit(analysisDepth));
			HeapRetentionSummary summary = buildSummary(normalized, analysisPass, slice, topN, maxChars, focusTypes,
					focusPackages);
			return new HeapRetentionAnalysisResult(true, ENGINE, summary.warnings(), "", summary, summary.summaryMarkdown());
		}
		catch (IOException e) {
			return failure("Failed to read heap dump: " + e.getMessage());
		}
		catch (RuntimeException | OutOfMemoryError e) {
			String message = e instanceof OutOfMemoryError
					? "Out of memory while computing retained-style analysis (try a smaller dump or raise -Xmx)"
					: e.getMessage();
			return failure(message == null ? e.getClass().getSimpleName() : message);
		}
	}

	private HeapRetentionSummary buildSummary(Path heapDumpPath, AnalysisPass analysisPass, Slice slice, int topN,
			int maxChars, List<String> focusTypes, List<String> focusPackages) {
		List<CandidateAnalysis> analyses = analysisPass.analyses();
		List<String> warnings = new ArrayList<>();
		if (analyses.isEmpty()) {
			warnings.add("No candidate objects matched the requested focus types inside the bounded dominator slice.");
		}
		if (focusPackages != null && !focusPackages.isEmpty()) {
			warnings.add("focusPackages prioritizes candidate selection before bounded truncation; it is not a hard filter.");
		}
		if (slice.ancestorTruncated()) {
			warnings.add("Ancestor tracing hit the local node budget; retained-style bytes may be understated.");
		}
		if (slice.forwardTruncated()) {
			warnings.add("Forward graph expansion hit the local node budget; reachable subgraph bytes may be understated.");
		}
		if (analysisPass.pathSearchLimitHits() > 0) {
			warnings.add("Path search hit the local node budget for " + analysisPass.pathSearchLimitHits()
					+ " candidate(s); some holder or chain evidence was dropped.");
		}

		List<RetainedTypeSummary> dominantTypes = buildDominantTypes(analyses, topN);
		List<SuspectedHolderSummary> holders = buildHolders(analyses, topN);
		List<RetentionChainSummary> chains = buildChains(analyses, topN);
		List<GcRootHint> rootHints = buildRootHints(analyses, topN);

		HeapRetentionConfidence confidence = new HeapRetentionConfidence(analyses.isEmpty() ? "low" : "medium",
				List.of("Retained-style bytes are computed from a local graph approximation, not MAT exact retained size."),
				List.of("Engine=dominator-style", "Deep analysis requested explicitly.",
						"focusTypes=" + normalizedFocusTypes(focusTypes), "sliceNodes=" + slice.nodeIds().size()));

		HeapRetentionSummary withoutMarkdown = new HeapRetentionSummary(dominantTypes, holders, chains, rootHints,
				confidence, "", true, warnings, "");
		String markdown = rewriteMarkdown(markdownRenderer.render(heapDumpPath, withoutMarkdown, maxChars));
		return new HeapRetentionSummary(dominantTypes, holders, chains, rootHints, confidence, markdown, true, warnings,
				"");
	}

	private AnalysisPass analyzeCandidates(GraphIndex index, Slice slice, DominatorIndex dominatorIndex,
			List<Candidate> candidates, int depthLimit) {
		List<CandidateAnalysis> analyses = new ArrayList<>();
		int pathSearchLimitHits = 0;
		for (Candidate candidate : candidates) {
			if (!dominatorIndex.reachableNodeIds().contains(candidate.objectId())) {
				continue;
			}
			HolderSelection holder = selectHolder(index, dominatorIndex, candidate.objectId());
			PathSearchResult pathResult = pathFromHolderToCandidate(index, slice, dominatorIndex, holder.holderObjectId(),
					candidate.objectId());
			if (pathResult.truncated()) {
				pathSearchLimitHits++;
			}
			List<RefEdge> path = pathResult.path();
			if (path.isEmpty()) {
				continue;
			}
			long candidateRetainedBytes = dominatorIndex.retainedBytesByNodeId()
				.getOrDefault(candidate.objectId(), candidate.shallowBytes());
			long candidateReachableBytes = Math.max(candidateRetainedBytes,
					estimateReachableBytes(index, slice, candidate.objectId(), depthLimit));
			long holderRetainedBytes = dominatorIndex.retainedBytesByNodeId()
				.getOrDefault(holder.holderObjectId(), candidateRetainedBytes);
			long holderReachableBytes = Math.max(holderRetainedBytes,
					estimateReachableBytes(index, slice, holder.holderObjectId(), depthLimit));
			analyses.add(new CandidateAnalysis(candidate, holder.holderObjectId(), holder.holderType(), holder.holderRole(),
					holder.rootKind(), candidateRetainedBytes, candidateReachableBytes, holderRetainedBytes,
					holderReachableBytes, path, buildFieldPath(path), NOTES));
		}

		analyses.sort(Comparator.comparingLong(CandidateAnalysis::holderRetainedBytesApprox)
			.reversed()
			.thenComparing(CandidateAnalysis::holderType)
			.thenComparing(analysis -> analysis.candidate().typeName()));
		return new AnalysisPass(List.copyOf(analyses), pathSearchLimitHits);
	}

	private List<RetainedTypeSummary> buildDominantTypes(List<CandidateAnalysis> analyses, int topN) {
		Map<String, TypeAccumulator> types = new LinkedHashMap<>();
		long totalRetainedBytes = 0L;
		for (CandidateAnalysis analysis : analyses) {
			totalRetainedBytes = saturatingAdd(totalRetainedBytes, analysis.candidateRetainedBytesApprox());
			TypeAccumulator accumulator = types.computeIfAbsent(analysis.candidate().typeName(),
					ignored -> new TypeAccumulator());
			accumulator.objectCount++;
			accumulator.retainedBytes = saturatingAdd(accumulator.retainedBytes, analysis.candidateRetainedBytesApprox());
			accumulator.terminalShallowBytes = saturatingAdd(accumulator.terminalShallowBytes,
					analysis.candidate().shallowBytes());
		}
		final long trackedRetainedBytes = totalRetainedBytes;

		return types.entrySet().stream()
			.map(entry -> {
				TypeAccumulator accumulator = entry.getValue();
				double share = trackedRetainedBytes <= 0L ? 0.0d
						: 100.0d * accumulator.retainedBytes / (double) trackedRetainedBytes;
				return new RetainedTypeSummary(entry.getKey(), accumulator.retainedBytes, accumulator.objectCount, share,
						accumulator.terminalShallowBytes);
			})
			.sorted(Comparator.comparingDouble(RetainedTypeSummary::shareOfTrackedRetainedApprox).reversed()
				.thenComparing(Comparator.comparingLong((RetainedTypeSummary summary) -> summary.retainedBytesApprox() == null
						? 0L : summary.retainedBytesApprox()).reversed())
				.thenComparing(RetainedTypeSummary::typeName))
			.limit(topN)
			.toList();
	}

	private List<SuspectedHolderSummary> buildHolders(List<CandidateAnalysis> analyses, int topN) {
		Map<String, HolderAccumulator> holders = new LinkedHashMap<>();
		for (CandidateAnalysis analysis : analyses) {
			String normalizedFieldPath = normalizedFieldPath(analysis.exampleFieldPath());
			String signature = analysis.holderType() + "|" + analysis.holderRole() + "|" + normalizedFieldPath;
			HolderAccumulator accumulator = holders.computeIfAbsent(signature,
					ignored -> new HolderAccumulator(analysis.holderType(), analysis.holderRole(),
							normalizedFieldPath, analysis.candidate().typeName(), analysis.notes()));
			accumulator.retainedBytesByHolderObjectId.merge(analysis.holderObjectId(), analysis.holderRetainedBytesApprox(),
					Math::max);
			accumulator.reachableBytesByHolderObjectId.merge(analysis.holderObjectId(), analysis.holderReachableBytesApprox(),
					Math::max);
			accumulator.objectCount++;
		}

		return holders.values().stream()
			.map(accumulator -> {
				long retainedBytes = accumulator.totalRetainedBytes();
				long reachableBytes = Math.max(accumulator.totalReachableBytes(), retainedBytes);
				return new SuspectedHolderSummary(accumulator.holderType, accumulator.holderRole,
					retainedBytes, reachableBytes,
					accumulator.objectCount, accumulator.exampleFieldPath, accumulator.exampleTargetType, accumulator.notes);
			})
			.sorted(Comparator.comparingLong((SuspectedHolderSummary holder) -> holder.retainedBytesApprox() == null ? 0L
					: holder.retainedBytesApprox()).reversed()
				.thenComparing(Comparator.comparingLong(SuspectedHolderSummary::reachableSubgraphBytesApprox).reversed()))
			.limit(topN)
			.toList();
	}

	private List<RetentionChainSummary> buildChains(List<CandidateAnalysis> analyses, int topN) {
		Map<String, ChainAccumulator> chains = new LinkedHashMap<>();
		for (CandidateAnalysis analysis : analyses) {
			List<RetentionChainSegment> normalizedSegments = normalizedChainSegments(analysis.path());
			String signature = analysis.rootKind() + "|" + normalizedSegments + "|" + analysis.candidate().typeName();
			ChainAccumulator accumulator = chains.computeIfAbsent(signature,
					ignored -> new ChainAccumulator(analysis.rootKind(), normalizedSegments,
							analysis.candidate().typeName(), analysis.candidate().shallowBytes()));
			accumulator.chainCount++;
			accumulator.retainedBytes = saturatingAdd(accumulator.retainedBytes, analysis.candidateRetainedBytesApprox());
			accumulator.reachableBytes = saturatingAdd(accumulator.reachableBytes, analysis.candidateReachableBytesApprox());
		}

		return chains.values().stream()
			.map(accumulator -> new RetentionChainSummary(accumulator.rootKind, accumulator.segments,
					accumulator.terminalType, accumulator.terminalShallowBytes, accumulator.chainCount,
					accumulator.retainedBytes, Math.max(accumulator.reachableBytes, accumulator.retainedBytes)))
			.sorted(Comparator.comparingLong((RetentionChainSummary chain) -> chain.retainedBytesApprox() == null ? 0L
					: chain.retainedBytesApprox()).reversed()
				.thenComparing(Comparator.comparingLong(RetentionChainSummary::reachableSubgraphBytesApprox).reversed()))
			.limit(topN)
			.toList();
	}

	private List<GcRootHint> buildRootHints(List<CandidateAnalysis> analyses, int topN) {
		Map<String, RootAccumulator> roots = new LinkedHashMap<>();
		for (CandidateAnalysis analysis : analyses) {
			RootAccumulator accumulator = roots.computeIfAbsent(analysis.rootKind(),
					ignored -> new RootAccumulator(analysis.rootKind(), analysis.holderType()));
			accumulator.count++;
		}

		return roots.values().stream()
			.map(accumulator -> new GcRootHint(accumulator.rootKind, accumulator.exampleOwnerType, accumulator.count,
					"Dominated candidate paths were traced back to this local root hint."))
			.sorted(Comparator.comparingLong(GcRootHint::occurrenceCountApprox).reversed())
			.limit(topN)
			.toList();
	}

	private Slice buildSlice(GraphIndex index, List<Candidate> candidates, int topN) {
		int reverseNodeLimit = Math.max(4_000, topN * 512);
		int forwardNodeLimit = Math.max(8_000, topN * 1_024);
		Set<Long> nodeIds = new LinkedHashSet<>();
		record QueueNode(long objectId, int depth) {
		}

		ArrayDeque<QueueNode> reverseQueue = new ArrayDeque<>();
		for (Candidate candidate : candidates) {
			reverseQueue.addLast(new QueueNode(candidate.objectId(), 0));
		}

		boolean ancestorTruncated = false;
		while (!reverseQueue.isEmpty()) {
			QueueNode node = reverseQueue.removeFirst();
			if (nodeIds.size() >= reverseNodeLimit) {
				ancestorTruncated = true;
				break;
			}
			if (!nodeIds.add(node.objectId())) {
				continue;
			}
			if (node.depth() >= REVERSE_DEPTH_LIMIT) {
				continue;
			}
			for (RefEdge edge : index.inboundEdges().getOrDefault(node.objectId(), List.of())) {
				reverseQueue.addLast(new QueueNode(edge.ownerId(), node.depth() + 1));
			}
		}

		ArrayDeque<QueueNode> forwardQueue = new ArrayDeque<>();
		for (Long nodeId : List.copyOf(nodeIds)) {
			forwardQueue.addLast(new QueueNode(nodeId, 0));
		}

		boolean forwardTruncated = false;
		Set<Long> expandedForward = new HashSet<>();
		while (!forwardQueue.isEmpty()) {
			QueueNode node = forwardQueue.removeFirst();
			if (!expandedForward.add(node.objectId())) {
				continue;
			}
			if (node.depth() >= FORWARD_DEPTH_LIMIT) {
				continue;
			}
			for (RefEdge edge : index.outboundEdges().getOrDefault(node.objectId(), List.of())) {
				if (nodeIds.size() >= forwardNodeLimit && !nodeIds.contains(edge.targetId())) {
					forwardTruncated = true;
					continue;
				}
				if (nodeIds.add(edge.targetId())) {
					forwardQueue.addLast(new QueueNode(edge.targetId(), node.depth() + 1));
				}
			}
		}

		Set<Long> rootIds = new LinkedHashSet<>();
		for (Long nodeId : nodeIds) {
			if (index.rootLikeObjectIds().contains(nodeId) || index.gcRootKindsByObjectId().containsKey(nodeId)) {
				rootIds.add(nodeId);
				continue;
			}
			List<RefEdge> inbound = index.inboundEdges().get(nodeId);
			if (inbound == null || inbound.isEmpty()) {
				rootIds.add(nodeId);
				continue;
			}
			for (RefEdge edge : inbound) {
				if (!nodeIds.contains(edge.ownerId())) {
					rootIds.add(nodeId);
					break;
				}
			}
		}

		if (rootIds.isEmpty()) {
			for (Candidate candidate : candidates) {
				rootIds.add(candidate.objectId());
			}
		}

		return new Slice(Set.copyOf(nodeIds), Set.copyOf(rootIds), ancestorTruncated, forwardTruncated);
	}

	private DominatorIndex computeDominators(GraphIndex index, Slice slice) {
		Map<Long, List<Long>> successors = new HashMap<>();
		for (Long nodeId : slice.nodeIds()) {
			List<Long> outbound = new ArrayList<>();
			for (RefEdge edge : index.outboundEdges().getOrDefault(nodeId, List.of())) {
				if (slice.nodeIds().contains(edge.targetId())) {
					outbound.add(edge.targetId());
				}
			}
			successors.put(nodeId, List.copyOf(outbound));
		}

		List<Long> reversePostOrder = reversePostOrder(successors, slice.rootIds());
		if (reversePostOrder.isEmpty()) {
			return DominatorIndex.empty();
		}

		Map<Long, Integer> orderByNodeId = new HashMap<>();
		for (int indexPosition = 0; indexPosition < reversePostOrder.size(); indexPosition++) {
			orderByNodeId.put(reversePostOrder.get(indexPosition), indexPosition);
		}

		Map<Long, Long> immediateDominatorByNodeId = new HashMap<>();
		immediateDominatorByNodeId.put(PSEUDO_ROOT_OBJECT_ID, PSEUDO_ROOT_OBJECT_ID);
		boolean changed = true;
		while (changed) {
			changed = false;
			for (int indexPosition = 1; indexPosition < reversePostOrder.size(); indexPosition++) {
				long nodeId = reversePostOrder.get(indexPosition);
				Long newImmediateDominator = null;
				for (Long predecessorId : predecessors(nodeId, slice, index, orderByNodeId.keySet())) {
					if (immediateDominatorByNodeId.containsKey(predecessorId)) {
						newImmediateDominator = predecessorId;
						break;
					}
				}
				if (newImmediateDominator == null) {
					continue;
				}
				for (Long predecessorId : predecessors(nodeId, slice, index, orderByNodeId.keySet())) {
					if (predecessorId == newImmediateDominator || !immediateDominatorByNodeId.containsKey(predecessorId)) {
						continue;
					}
					newImmediateDominator = intersect(predecessorId, newImmediateDominator, immediateDominatorByNodeId,
							orderByNodeId);
				}
				if (!Objects.equals(immediateDominatorByNodeId.get(nodeId), newImmediateDominator)) {
					immediateDominatorByNodeId.put(nodeId, newImmediateDominator);
					changed = true;
				}
			}
		}

		Map<Long, List<Long>> dominatedChildrenByNodeId = new HashMap<>();
		for (Long nodeId : reversePostOrder) {
			if (nodeId == PSEUDO_ROOT_OBJECT_ID) {
				continue;
			}
			Long immediateDominator = immediateDominatorByNodeId.get(nodeId);
			if (immediateDominator == null) {
				continue;
			}
			dominatedChildrenByNodeId.computeIfAbsent(immediateDominator, ignored -> new ArrayList<>()).add(nodeId);
		}
		dominatedChildrenByNodeId.replaceAll((ignored, children) -> List.copyOf(children));

		Map<Long, Integer> entryByNodeId = new HashMap<>();
		Map<Long, Integer> exitByNodeId = new HashMap<>();
		record DomFrame(long objectId, boolean exiting) {
		}
		int sequence = 0;
		ArrayDeque<DomFrame> stack = new ArrayDeque<>();
		stack.push(new DomFrame(PSEUDO_ROOT_OBJECT_ID, false));
		while (!stack.isEmpty()) {
			DomFrame frame = stack.pop();
			if (frame.exiting()) {
				exitByNodeId.put(frame.objectId(), sequence++);
				continue;
			}
			entryByNodeId.put(frame.objectId(), sequence++);
			stack.push(new DomFrame(frame.objectId(), true));
			List<Long> children = dominatedChildrenByNodeId.getOrDefault(frame.objectId(), List.of());
			for (int indexPosition = children.size() - 1; indexPosition >= 0; indexPosition--) {
				stack.push(new DomFrame(children.get(indexPosition), false));
			}
		}

		Map<Long, Long> retainedBytesByNodeId = new HashMap<>();
		for (int indexPosition = reversePostOrder.size() - 1; indexPosition >= 1; indexPosition--) {
			long nodeId = reversePostOrder.get(indexPosition);
			long retainedBytes = Math.max(0L, index.objectsById().getOrDefault(nodeId, ObjectInfo.EMPTY).shallowBytes());
			for (Long childId : dominatedChildrenByNodeId.getOrDefault(nodeId, List.of())) {
				retainedBytes = saturatingAdd(retainedBytes, retainedBytesByNodeId.getOrDefault(childId, 0L));
			}
			retainedBytesByNodeId.put(nodeId, retainedBytes);
		}

		return new DominatorIndex(Set.copyOf(reversePostOrder.subList(1, reversePostOrder.size())),
				Map.copyOf(immediateDominatorByNodeId), Map.copyOf(dominatedChildrenByNodeId), Map.copyOf(retainedBytesByNodeId),
				Map.copyOf(entryByNodeId), Map.copyOf(exitByNodeId));
	}

	private static List<Long> reversePostOrder(Map<Long, List<Long>> successors, Set<Long> rootIds) {
		record Frame(long objectId, boolean exiting) {
		}

		List<Long> postOrder = new ArrayList<>();
		Set<Long> visited = new HashSet<>();
		ArrayDeque<Frame> stack = new ArrayDeque<>();
		stack.push(new Frame(PSEUDO_ROOT_OBJECT_ID, false));
		while (!stack.isEmpty()) {
			Frame frame = stack.pop();
			if (frame.exiting()) {
				postOrder.add(frame.objectId());
				continue;
			}
			if (!visited.add(frame.objectId())) {
				continue;
			}
			stack.push(new Frame(frame.objectId(), true));
			List<Long> children = frame.objectId() == PSEUDO_ROOT_OBJECT_ID ? List.copyOf(rootIds)
					: successors.getOrDefault(frame.objectId(), List.of());
			for (int indexPosition = children.size() - 1; indexPosition >= 0; indexPosition--) {
				stack.push(new Frame(children.get(indexPosition), false));
			}
		}

		List<Long> reversePostOrder = new ArrayList<>(postOrder.size());
		for (int indexPosition = postOrder.size() - 1; indexPosition >= 0; indexPosition--) {
			reversePostOrder.add(postOrder.get(indexPosition));
		}
		return reversePostOrder;
	}

	private static List<Long> predecessors(long nodeId, Slice slice, GraphIndex index, Set<Long> reachableNodeIds) {
		List<Long> predecessors = new ArrayList<>();
		if (slice.rootIds().contains(nodeId)) {
			predecessors.add(PSEUDO_ROOT_OBJECT_ID);
		}
		for (RefEdge edge : index.inboundEdges().getOrDefault(nodeId, List.of())) {
			if (slice.nodeIds().contains(edge.ownerId()) && reachableNodeIds.contains(edge.ownerId())) {
				predecessors.add(edge.ownerId());
			}
		}
		return predecessors;
	}

	private static long intersect(long fingerOne, long fingerTwo, Map<Long, Long> immediateDominatorByNodeId,
			Map<Long, Integer> orderByNodeId) {
		long left = fingerOne;
		long right = fingerTwo;
		while (left != right) {
			while (orderByNodeId.getOrDefault(left, -1) > orderByNodeId.getOrDefault(right, -1)) {
				left = immediateDominatorByNodeId.get(left);
			}
			while (orderByNodeId.getOrDefault(right, -1) > orderByNodeId.getOrDefault(left, -1)) {
				right = immediateDominatorByNodeId.get(right);
			}
		}
		return left;
	}

	private HolderSelection selectHolder(GraphIndex index, DominatorIndex dominatorIndex, long candidateObjectId) {
		Long fallbackHolderObjectId = null;
		long currentObjectId = candidateObjectId;
		while (true) {
			Long parentObjectId = dominatorIndex.immediateDominatorByNodeId().get(currentObjectId);
			if (parentObjectId == null || parentObjectId == PSEUDO_ROOT_OBJECT_ID) {
				break;
			}
			if (fallbackHolderObjectId == null) {
				fallbackHolderObjectId = parentObjectId;
			}
			HolderDescriptor descriptor = describeHolder(index, parentObjectId);
			if (descriptor.preferred()) {
				return new HolderSelection(parentObjectId, descriptor.holderType(), descriptor.holderRole(),
						rootKindFor(index, dominatorIndex, parentObjectId));
			}
			currentObjectId = parentObjectId;
		}

		long holderObjectId = fallbackHolderObjectId != null ? fallbackHolderObjectId : candidateObjectId;
		HolderDescriptor descriptor = describeHolder(index, holderObjectId);
		return new HolderSelection(holderObjectId, descriptor.holderType(), descriptor.holderRole(),
				rootKindFor(index, dominatorIndex, holderObjectId));
	}

	private static HolderDescriptor describeHolder(GraphIndex index, long objectId) {
		ObjectInfo info = index.objectsById().get(objectId);
		String typeName = info == null ? "(unknown)" : info.typeName();
		if (info != null && info.heapClass()) {
			return new HolderDescriptor(typeName, "static-field-owner", true);
		}
		String role = classifyHolderRole(typeName, index.outboundEdges().getOrDefault(objectId, List.of()));
		boolean preferred = !"unknown".equals(role) || index.gcRootKindsByObjectId().containsKey(objectId);
		return new HolderDescriptor(typeName, role, preferred);
	}

	private static String rootKindFor(GraphIndex index, DominatorIndex dominatorIndex, long holderObjectId) {
		long currentObjectId = holderObjectId;
		while (currentObjectId != PSEUDO_ROOT_OBJECT_ID) {
			ObjectInfo info = index.objectsById().get(currentObjectId);
			if (info != null && info.heapClass()) {
				return "system-class";
			}
			List<String> gcRootKinds = index.gcRootKindsByObjectId().get(currentObjectId);
			if (gcRootKinds != null && !gcRootKinds.isEmpty()) {
				return gcRootKinds.get(0);
			}
			Long next = dominatorIndex.immediateDominatorByNodeId().get(currentObjectId);
			if (next == null) {
				break;
			}
			currentObjectId = next;
		}
		return "unknown";
	}

	private PathSearchResult pathFromHolderToCandidate(GraphIndex index, Slice slice, DominatorIndex dominatorIndex,
			long holderObjectId, long candidateObjectId) {
		if (holderObjectId == candidateObjectId) {
			return new PathSearchResult(List.of(), false);
		}
		Map<Long, RefEdge> pathEdgeByTargetId = new HashMap<>();
		Set<Long> visited = new HashSet<>();
		ArrayDeque<Long> queue = new ArrayDeque<>();
		queue.add(holderObjectId);
		visited.add(holderObjectId);

		while (!queue.isEmpty()) {
			long objectId = queue.removeFirst();
			for (RefEdge edge : index.outboundEdges().getOrDefault(objectId, List.of())) {
				if (!slice.nodeIds().contains(edge.targetId())) {
					continue;
				}
				if (!dominatorIndex.dominates(holderObjectId, edge.targetId()) && edge.targetId() != candidateObjectId) {
					continue;
				}
				if (!visited.contains(edge.targetId()) && visited.size() >= PATH_SEARCH_NODE_LIMIT) {
					return new PathSearchResult(List.of(), true);
				}
				if (!visited.add(edge.targetId())) {
					continue;
				}
				pathEdgeByTargetId.put(edge.targetId(), edge);
				if (edge.targetId() == candidateObjectId) {
					return new PathSearchResult(rebuildPath(pathEdgeByTargetId, holderObjectId, candidateObjectId), false);
				}
				queue.addLast(edge.targetId());
			}
		}

		return new PathSearchResult(List.of(), false);
	}

	private static List<RefEdge> rebuildPath(Map<Long, RefEdge> pathEdgeByTargetId, long holderObjectId,
			long candidateObjectId) {
		List<RefEdge> path = new ArrayList<>();
		long currentObjectId = candidateObjectId;
		while (currentObjectId != holderObjectId) {
			RefEdge edge = pathEdgeByTargetId.get(currentObjectId);
			if (edge == null) {
				return List.of();
			}
			path.add(edge);
			currentObjectId = edge.ownerId();
		}
		List<RefEdge> ordered = new ArrayList<>(path.size());
		for (int indexPosition = path.size() - 1; indexPosition >= 0; indexPosition--) {
			ordered.add(path.get(indexPosition));
		}
		return List.copyOf(ordered);
	}

	private static List<RetentionChainSegment> chainSegments(List<RefEdge> path) {
		List<RetentionChainSegment> segments = new ArrayList<>(path.size());
		for (RefEdge edge : path) {
			segments.add(new RetentionChainSegment(edge.ownerType(), edge.referenceKind(), edge.referenceName(),
					edge.targetType()));
		}
		return List.copyOf(segments);
	}

	private static List<RetentionChainSegment> normalizedChainSegments(List<RefEdge> path) {
		List<RetentionChainSegment> segments = new ArrayList<>(path.size());
		for (RefEdge edge : path) {
			segments.add(new RetentionChainSegment(edge.ownerType(), edge.referenceKind(),
					normalizedReferenceName(edge.referenceKind(), edge.referenceName()), edge.targetType()));
		}
		return List.copyOf(segments);
	}

	private static String buildFieldPath(List<RefEdge> path) {
		if (path.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder(path.get(0).ownerType());
		for (RefEdge edge : path) {
			if ("array-element".equals(edge.referenceKind())) {
				sb.append("[").append(edge.referenceName()).append("]");
			}
			else {
				sb.append(".").append(edge.referenceName());
			}
		}
		return sb.toString();
	}

	private static String normalizedFieldPath(String fieldPath) {
		if (fieldPath == null || fieldPath.isBlank()) {
			return "";
		}
		return fieldPath.replaceAll("\\[\\d+\\]", "[*]");
	}

	private static String normalizedReferenceName(String referenceKind, String referenceName) {
		if ("array-element".equals(referenceKind)) {
			return "*";
		}
		return referenceName;
	}

	private static long estimateReachableBytes(GraphIndex index, Slice slice, long startObjectId, int depthLimit) {
		record QueueNode(long objectId, int depth) {
		}

		long totalBytes = 0L;
		Set<Long> visited = new HashSet<>();
		ArrayDeque<QueueNode> queue = new ArrayDeque<>();
		queue.addLast(new QueueNode(startObjectId, 0));
		while (!queue.isEmpty() && visited.size() < slice.nodeIds().size()) {
			QueueNode node = queue.removeFirst();
			if (!visited.add(node.objectId())) {
				continue;
			}
			ObjectInfo info = index.objectsById().get(node.objectId());
			if (info != null) {
				totalBytes = saturatingAdd(totalBytes, Math.max(info.shallowBytes(), 0L));
			}
			if (node.depth() >= depthLimit) {
				continue;
			}
			for (RefEdge edge : index.outboundEdges().getOrDefault(node.objectId(), List.of())) {
				if (slice.nodeIds().contains(edge.targetId()) && !visited.contains(edge.targetId())) {
					queue.addLast(new QueueNode(edge.targetId(), node.depth() + 1));
				}
			}
		}
		return totalBytes;
	}

	private List<Candidate> selectCandidates(GraphIndex index, Set<String> focusTypes, List<String> focusPackages, int topN) {
		return index.objectsById().entrySet().stream()
			.filter(entry -> !entry.getValue().heapClass())
			.map(entry -> new Candidate(entry.getKey(), entry.getValue().typeName(), entry.getValue().shallowBytes()))
			.filter(candidate -> isCandidateType(candidate.typeName(), focusTypes))
			.sorted(candidateComparator(focusPackages))
			.limit(Math.max(topN * CANDIDATE_MULTIPLIER, topN))
			.toList();
	}

	private GraphIndex indexGraph(CloseableHeapGraph graph) {
		Map<Long, ObjectInfo> objectsById = new HashMap<>();
		Map<Long, List<RefEdge>> inboundEdges = new HashMap<>();
		Map<Long, List<RefEdge>> outboundEdges = new HashMap<>();
		Map<Long, List<String>> gcRootKindsByObjectId = new HashMap<>();
		Set<Long> rootLikeObjectIds = new LinkedHashSet<>();

		for (GcRoot gcRoot : graph.getGcRoots()) {
			gcRootKindsByObjectId.computeIfAbsent(gcRoot.getId(), ignored -> new ArrayList<>()).add(normalizeGcRootKind(gcRoot));
		}

		Iterator<HeapObject> objects = graph.getObjects().iterator();
		while (objects.hasNext()) {
			HeapObject object = objects.next();
			HeapObject.HeapClass heapClass = object.getAsClass();
			if (heapClass != null) {
				objectsById.put(heapClass.getObjectId(), new ObjectInfo(heapClass.getName(), 0L, true));
				Iterator<HeapField> staticFields = heapClass.readStaticFields().iterator();
				boolean addedStaticReference = false;
				while (staticFields.hasNext()) {
					HeapField field = staticFields.next();
					if (addReference(objectsById, inboundEdges, outboundEdges, heapClass.getObjectId(), heapClass.getName(),
							"static-field", field.getName(), field.getValue(), true)) {
						addedStaticReference = true;
					}
				}
				if (addedStaticReference) {
					rootLikeObjectIds.add(heapClass.getObjectId());
				}
				continue;
			}

			HeapObject.HeapInstance instance = object.getAsInstance();
			if (instance != null) {
				objectsById.put(instance.getObjectId(),
						new ObjectInfo(instance.getInstanceClassName(), instance.getByteSize(), false));
				Iterator<HeapField> fields = instance.readFields().iterator();
				while (fields.hasNext()) {
					HeapField field = fields.next();
					addReference(objectsById, inboundEdges, outboundEdges, instance.getObjectId(),
							instance.getInstanceClassName(), "field", field.getName(), field.getValue(), false);
				}
				continue;
			}

			HeapObject.HeapObjectArray objectArray = object.getAsObjectArray();
			if (objectArray != null) {
				objectsById.put(objectArray.getObjectId(),
						new ObjectInfo(objectArray.getArrayClassName(), objectArray.getByteSize(), false));
				Iterator<HeapValue> elements = objectArray.readElements().iterator();
				int indexPosition = 0;
				while (elements.hasNext()) {
					addReference(objectsById, inboundEdges, outboundEdges, objectArray.getObjectId(),
							objectArray.getArrayClassName(), "array-element", Integer.toString(indexPosition),
							elements.next(), false);
					indexPosition++;
				}
				continue;
			}

			HeapObject.HeapPrimitiveArray primitiveArray = object.getAsPrimitiveArray();
			if (primitiveArray != null) {
				objectsById.put(primitiveArray.getObjectId(),
						new ObjectInfo(primitiveArray.getArrayClassName(), primitiveArray.getByteSize(), false));
			}
		}

		enrichEdgeTargetTypes(inboundEdges, objectsById);
		enrichEdgeTargetTypes(outboundEdges, objectsById);
		inboundEdges.replaceAll((ignored, edges) -> List.copyOf(edges));
		outboundEdges.replaceAll((ignored, edges) -> List.copyOf(edges));
		gcRootKindsByObjectId.replaceAll((ignored, kinds) -> List.copyOf(kinds));
		return new GraphIndex(Map.copyOf(objectsById), Map.copyOf(inboundEdges), Map.copyOf(outboundEdges),
				Map.copyOf(gcRootKindsByObjectId), Set.copyOf(rootLikeObjectIds));
	}

	private static boolean addReference(Map<Long, ObjectInfo> objectsById, Map<Long, List<RefEdge>> inboundEdges,
			Map<Long, List<RefEdge>> outboundEdges, long ownerId, String ownerType, String referenceKind,
			String referenceName, HeapValue value, boolean staticField) {
		if (value == null || !value.isNonNullReference()) {
			return false;
		}
		Long targetId = value.getAsObjectId();
		if (targetId == null) {
			return false;
		}
		String targetType = objectsById.getOrDefault(targetId, ObjectInfo.EMPTY).typeName();
		RefEdge edge = new RefEdge(ownerId, ownerType, referenceKind, referenceName, targetId, staticField, targetType);
		inboundEdges.computeIfAbsent(targetId, ignored -> new ArrayList<>()).add(edge);
		outboundEdges.computeIfAbsent(ownerId, ignored -> new ArrayList<>()).add(edge);
		return true;
	}

	private static void enrichEdgeTargetTypes(Map<Long, List<RefEdge>> edgesByNodeId, Map<Long, ObjectInfo> objectsById) {
		edgesByNodeId.replaceAll((ignored, edges) -> edges.stream()
			.map(edge -> edge.targetType().isBlank()
					? new RefEdge(edge.ownerId(), edge.ownerType(), edge.referenceKind(), edge.referenceName(),
							edge.targetId(), edge.staticField(),
							objectsById.getOrDefault(edge.targetId(), ObjectInfo.EMPTY).typeName())
					: edge)
			.toList());
	}

	private static Comparator<Candidate> candidateComparator(List<String> focusPackages) {
		Comparator<Candidate> bySizeThenName = Comparator.comparingLong(Candidate::shallowBytes)
			.reversed()
			.thenComparing(Candidate::typeName);
		if (focusPackages == null || focusPackages.isEmpty()) {
			return bySizeThenName;
		}
		return Comparator.comparingInt((Candidate candidate) -> packagePreference(candidate.typeName(), focusPackages))
			.reversed()
			.thenComparing(bySizeThenName);
	}

	private static boolean isCandidateType(String typeName, Set<String> focusTypes) {
		String normalized = normalizeTypeName(typeName);
		if (!focusTypes.isEmpty()) {
			return focusTypes.contains(normalized);
		}
		return normalized.endsWith("[]");
	}

	private static int packagePreference(String typeName, List<String> focusPackages) {
		for (int index = 0; index < focusPackages.size(); index++) {
			String prefix = focusPackages.get(index);
			if (typeName != null && prefix != null && !prefix.isBlank() && typeName.startsWith(prefix)) {
				return focusPackages.size() - index;
			}
		}
		return 0;
	}

	private static String classifyHolderRole(String typeName, List<RefEdge> outboundEdges) {
		String normalizedType = typeName == null ? "" : typeName.toLowerCase(Locale.ROOT);
		if (normalizedType.contains("threadlocal")) {
			return "thread-local";
		}
		if (normalizedType.contains("thread")) {
			return "thread-owner";
		}
		if (normalizedType.contains("map")) {
			return "map";
		}
		if (normalizedType.contains("list") || normalizedType.contains("set") || normalizedType.contains("queue")
				|| normalizedType.contains("collection")) {
			return "collection";
		}
		if (normalizedType.contains("owner") || normalizedType.contains("holder") || normalizedType.contains("bucket")) {
			return "owner";
		}
		if (normalizedType.endsWith("[]")) {
			return "array-owner";
		}
		long outboundReferenceCount = outboundEdges == null ? 0L : outboundEdges.size();
		if (outboundReferenceCount > 3L) {
			return "container";
		}
		return "unknown";
	}

	private static String normalizeGcRootKind(GcRoot root) {
		String simpleName = root.getClass().getSimpleName();
		StringBuilder normalized = new StringBuilder(simpleName.length() + 4);
		for (int index = 0; index < simpleName.length(); index++) {
			char ch = simpleName.charAt(index);
			if (Character.isUpperCase(ch) && index > 0) {
				normalized.append('-');
			}
			normalized.append(Character.toLowerCase(ch));
		}
		return normalized.toString();
	}

	private static Set<String> normalizedFocusTypes(List<String> focusTypes) {
		if (focusTypes == null || focusTypes.isEmpty()) {
			return Set.of();
		}
		Set<String> normalized = new LinkedHashSet<>();
		for (String focusType : focusTypes) {
			String value = normalizeTypeName(focusType);
			if (!value.isBlank()) {
				normalized.add(value);
			}
		}
		return Set.copyOf(normalized);
	}

	private static List<String> normalizeFocusPackages(List<String> focusPackages) {
		if (focusPackages == null || focusPackages.isEmpty()) {
			return List.of();
		}
		List<String> normalized = new ArrayList<>();
		for (String focusPackage : focusPackages) {
			if (focusPackage != null && !focusPackage.isBlank()) {
				normalized.add(focusPackage.trim());
			}
		}
		return List.copyOf(normalized);
	}

	private static String normalizeTypeName(String typeName) {
		return typeName == null ? "" : typeName.trim();
	}

	private static int depthLimit(String analysisDepth) {
		if (analysisDepth == null) {
			return 12;
		}
		return switch (analysisDepth.trim().toLowerCase(Locale.ROOT)) {
			case "fast" -> 6;
			case "deep" -> 16;
			default -> 12;
		};
	}

	private static int normalizePositive(Integer value, int defaultValue) {
		return value != null && value > 0 ? value : defaultValue;
	}

	private static long saturatingAdd(long left, long right) {
		long sum = left + right;
		if (((left ^ sum) & (right ^ sum)) < 0) {
			return Long.MAX_VALUE;
		}
		return sum;
	}

	private static CloseableHeapGraph openGraph(Path heapDumpPath) throws IOException {
		return HprofHeapGraph.Companion.openHeapGraph(heapDumpPath.toFile(), null, EnumSet.allOf(HprofRecordTag.class));
	}

	private String rewriteMarkdown(String markdown) {
		return markdown.replace("### Heap retention analysis (local, holder-oriented)",
				"### Heap retention analysis (local, dominator-style approximation)")
			.replace(
					"Shark retention hint output. `reachable subgraph` is a bounded graph-based approximation, and this is not full dominator retained-size analysis.",
					"Dominator-style retained output. `retained bytes` come from a bounded local dominator approximation, and `reachable subgraph` remains an outbound graph estimate rather than MAT-exact retained size.");
	}

	private static HeapRetentionAnalysisResult failure(String errorMessage) {
		HeapRetentionSummary summary = new HeapRetentionSummary(List.of(), List.of(), List.of(), List.of(),
				new HeapRetentionConfidence("low", List.of("Retention analysis did not complete."),
						List.of("Engine=dominator-style")),
				"", false, List.of(), errorMessage == null ? "" : errorMessage);
		return new HeapRetentionAnalysisResult(false, ENGINE, List.of(), errorMessage, summary, "");
	}

	private record GraphIndex(Map<Long, ObjectInfo> objectsById, Map<Long, List<RefEdge>> inboundEdges,
			Map<Long, List<RefEdge>> outboundEdges, Map<Long, List<String>> gcRootKindsByObjectId,
			Set<Long> rootLikeObjectIds) {
	}

	private record Slice(Set<Long> nodeIds, Set<Long> rootIds, boolean ancestorTruncated, boolean forwardTruncated) {
	}

	private record DominatorIndex(Set<Long> reachableNodeIds, Map<Long, Long> immediateDominatorByNodeId,
			Map<Long, List<Long>> dominatedChildrenByNodeId, Map<Long, Long> retainedBytesByNodeId,
			Map<Long, Integer> entryByNodeId, Map<Long, Integer> exitByNodeId) {

		private static DominatorIndex empty() {
			return new DominatorIndex(Set.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
		}

		private boolean dominates(long ancestorId, long nodeId) {
			Integer ancestorEntry = entryByNodeId.get(ancestorId);
			Integer ancestorExit = exitByNodeId.get(ancestorId);
			Integer nodeEntry = entryByNodeId.get(nodeId);
			Integer nodeExit = exitByNodeId.get(nodeId);
			return ancestorEntry != null && ancestorExit != null && nodeEntry != null && nodeExit != null
					&& ancestorEntry <= nodeEntry && ancestorExit >= nodeExit;
		}

	}

	private record ObjectInfo(String typeName, long shallowBytes, boolean heapClass) {

		private static final ObjectInfo EMPTY = new ObjectInfo("", 0L, false);

	}

	private record Candidate(long objectId, String typeName, long shallowBytes) {
	}

	private record RefEdge(long ownerId, String ownerType, String referenceKind, String referenceName, long targetId,
			boolean staticField, String targetType) {
	}

	private record HolderDescriptor(String holderType, String holderRole, boolean preferred) {
	}

	private record HolderSelection(long holderObjectId, String holderType, String holderRole, String rootKind) {
	}

	private record CandidateAnalysis(Candidate candidate, long holderObjectId, String holderType, String holderRole,
			String rootKind, long candidateRetainedBytesApprox, long candidateReachableBytesApprox,
			long holderRetainedBytesApprox, long holderReachableBytesApprox, List<RefEdge> path, String exampleFieldPath,
			String notes) {
	}

	private record AnalysisPass(List<CandidateAnalysis> analyses, int pathSearchLimitHits) {
	}

	private record PathSearchResult(List<RefEdge> path, boolean truncated) {
	}

	private static final class TypeAccumulator {

		private long objectCount;

		private long retainedBytes;

		private long terminalShallowBytes;

	}

	private static final class HolderAccumulator {

		private final String holderType;

		private final String holderRole;

		private final String exampleFieldPath;

		private final String exampleTargetType;

		private final String notes;

		private final Map<Long, Long> retainedBytesByHolderObjectId = new HashMap<>();

		private final Map<Long, Long> reachableBytesByHolderObjectId = new HashMap<>();

		private long objectCount;

		private HolderAccumulator(String holderType, String holderRole, String exampleFieldPath, String exampleTargetType,
				String notes) {
			this.holderType = holderType;
			this.holderRole = holderRole;
			this.exampleFieldPath = exampleFieldPath;
			this.exampleTargetType = exampleTargetType;
			this.notes = notes;
		}

		private long totalRetainedBytes() {
			long total = 0L;
			for (Long retainedBytes : retainedBytesByHolderObjectId.values()) {
				total = saturatingAdd(total, retainedBytes == null ? 0L : retainedBytes);
			}
			return total;
		}

		private long totalReachableBytes() {
			long total = 0L;
			for (Long reachableBytes : reachableBytesByHolderObjectId.values()) {
				total = saturatingAdd(total, reachableBytes == null ? 0L : reachableBytes);
			}
			return total;
		}

	}

	private static final class ChainAccumulator {

		private final String rootKind;

		private final List<RetentionChainSegment> segments;

		private final String terminalType;

		private final long terminalShallowBytes;

		private long chainCount;

		private long retainedBytes;

		private long reachableBytes;

		private ChainAccumulator(String rootKind, List<RetentionChainSegment> segments, String terminalType,
				long terminalShallowBytes) {
			this.rootKind = rootKind;
			this.segments = segments;
			this.terminalType = terminalType;
			this.terminalShallowBytes = terminalShallowBytes;
		}

	}

	private static final class RootAccumulator {

		private final String rootKind;

		private final String exampleOwnerType;

		private long count;

		private RootAccumulator(String rootKind, String exampleOwnerType) {
			this.rootKind = rootKind;
			this.exampleOwnerType = exampleOwnerType;
		}

	}

}
