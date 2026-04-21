package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
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
import shark.HprofHeapGraph;
import shark.HprofRecordTag;
import shark.HeapObject;
import shark.HeapValue;

public final class SharkHeapRetentionAnalyzer implements HeapRetentionAnalyzer {

	private static final String ENGINE = "shark";

	private static final String PHASE_ONE_NOTE = "Shark path-based retention hint; retainedBytesApprox unavailable in phase 1.";

	private final int defaultTopObjects;

	private final int defaultMaxOutputChars;

	private final HeapRetentionMarkdownRenderer markdownRenderer;

	public SharkHeapRetentionAnalyzer(int defaultTopObjects, int defaultMaxOutputChars) {
		this(defaultTopObjects, defaultMaxOutputChars, new HeapRetentionMarkdownRenderer());
	}

	SharkHeapRetentionAnalyzer(int defaultTopObjects, int defaultMaxOutputChars,
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

		try (CloseableHeapGraph graph = openGraph(normalized)) {
			AnalysisIndex index = indexGraph(graph, normalizedFocusTypes(focusTypes), normalizeFocusPackages(focusPackages));
			List<PathAnalysis> analyses = analyzeCandidates(index, topN, depthLimit(analysisDepth));
			HeapRetentionSummary summary = buildSummary(normalized, analyses, topN, maxChars, focusTypes, focusPackages);
			return new HeapRetentionAnalysisResult(true, ENGINE, summary.warnings(), "", summary, summary.summaryMarkdown());
		}
		catch (IOException e) {
			return failure("Failed to read heap dump: " + e.getMessage());
		}
		catch (RuntimeException | OutOfMemoryError e) {
			String message = e instanceof OutOfMemoryError
					? "Out of memory while analyzing heap retention hints (try a smaller dump or raise -Xmx)"
					: e.getMessage();
			return failure(message == null ? e.getClass().getSimpleName() : message);
		}
	}

	private HeapRetentionSummary buildSummary(Path heapDumpPath, List<PathAnalysis> analyses, int topN, int maxChars,
			List<String> focusTypes, List<String> focusPackages) {
		List<String> warnings = new ArrayList<>();
		if (analyses.isEmpty()) {
			warnings.add("No candidate objects matched the requested focus types; returned an empty retention hint summary.");
		}
		if (focusPackages != null && !focusPackages.isEmpty()) {
			warnings.add("focusPackages influences path ranking only in phase 1; it is not a hard filter.");
		}

		List<RetainedTypeSummary> dominantTypes = buildDominantTypes(analyses, topN);
		List<SuspectedHolderSummary> holders = buildHolders(analyses, topN);
		List<RetentionChainSummary> chains = buildChains(analyses, topN);
		List<GcRootHint> rootHints = buildRootHints(analyses, topN);

		HeapRetentionConfidence confidence = new HeapRetentionConfidence(analyses.isEmpty() ? "low" : "medium",
				List.of("Retained bytes are approximate or unavailable in phase 1.",
						"reachableSubgraphBytesApprox is a path-based hint, not exact retained size."),
				List.of("Engine=shark", "Holder chains are representative, not exhaustive.",
						"focusTypes=" + normalizedFocusTypes(focusTypes)));

		HeapRetentionSummary withoutMarkdown = new HeapRetentionSummary(dominantTypes, holders, chains, rootHints,
				confidence, "", true, warnings, "");
		String markdown = markdownRenderer.render(heapDumpPath, withoutMarkdown, maxChars);
		return new HeapRetentionSummary(dominantTypes, holders, chains, rootHints, confidence, markdown, true, warnings,
				"");
	}

	private List<PathAnalysis> analyzeCandidates(AnalysisIndex index, int topN, int depthLimit) {
		int candidateLimit = Math.max(topN * 4, topN);
		List<Candidate> candidates = index.candidates().stream()
			.sorted(Comparator.comparingLong(Candidate::shallowBytes).reversed().thenComparing(Candidate::typeName))
			.limit(candidateLimit)
			.toList();

		List<PathAnalysis> analyses = new ArrayList<>();
		for (Candidate candidate : candidates) {
			PathTrace trace = traceToHolder(index, candidate, depthLimit);
			if (trace == null) {
				continue;
			}
			analyses.add(new PathAnalysis(candidate, trace));
		}

		analyses.sort(Comparator.comparingLong(PathAnalysis::approximateBytes).reversed()
			.thenComparing(analysis -> analysis.candidate().typeName()));
		return analyses;
	}

