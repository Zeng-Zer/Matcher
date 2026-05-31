package matcher.cli.provider.builtin;

import java.util.function.Predicate;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import matcher.cli.MatcherCli;
import matcher.cli.provider.CliCommandProvider;
import matcher.core.Matcher;
import matcher.core.serdes.MatchesIo;
import matcher.model.InputFile;
import matcher.model.classifier.ClassClassifier;
import matcher.model.classifier.ClassifierLevel;
import matcher.model.classifier.ClassifierUtil;
import matcher.model.classifier.FieldClassifier;
import matcher.model.classifier.MethodClassifier;
import matcher.model.classifier.MethodVarClassifier;
import matcher.model.classifier.RankResult;
import matcher.model.config.Config;
import matcher.model.config.ProjectConfig;
import matcher.model.type.ClassEnvironment;
import matcher.model.type.ClassInstance;
import matcher.model.type.FieldInstance;
import matcher.model.type.MethodInstance;
import matcher.model.type.MethodVarInstance;
import matcher.model.NameType;

import matcher.model.mapping.Mappings;
import matcher.model.mapping.MappingsExportVerbosity;
import net.fabricmc.mappingio.format.MappingFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides the default {@code automatch} command.
 */
public class AutomatchCliCommandProvider implements CliCommandProvider {

	private static final Logger LOGGER = LoggerFactory.getLogger("Automatch CLI");

	@Parameters(commandNames = {commandName})
	class AutomatchCommand {
		@Parameter(names = {BuiltinCliParameters.INPUTS_A}, required = true)
		List<Path> inputsA = Collections.emptyList();

		@Parameter(names = {BuiltinCliParameters.INPUTS_B}, required = true)
		List<Path> inputsB = Collections.emptyList();

		@Parameter(names = {BuiltinCliParameters.CLASSPATH_A})
		List<Path> classpathA = Collections.emptyList();

		@Parameter(names = {BuiltinCliParameters.CLASSPATH_B})
		List<Path> classpathB = Collections.emptyList();

		@Parameter(names = {BuiltinCliParameters.SHARED_CLASSPATH})
		List<Path> sharedClasspath = Collections.emptyList();

		@Parameter(names = {BuiltinCliParameters.INPUTS_BEFORE_CLASSPATH})
		boolean inputsBeforeClasspath;

		@Parameter(names = {BuiltinCliParameters.NON_OBFUSCATED_CLASS_PATTERN_A})
		String nonObfuscatedClassPatternA = "";

		@Parameter(names = {BuiltinCliParameters.NON_OBFUSCATED_CLASS_PATTERN_B})
		String nonObfuscatedClassPatternB = "";

		@Parameter(names = {BuiltinCliParameters.NON_OBFUSCATED_MEMBER_PATTERN_A})
		String nonObfuscatedMemberPatternA = "";

		@Parameter(names = {BuiltinCliParameters.NON_OBFUSCATED_MEMBER_PATTERN_B})
		String nonObfuscatedMemberPatternB = "";

		@Parameter(names = {BuiltinCliParameters.MAPPINGS_A})
		Path mappingsPathA;

		@Parameter(names = {BuiltinCliParameters.MAPPINGS_B})
		Path mappingsPathB;

		@Parameter(names = {BuiltinCliParameters.OUTPUT_FILE}, required = true)
		Path outputFile;

		@Parameter(names = {BuiltinCliParameters.DONT_SAVE_UNMAPPED_MATCHES})
		boolean dontSaveUnmappedMatches;

		@Parameter(names = {BuiltinCliParameters.PASSES})
		int passes = 1;

		@Parameter(names = {BuiltinCliParameters.CLASS_BY_CLASS})
		boolean classByClass;

		@Parameter(names = {BuiltinCliParameters.CLASS_BY_CLASS_TIMEOUT})
		int classByClassTimeout = 180; // seconds

		@Parameter(names = {BuiltinCliParameters.CLASS_BY_CLASS_LEVEL})
		ClassifierLevel classByClassLevel = ClassifierLevel.Full;


		@Parameter(names = {BuiltinCliParameters.FROM_MAPPING})
		boolean fromMapping;
		@Parameter(names = {BuiltinCliParameters.OUTPUT_MAPPING})
		Path outputMapping;