	private PathTrace traceToHolder(AnalysisIndex index, Candidate candidate, int depthLimit) {
		record QueueNode(long objectId, int depth) {
		}

		ArrayDeque<QueueNode> queue = new ArrayDeque<>();
		Map<Long, RefEdge> edgeByOwnerId = new HashMap<>();
		Map<Long, Long> childByOwnerId = new HashMap<>();
		Set<Long> visited = new HashSet<>();
		queue.add(new QueueNode(candidate.objectId(), 0));
		visited.add(candidate.objectId());

		RefEdge bestEdge = firstEdge(index.inboundEdges().get(candidate.objectId()));
		Long bestOwner = bestEdge != null ? bestEdge.ownerId() : null;
		if (bestOwner != null) {
			edgeByOwnerId.put(bestOwner, bestEdge);
			childByOwnerId.put(bestOwner, candidate.objectId());
		}

		while (!queue.isEmpty() && visited.size() <= 512) {
			QueueNode node = queue.removeFirst();
			List<RefEdge> inbound = orderedInbound(index, index.inboundEdges().get(node.objectId()));
			for (RefEdge edge : inbound) {
				if (!visited.add(edge.ownerId())) {
					continue;
				}
				edgeByOwnerId.put(edge.ownerId(), edge);
				childByOwnerId.put(edge.ownerId(), node.objectId());
				bestOwner = edge.ownerId();
				bestEdge = edge;
				if (edge.staticField() || index.gcRootKindsByObjectId().containsKey(edge.ownerId())) {
					return buildTrace(index, candidate, edge.ownerId(), edgeByOwnerId, childByOwnerId);
				}
				if (node.depth() + 1 < depthLimit) {
					queue.addLast(new QueueNode(edge.ownerId(), node.depth() + 1));
				}
			}
		}

		if (bestOwner == null || bestEdge == null) {
			return null;
		}
		return buildTrace(index, candidate, bestOwner, edgeByOwnerId, childByOwnerId);
	}

	private PathTrace buildTrace(AnalysisIndex index, Candidate candidate, long holderObjectId, Map<Long, RefEdge> edgeByOwnerId,
			Map<Long, Long> childByOwnerId) {
		List<RefEdge> chain = new ArrayList<>();
		long currentOwner = holderObjectId;
		while (currentOwner != candidate.objectId()) {
			RefEdge edge = edgeByOwnerId.get(currentOwner);
			if (edge == null) {
				break;
			}
			chain.add(edge);
			Long childId = childByOwnerId.get(currentOwner);
			if (childId == null || childId == currentOwner) {
				break;
			}
			currentOwner = childId;
		}
		if (chain.isEmpty()) {
			return null;
		}
		String holderType = chain.get(0).ownerType();
		String holderRole = classifyHolderRole(chain.get(0));
		String rootKind = chain.get(0).staticField() ? "system-class"
				: firstOrDefault(index.gcRootKindsByObjectId().get(holderObjectId), "unknown");
		String exampleFieldPath = buildFieldPath(chain);
		List<RetentionChainSegment> segments = new ArrayList<>(chain.size());
		for (RefEdge edge : chain) {
			ObjectInfo target = index.objectsById().get(edge.targetId());
			segments.add(new RetentionChainSegment(edge.ownerType(), edge.referenceKind(), edge.referenceName(),
					target == null ? "(unknown)" : target.typeName()));
		}
		return new PathTrace(holderObjectId, holderType, holderRole, rootKind, exampleFieldPath,
				List.copyOf(segments), candidate.shallowBytes(), PHASE_ONE_NOTE);
	}

	private static String buildFieldPath(List<RefEdge> chain) {
		StringBuilder sb = new StringBuilder(chain.get(0).ownerType());
		for (RefEdge edge : chain) {
			if ("array-element".equals(edge.referenceKind())) {
				sb.append("[").append(edge.referenceName()).append("]");
			}
			else {
				sb.append(".").append(edge.referenceName());
			}
		}
		return sb.toString();
	}

	private static String classifyHolderRole(RefEdge edge) {
		if (edge.staticField()) {
			return "static-field-owner";
		}
		String type = edge.ownerType().toLowerCase(Locale.ROOT);
		if (type.contains("threadlocal")) {
			return "thread-local";
		}
		if (type.contains("thread")) {
			return "thread-owner";
		}
		if (type.contains("map")) {
			return "map";
		}
		if (type.contains("list") || type.contains("set") || type.contains("queue") || type.contains("collection")) {
			return "collection";
		}
		if (type.endsWith("[]")) {
			return "array-owner";
		}
		return "unknown";
	}