		@Parameter(names = {BuiltinCliParameters.LOAD_MATCH})
		Path loadMatch;

		@Parameter(names = {BuiltinCliParameters.LIST_UNMATCHED_MAPPED})
		boolean listUnmatchedMapped;
	}

	@Override
	public String getCommandName() {
		return commandName;
	}

	@Override
	public Object getDataHolder() {
		return command;
	}

	@Override
	public void processArgs() {
		Matcher.init();
		ClassEnvironment env = new ClassEnvironment();
		Matcher matcher = new Matcher(env);
		ProjectConfig config = new ProjectConfig.Builder(InputFile.fromPaths(command.inputsA), InputFile.fromPaths(command.inputsB))
				.classPathA(InputFile.fromPaths(command.classpathA))
				.classPathB(InputFile.fromPaths(command.classpathB))
				.sharedClassPath(InputFile.fromPaths(command.sharedClasspath))
				.inputsBeforeClassPath(command.inputsBeforeClasspath)
				.mappingsPathA(command.mappingsPathA)
				.mappingsPathB(command.mappingsPathB)
				.saveUnmappedMatches(!command.dontSaveUnmappedMatches)
				.nonObfuscatedClassPatternA(command.nonObfuscatedClassPatternA)
				.nonObfuscatedClassPatternB(command.nonObfuscatedClassPatternB)
				.nonObfuscatedMemberPatternA(command.nonObfuscatedMemberPatternA)
				.nonObfuscatedMemberPatternB(command.nonObfuscatedMemberPatternB)
				.build();

		Config.setProjectConfig(config);
		matcher.init(config, (progress) -> { });

		if (command.loadMatch != null) {
			MatchesIo.read(command.loadMatch, null, false, matcher, (progress) -> { });
			MatcherCli.LOGGER.info("Loaded {} existing matches", command.loadMatch);
		}

		if (command.listUnmatchedMapped) {
			long count = env.getClassesA().stream()
					.filter(cls -> cls.isReal() && cls.hasOwnMappedName() && !cls.hasMatch() && cls.isMatchable())
					.peek(cls -> System.out.println("UNMATCHED: " + cls + " -> " + cls.getName(NameType.MAPPED_PLAIN)))
					.count();
			MatcherCli.LOGGER.info("Total unmatched mapped-name classes: {}", count);
			return;
		}

		if (command.fromMapping && command.loadMatch == null) {
			// Bulk + sequential from mapping
			runBulk(matcher);
			runFromMappingSequential(matcher, env);
		} else if (command.fromMapping) {
			// Skipping bulk, just sequential from mapping
			runFromMappingSequential(matcher, env);
		} else if (command.classByClass) {
			runClassByClass(matcher, env);
		} else if (command.loadMatch == null) {
			// Neither --load-match nor --from-mapping: bulk only
			runBulk(matcher);
		} else {
			// --load-match only: save checkpoint + mapping
			checkpoint(matcher);
			saveMapping(matcher);
		}

		MatcherCli.LOGGER.info("Auto-matching done!");
	}

	private void runBulk(Matcher matcher) {
		for (int i = 0; i < command.passes; i++) {
			matcher.autoMatchAll((progress) -> { });
		}
		// Write matches file
		try {
			Files.deleteIfExists(command.outputFile);
			MatchesIo.write(matcher, command.outputFile);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}

		saveMapping(matcher);
	}