	private List<RetainedTypeSummary> buildDominantTypes(List<PathAnalysis> analyses, int topN) {
		Map<String, TypeAccumulator> types = new LinkedHashMap<>();
		long totalBytes = 0L;
		for (PathAnalysis analysis : analyses) {
			totalBytes += analysis.approximateBytes();
			TypeAccumulator accumulator = types.computeIfAbsent(analysis.candidate().typeName(), ignored -> new TypeAccumulator());
			accumulator.objectCount++;
			accumulator.terminalShallowBytes += analysis.candidate().shallowBytes();
		}
		final long trackedBytes = totalBytes;

		return types.entrySet().stream()
			.map(entry -> {
				TypeAccumulator accumulator = entry.getValue();
				double share = trackedBytes <= 0L ? 0.0d
						: (100.0d * accumulator.terminalShallowBytes / (double) trackedBytes);
				return new RetainedTypeSummary(entry.getKey(), null, accumulator.objectCount, share,
						accumulator.terminalShallowBytes);
			})
			.sorted(Comparator.comparingLong(RetainedTypeSummary::terminalShallowBytes).reversed())
			.limit(topN)
			.toList();
	}

	private List<SuspectedHolderSummary> buildHolders(List<PathAnalysis> analyses, int topN) {
		Map<String, HolderAccumulator> holders = new LinkedHashMap<>();
		for (PathAnalysis analysis : analyses) {
			String key = analysis.trace().holderType() + "|" + analysis.trace().holderRole() + "|"
					+ analysis.trace().exampleFieldPath();
			HolderAccumulator accumulator = holders.computeIfAbsent(key,
					ignored -> new HolderAccumulator(analysis.trace().holderType(), analysis.trace().holderRole(),
							analysis.trace().exampleFieldPath(), analysis.candidate().typeName(), analysis.trace().notes()));
			accumulator.reachableBytes += analysis.approximateBytes();
			accumulator.objectCount++;
		}

		return holders.values().stream()
			.map(accumulator -> new SuspectedHolderSummary(accumulator.holderType, accumulator.holderRole, null,
					accumulator.reachableBytes, accumulator.objectCount, accumulator.exampleFieldPath,
					accumulator.exampleTargetType, accumulator.notes))
			.sorted(Comparator.comparingLong(SuspectedHolderSummary::reachableSubgraphBytesApprox).reversed())
			.limit(topN)
			.toList();
	}

	private List<RetentionChainSummary> buildChains(List<PathAnalysis> analyses, int topN) {
		Map<String, ChainAccumulator> chains = new LinkedHashMap<>();
		for (PathAnalysis analysis : analyses) {
			String signature = analysis.trace().rootKind() + "|" + analysis.trace().segments() + "|"
					+ analysis.candidate().typeName();
			ChainAccumulator accumulator = chains.computeIfAbsent(signature,
					ignored -> new ChainAccumulator(analysis.trace().rootKind(), analysis.trace().segments(),
							analysis.candidate().typeName(), analysis.candidate().shallowBytes()));
			accumulator.chainCount++;
			accumulator.reachableBytes += analysis.approximateBytes();
		}

		return chains.values().stream()
			.map(accumulator -> new RetentionChainSummary(accumulator.rootKind, accumulator.segments,
					accumulator.terminalType, accumulator.terminalShallowBytes, accumulator.chainCount, null,
					accumulator.reachableBytes))
			.sorted(Comparator.comparingLong(RetentionChainSummary::reachableSubgraphBytesApprox).reversed())
			.limit(topN)
			.toList();
	}

	private List<GcRootHint> buildRootHints(List<PathAnalysis> analyses, int topN) {
		Map<String, RootAccumulator> roots = new LinkedHashMap<>();
		for (PathAnalysis analysis : analyses) {
			RootAccumulator accumulator = roots.computeIfAbsent(analysis.trace().rootKind(),
					ignored -> new RootAccumulator(analysis.trace().rootKind(), analysis.trace().holderType()));
			accumulator.count++;
		}

		return roots.values().stream()
			.map(accumulator -> new GcRootHint(accumulator.rootKind, accumulator.exampleOwnerType, accumulator.count,
					"Phase-1 Shark root hint; representative rather than exhaustive."))
			.sorted(Comparator.comparingLong(GcRootHint::occurrenceCountApprox).reversed())
			.limit(topN)
			.toList();
	}

	private AnalysisIndex indexGraph(CloseableHeapGraph graph, Set<String> focusTypes, List<String> focusPackages) {
		Map<Long, ObjectInfo> objectsById = new HashMap<>();
		Map<Long, List<RefEdge>> inboundEdges = new HashMap<>();
		List<Candidate> candidates = new ArrayList<>();
		Map<Long, List<String>> gcRootKinds = new HashMap<>();

		for (GcRoot gcRoot : graph.getGcRoots()) {
			gcRootKinds.computeIfAbsent(gcRoot.getId(), ignored -> new ArrayList<>()).add(normalizeGcRootKind(gcRoot));
		}

		Iterator<HeapObject> objects = graph.getObjects().iterator();
		while (objects.hasNext()) {
			HeapObject object = objects.next();
			HeapObject.HeapClass heapClass = object.getAsClass();
			if (heapClass != null) {
				objectsById.put(heapClass.getObjectId(), new ObjectInfo(heapClass.getName(), 0L, true));
				Iterator<HeapField> staticFields = heapClass.readStaticFields().iterator();
				while (staticFields.hasNext()) {
					HeapField field = staticFields.next();
					addReference(inboundEdges, heapClass.getObjectId(), heapClass.getName(), "static-field", field.getName(),
							field.getValue(), true);
				}
				continue;
			}

			HeapObject.HeapInstance instance = object.getAsInstance();
			if (instance != null) {
				objectsById.put(instance.getObjectId(),
						new ObjectInfo(instance.getInstanceClassName(), instance.getByteSize(), false));
				maybeAddCandidate(candidates, instance.getObjectId(), instance.getInstanceClassName(), instance.getByteSize(),
						focusTypes);
				Iterator<HeapField> fields = instance.readFields().iterator();
				while (fields.hasNext()) {
					HeapField field = fields.next();
					addReference(inboundEdges, instance.getObjectId(), instance.getInstanceClassName(), "field", field.getName(),
							field.getValue(), false);
				}
				continue;
			}

			HeapObject.HeapObjectArray objectArray = object.getAsObjectArray();
			if (objectArray != null) {
				objectsById.put(objectArray.getObjectId(),
						new ObjectInfo(objectArray.getArrayClassName(), objectArray.getByteSize(), false));
				maybeAddCandidate(candidates, objectArray.getObjectId(), objectArray.getArrayClassName(),
						objectArray.getByteSize(), focusTypes);
				Iterator<HeapValue> elements = objectArray.readElements().iterator();
				int index = 0;
				while (elements.hasNext()) {
					addReference(inboundEdges, objectArray.getObjectId(), objectArray.getArrayClassName(), "array-element",
							Integer.toString(index), elements.next(), false);
					index++;
				}
				continue;
			}

			HeapObject.HeapPrimitiveArray primitiveArray = object.getAsPrimitiveArray();
			if (primitiveArray != null) {
				objectsById.put(primitiveArray.getObjectId(),
						new ObjectInfo(primitiveArray.getArrayClassName(), primitiveArray.getByteSize(), false));
				maybeAddCandidate(candidates, primitiveArray.getObjectId(), primitiveArray.getArrayClassName(),
						primitiveArray.getByteSize(), focusTypes);
			}
		}

		if (!focusPackages.isEmpty()) {
			candidates.sort(candidateComparator(focusPackages));
		}
		return new AnalysisIndex(objectsById, inboundEdges, gcRootKinds, List.copyOf(candidates));
	}

	private static Comparator<Candidate> candidateComparator(List<String> focusPackages) {
		return Comparator.comparingInt((Candidate candidate) -> packagePreference(candidate.typeName(), focusPackages))
			.reversed()
			.thenComparing(Comparator.comparingLong(Candidate::shallowBytes).reversed())
			.thenComparing(Candidate::typeName);
	}

	private static void maybeAddCandidate(List<Candidate> candidates, long objectId, String typeName, long shallowBytes,
			Set<String> focusTypes) {
		if (!isCandidateType(typeName, focusTypes)) {
			return;
		}
		candidates.add(new Candidate(objectId, typeName, shallowBytes));
	}

	private static boolean isCandidateType(String typeName, Set<String> focusTypes) {
		String normalized = normalizeTypeName(typeName);
		if (!focusTypes.isEmpty()) {
			return focusTypes.contains(normalized);
		}
		return normalized.endsWith("[]");
	}