	private void runFromMappingSequential(Matcher matcher, ClassEnvironment env) {
		// Checkpoint after bulk run
		checkpoint(matcher);

		boolean assumeBothOrNoneObfuscated = env.assumeBothOrNoneObfuscated;

		// Filter A: only classes with own mapped name, not yet matched
		Predicate<ClassInstance> filterA = cls -> cls.isReal() && cls.hasOwnMappedName() && !cls.hasMatch() && cls.isMatchable();
		Predicate<ClassInstance> filterB = cls -> cls.isReal() && (!assumeBothOrNoneObfuscated || cls.isNameObfuscated()) && !cls.hasMatch() && cls.isMatchable();

		List<ClassInstance> classes = env.getClassesA().stream()
				.filter(filterA)
				.collect(Collectors.toList());

		if (classes.isEmpty()) {
			LOGGER.info("No classes with mapped names remain to match");
			saveMapping(matcher);
			return;
		}

		ClassifierLevel level = command.classByClassLevel;
		// From-mapping mode: always pick the highest-scoring candidate for classes
		// without threshold pruning. Methods/fields still use strict thresholds.
		double classAbsThreshold = 0;
		double classRelThreshold = 0;
		double maxScore = ClassClassifier.getMaxScore(level);
		double maxMismatch = maxScore;
		
		double absThreshold = 0.85;
		double relThreshold = 0.085;

		long timeoutMs = command.classByClassTimeout * 1000L;

		int total = classes.size();
		int matched = 0;
		int failed = 0;
		int timedOut = 0;

		LOGGER.info("From-mapping sequential matching: {} classes to process ({} timeout)", total, formatDuration(command.classByClassTimeout));

		ExecutorService singleThread = Executors.newSingleThreadExecutor();
		try {
			for (int idx = 0; idx < total; idx++) {
				ClassInstance cls = classes.get(idx);

				// Skip if already matched (e.g. by a prior iteration's method/field matching)
				if (cls.hasMatch() || !cls.isMatchable()) continue;

				LOGGER.info("[{}/{}] Matching class {}", idx + 1, total, cls);

				try {
					Future<ClassInstance> future = singleThread.submit(() -> {
						ClassInstance[] candidates = env.getClassesB().stream()
								.filter(filterB)
								.collect(Collectors.toList()).toArray(new ClassInstance[0]);

						List<RankResult<ClassInstance>> ranking = ClassClassifier.rank(cls, candidates, level, env, maxMismatch);

						// In from-mapping mode: always pick the highest-scoring candidate,
						// regardless of threshold. The user validates these manually in GUI.
						if (!ranking.isEmpty()) {
							return ranking.get(0).getSubject();
						}
						return null;
					});

					ClassInstance match = future.get(timeoutMs, TimeUnit.MILLISECONDS);

					if (match != null) {
						matcher.match(cls, match);
						matched++;

						// Auto-match all methods and fields for this class
						matchMethods(matcher, cls, level, absThreshold, relThreshold);
						matchFields(matcher, cls, level, absThreshold, relThreshold);

						// Checkpoint: overwrite main output file
						checkpoint(matcher);
						LOGGER.info("  -> Matched {} (total matched: {}, total failed: {}, timed out: {})", match, matched, failed, timedOut);
					} else {
						LOGGER.info("  -> No candidate above threshold");
					}
				} catch (TimeoutException e) {
					timedOut++;
					LOGGER.warn("  -> Timed out after {}, skipping", formatDuration(command.classByClassTimeout));
				} catch (CancellationException e) {
					timedOut++;
					LOGGER.warn("  -> Cancelled, skipping");
				} catch (ExecutionException e) {
					failed++;
					LOGGER.warn("  -> Failed: {}, skipping", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
				} catch (Exception e) {
					failed++;
					LOGGER.warn("  -> Unexpected error: {}, skipping", e.getMessage());
				}
			}
		} finally {
			singleThread.shutdown();
		}

		// Final save
		finalSave(matcher);

		// Write mapping if requested
		saveMapping(matcher);

		LOGGER.info("From-mapping sequential matching done: {} matched, {} failed, {} timed out", matched, failed, timedOut);
	}

	private void runClassByClass(Matcher matcher, ClassEnvironment env) {
		// First run the standard bulk auto-match (Intermediate, capped)
		for (int i = 0; i < command.passes; i++) {
			matcher.autoMatchAll((progress) -> { });
		}

		// Ensure auto-save from bulk phase
		autoSave(matcher);

		ExecutorService singleThread = Executors.newSingleThreadExecutor();
		ClassifierLevel level = command.classByClassLevel;
		double absThreshold = 0.85;
		double relThreshold = 0.085;
		double maxScore = ClassClassifier.getMaxScore(level);
		double maxMismatch = maxScore - ClassifierUtil.getRawScore(absThreshold * (1 - relThreshold), maxScore);
		long timeoutMs = command.classByClassTimeout * 1000L;

		boolean assumeBothOrNoneObfuscated = env.assumeBothOrNoneObfuscated;
		Predicate<ClassInstance> unmatchedFilter = cls -> cls.isReal() && (!assumeBothOrNoneObfuscated || cls.isNameObfuscated()) && !cls.hasMatch() && cls.isMatchable();

		List<ClassInstance> unmatchedA = env.getClassesA().stream()
				.filter(unmatchedFilter)
				.collect(Collectors.toList());

		int total = unmatchedA.size();
		int matched = 0;
		int failed = 0;
		int timedOut = 0;

		LOGGER.info("Class-by-class matching: {} classes to process ({} timeout)", total, formatDuration(command.classByClassTimeout));

		for (int idx = 0; idx < total; idx++) {
			ClassInstance cls = unmatchedA.get(idx);

			// Skip if already matched (e.g. by autoMatchMethods/Fields in a prior iteration)
			if (cls.hasMatch() || !cls.isMatchable()) continue;

			LOGGER.info("[{}/{}] Matching class {}", idx + 1, total, cls);

			try {
				Future<ClassInstance> future = singleThread.submit(() -> {
					// Build fresh candidates to exclude already-matched
					ClassInstance[] freshCandidates = env.getClassesB().stream()
							.filter(unmatchedFilter)
							.collect(Collectors.toList()).toArray(new ClassInstance[0]);

					List<RankResult<ClassInstance>> ranking = ClassClassifier.rank(cls, freshCandidates, level, env, maxMismatch);

					if (!ranking.isEmpty() && ClassifierUtil.checkRank(ranking, absThreshold, relThreshold, maxScore)) {
						return ranking.get(0).getSubject();
					}

					return null;
				});

				ClassInstance match = future.get(timeoutMs, TimeUnit.MILLISECONDS);

				if (match != null) {
					matcher.match(cls, match);
					matched++;

					// Auto-match methods and fields for this newly-matched class
					matchMethods(matcher, cls, level, absThreshold, relThreshold);
					matchFields(matcher, cls, level, absThreshold, relThreshold);

					// Incremental save
					autoSave(matcher);
					LOGGER.info("  -> Matched {} (total matched: {})", match, matched);
				} else {
					LOGGER.info("  -> No candidate above threshold");
				}
			} catch (TimeoutException e) {
				timedOut++;
				LOGGER.warn("  -> Timed out after {}, skipping", formatDuration(command.classByClassTimeout));
			} catch (CancellationException e) {
				timedOut++;
				LOGGER.warn("  -> Cancelled, skipping");
			} catch (ExecutionException e) {
				failed++;
				LOGGER.warn("  -> Failed: {}, skipping", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
			} catch (Exception e) {
				failed++;
				LOGGER.warn("  -> Unexpected error: {}, skipping", e.getMessage());
			}
		}

		// Final save
		finalSave(matcher);

		// Write mapping if requested
		saveMapping(matcher);
	}

	private static void matchMethods(Matcher matcher, ClassInstance cls, ClassifierLevel level, double absThreshold, double relThreshold) {
		try {
			if (!cls.hasMatch()) return;
			
			double maxScore = MethodClassifier.getMaxScore(level);
			double maxMismatch = maxScore - ClassifierUtil.getRawScore(absThreshold * (1 - relThreshold), maxScore);

			for (MethodInstance m : cls.getMethods()) {
				if (m.hasMatch() || !m.isMatchable()) continue;

				List<RankResult<MethodInstance>> ranking = MethodClassifier.rank(m, cls.getMatch().getMethods(), level, matcher.getEnv(), maxMismatch);

				if (!ranking.isEmpty() && ClassifierUtil.checkRank(ranking, absThreshold, relThreshold, maxScore)) {
					matcher.match(m, ranking.get(0).getSubject());

					// Auto-match method vars
					matchMethodVars(matcher, m, level, absThreshold, relThreshold);
				}
			}
		} catch (Exception e) {
			LOGGER.warn("  -> Method matching failed for class {}: {}", cls, e.getMessage());
		}
	}

	private static void matchFields(Matcher matcher, ClassInstance cls, ClassifierLevel level, double absThreshold, double relThreshold) {
		try {
			if (!cls.hasMatch()) return;

			double maxScore = FieldClassifier.getMaxScore(level);
			double maxMismatch = maxScore - ClassifierUtil.getRawScore(absThreshold * (1 - relThreshold), maxScore);

			for (FieldInstance f : cls.getFields()) {
				if (f.hasMatch() || !f.isMatchable()) continue;

				List<RankResult<FieldInstance>> ranking = FieldClassifier.rank(f, cls.getMatch().getFields(), level, matcher.getEnv(), maxMismatch);

				if (!ranking.isEmpty() && ClassifierUtil.checkRank(ranking, absThreshold, relThreshold, maxScore)) {
					matcher.match(f, ranking.get(0).getSubject());
				}
			}
		} catch (Exception e) {
			LOGGER.warn("  -> Field matching failed for class {}: {}", cls, e.getMessage());
		}
	}

	private static void matchMethodVars(Matcher matcher, MethodInstance m, ClassifierLevel level, double absThreshold, double relThreshold) {
		try {
			if (!m.hasMatch()) return;

			double maxScore = MethodVarClassifier.getMaxScore(level);
			double maxMismatch = maxScore - ClassifierUtil.getRawScore(absThreshold * (1 - relThreshold), maxScore);

			for (MethodVarInstance arg : m.getArgs()) {
				if (arg.hasMatch() || !arg.isMatchable()) continue;

				List<RankResult<MethodVarInstance>> ranking = MethodVarClassifier.rank(arg, m.getMatch().getArgs(), level, matcher.getEnv(), maxMismatch);

				if (!ranking.isEmpty() && ClassifierUtil.checkRank(ranking, absThreshold, relThreshold, maxScore)) {
					matcher.match(arg, ranking.get(0).getSubject());
				}
			}

			for (MethodVarInstance var : m.getVars()) {
				if (var.hasMatch() || !var.isMatchable()) continue;

				List<RankResult<MethodVarInstance>> ranking = MethodVarClassifier.rank(var, m.getMatch().getVars(), level, matcher.getEnv(), maxMismatch);

				if (!ranking.isEmpty() && ClassifierUtil.checkRank(ranking, absThreshold, relThreshold, maxScore)) {
					matcher.match(var, ranking.get(0).getSubject());
				}
			}
		} catch (Exception e) {
			LOGGER.warn("  -> Method var matching failed for {}: {}", m, e.getMessage());
		}
	}

	private void autoSave(Matcher matcher) {
		try {
			// Create auto-save path next to the output file
			Path autoSavePath = getAutoSavePath();

			// Delete then re-create for atomic-ish incremental write
			Files.deleteIfExists(autoSavePath);
			MatchesIo.write(matcher, autoSavePath);
		} catch (Throwable e) {
			LOGGER.warn("Auto-save failed: {}", e.getMessage());
		}
	}

	private void finalSave(Matcher matcher) {
		try {
			Files.deleteIfExists(command.outputFile);
			MatchesIo.write(matcher, command.outputFile);
			LOGGER.info("Final matches saved to {}", command.outputFile);

			// Clean up auto-save
			Path autoSavePath = getAutoSavePath();
			Files.deleteIfExists(autoSavePath);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	private Path getAutoSavePath() {
		String name = command.outputFile.getFileName().toString();
		return command.outputFile.resolveSibling(name + ".autosave");
	}

	private void checkpoint(Matcher matcher) {
		// Overwrite the main output file with current state
		try {
			Files.deleteIfExists(command.outputFile);
			MatchesIo.write(matcher, command.outputFile);
			LOGGER.info("Checkpoint saved to {}", command.outputFile);
		} catch (Throwable e) {
			LOGGER.warn("Checkpoint failed: {}", e.getMessage());
		}
	}

	private static String formatDuration(int seconds) {
		if (seconds < 60) return seconds + "s";
		return seconds / 60 + "m" + (seconds % 60 > 0 ? " " + seconds % 60 + "s" : "");
	}
	private void saveMapping(Matcher matcher) {
		if (command.outputMapping == null) return;

		try {
			List<NameType> nsTypes = Arrays.asList(NameType.PLAIN, NameType.MAPPED_PLAIN);
			List<String> nsNames = Arrays.asList("original", "mapped");

			Files.deleteIfExists(command.outputMapping);

			if (Mappings.save(command.outputMapping, MappingFormat.TINY_2_FILE,
					matcher.getEnv().getEnvB(),
					nsTypes, nsNames,
					MappingsExportVerbosity.FULL, true, true)) {
				LOGGER.info("Mapping saved to {}", command.outputMapping);
			} else {
				LOGGER.warn("No mappings to save");
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static final String commandName = "automatch";
	private final AutomatchCommand command = new AutomatchCommand();
}