	private static void addReference(Map<Long, List<RefEdge>> inboundEdges, long ownerId, String ownerType,
			String referenceKind, String referenceName, HeapValue value, boolean staticField) {
		if (value == null || !value.isNonNullReference()) {
			return;
		}
		Long targetId = value.getAsObjectId();
		if (targetId == null) {
			return;
		}
		inboundEdges.computeIfAbsent(targetId, ignored -> new ArrayList<>())
			.add(new RefEdge(ownerId, ownerType, referenceKind, referenceName, targetId, staticField));
	}

	private static List<RefEdge> orderedInbound(AnalysisIndex index, List<RefEdge> inbound) {
		if (inbound == null || inbound.isEmpty()) {
			return List.of();
		}
		return inbound.stream()
			.sorted(Comparator.comparingInt((RefEdge edge) -> scoreEdge(index, edge)).reversed()
				.thenComparing(RefEdge::ownerType))
			.toList();
	}

	private static int scoreEdge(AnalysisIndex index, RefEdge edge) {
		int score = 0;
		if (edge.staticField()) {
			score += 100;
		}
		if (index.gcRootKindsByObjectId().containsKey(edge.ownerId())) {
			score += 50;
		}
		String role = classifyHolderRole(edge);
		if (!"unknown".equals(role)) {
			score += 10;
		}
		return score;
	}

	private static RefEdge firstEdge(List<RefEdge> inbound) {
		if (inbound == null || inbound.isEmpty()) {
			return null;
		}
		return inbound.get(0);
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
			return 7;
		}
		return switch (analysisDepth.trim().toLowerCase(Locale.ROOT)) {
			case "fast" -> 4;
			case "deep" -> 12;
			default -> 7;
		};
	}

	private static int normalizePositive(Integer value, int defaultValue) {
		return value != null && value > 0 ? value : defaultValue;
	}

	private static String firstOrDefault(Collection<String> values, String fallback) {
		if (values == null || values.isEmpty()) {
			return fallback;
		}
		return values.iterator().next();
	}

	private static CloseableHeapGraph openGraph(Path heapDumpPath) throws IOException {
		return HprofHeapGraph.Companion.openHeapGraph(heapDumpPath.toFile(), null, EnumSet.allOf(HprofRecordTag.class));
	}

	private static HeapRetentionAnalysisResult failure(String errorMessage) {
		HeapRetentionSummary summary = new HeapRetentionSummary(List.of(), List.of(), List.of(), List.of(),
				new HeapRetentionConfidence("low", List.of("Retention analysis did not complete."),
						List.of("Engine=shark")),
				"", false, List.of(), errorMessage == null ? "" : errorMessage);
		return new HeapRetentionAnalysisResult(false, ENGINE, List.of(), errorMessage, summary, "");
	}

	private record AnalysisIndex(Map<Long, ObjectInfo> objectsById, Map<Long, List<RefEdge>> inboundEdges,
			Map<Long, List<String>> gcRootKindsByObjectId, List<Candidate> candidates) {
	}

	private record ObjectInfo(String typeName, long shallowBytes, boolean heapClass) {
	}

	private record Candidate(long objectId, String typeName, long shallowBytes) {
	}

	private record RefEdge(long ownerId, String ownerType, String referenceKind, String referenceName, long targetId,
			boolean staticField) {
	}

	private record PathTrace(long holderObjectId, String holderType, String holderRole, String rootKind,
			String exampleFieldPath, List<RetentionChainSegment> segments, long reachableSubgraphBytesApprox, String notes) {
	}

	private record PathAnalysis(Candidate candidate, PathTrace trace) {

		private long approximateBytes() {
			return trace.reachableSubgraphBytesApprox();
		}

	}

	private static final class TypeAccumulator {

		private long objectCount;

		private long terminalShallowBytes;

	}

	private static final class HolderAccumulator {

		private final String holderType;

		private final String holderRole;

		private final String exampleFieldPath;

		private final String exampleTargetType;

		private final String notes;

		private long reachableBytes;

		private long objectCount;

		private HolderAccumulator(String holderType, String holderRole, String exampleFieldPath, String exampleTargetType,
				String notes) {
			this.holderType = holderType;
			this.holderRole = holderRole;
			this.exampleFieldPath = exampleFieldPath;
			this.exampleTargetType = exampleTargetType;
			this.notes = notes;
		}

	}

	private static final class ChainAccumulator {

		private final String rootKind;

		private final List<RetentionChainSegment> segments;

		private final String terminalType;

		private final long terminalShallowBytes;

		private long chainCount;

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
