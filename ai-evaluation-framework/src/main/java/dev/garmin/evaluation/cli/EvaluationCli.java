package dev.garmin.evaluation.cli;

import dev.garmin.evaluation.core.Json;
import dev.garmin.evaluation.core.Status;
import dev.garmin.evaluation.dataset.EvaluationSuite;
import dev.garmin.evaluation.report.Capture;
import dev.garmin.evaluation.report.PrivateFiles;
import dev.garmin.evaluation.report.Reports;
import dev.garmin.evaluation.review.HumanReview;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class EvaluationCli {
    private static final String USAGE = """
            Usage: java -jar ai-evaluation.jar <list|run|review|compare> [options]

              list
              run --output PATH [--mode model|application] [--split regression|holdout|all]
                  [--stage routing|explanation|all] [--repetitions 1..20] [--timeout SECONDS]
                  Model options: --model TAG --ollama-url ORIGIN --temperature N
                                 --num-ctx N --num-predict N --num-gpu N
                  Application:   --synthetic-database --deployment-id ID --application-url ORIGIN
              review RUN_DIR ANNOTATIONS --output PATH
              compare BASELINE CANDIDATE --output PATH [--baseline-results NAME] [--candidate-results NAME]
            """;

    private EvaluationCli() {}

    public static void main(String[] args) {
        int code;
        try {
            code = execute(args, Path.of("").toAbsolutePath(), System.out);
        } catch (IOException | IllegalArgumentException | java.time.DateTimeException e) {
            System.err.println("Evaluation stopped: " + (e instanceof com.fasterxml.jackson.core.JsonProcessingException
                    ? "Invalid evaluation JSON" : e.getMessage()));
            code = 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Evaluation interrupted");
            code = 1;
        }
        if (code != 0) {
            System.exit(code);
        }
    }

    static int execute(String[] args, Path workspace, PrintStream out) throws IOException, InterruptedException {
        if (args.length == 0 || List.of("help", "--help", "-h").contains(args[0])) {
            out.print(USAGE);
            return args.length == 0 ? 1 : 0;
        }
        String command = args[0];
        var allowed = switch (command) {
            case "list" -> Set.<String>of();
            case "run" -> Set.of("mode", "stage", "split", "repetitions", "model", "ollama-url", "application-url",
                    "synthetic-database", "deployment-id", "timeout", "output",
                    "temperature", "num-ctx", "num-predict", "num-gpu");
            case "review" -> Set.of("output");
            case "compare" -> Set.of("output", "baseline-results", "candidate-results");
            default -> throw new IllegalArgumentException("Unknown command. Use --help for usage.");
        };
        var options = Options.parse(args, allowed, Set.of("review", "compare").contains(command) ? 2 : 0);
        switch (command) {
            case "list" -> {
                for (var scenario : EvaluationSuite.load().cases()) {
                    out.println(Json.text(scenario, "id") + " " + Json.text(scenario, "stage") + " "
                            + Json.text(scenario, "split") + " " + Json.text(scenario, "question"));
                }
                return 0;
            }
            case "run" -> {
                return RunCommand.execute(options, workspace, out);
            }
            case "review" -> {
                var capture = Capture.read(workspace.resolve(options.paths().get(0)));
                var annotations = PrivateFiles.read(workspace.resolve(options.paths().get(1)));
                var reviewed = HumanReview.apply(capture.rows(), annotations);
                PrivateFiles.saveRows(new PrivateFiles(workspace).destination(Path.of(options.required("output"))), reviewed);
                out.println(Json.encode(Reports.describe(reviewed)));
                return reviewed.stream().allMatch(row -> Status.PASS.name().equals(row.get("status").asText())) ? 0 : 2;
            }
            case "compare" -> {
                var base = workspace.resolve(options.paths().get(0));
                var next = workspace.resolve(options.paths().get(1));
                var baseline = Capture.read(base);
                var candidate = Capture.read(next);
                var result = Reports.compare(baseline.manifest(),
                        baseline.comparisonRows(base, options.get("baseline-results", Capture.RESULTS)),
                        candidate.manifest(),
                        candidate.comparisonRows(next, options.get("candidate-results", Capture.RESULTS)));
                PrivateFiles.save(new PrivateFiles(workspace).destination(Path.of(options.required("output"))), result);
                out.println(Json.encode(result));
                return Reports.cleanComparison(result) ? 0 : 2;
            }
            default -> throw new IllegalStateException("Unhandled command");
        }
    }

    record Options(Map<String, String> values, List<String> paths) {
        static Options parse(String[] args, Set<String> allowed, int pathCount) {
            var values = new HashMap<String, String>();
            var paths = new ArrayList<String>();
            for (int index = 1; index < args.length; index++) {
                String argument = args[index];
                if (!argument.startsWith("--")) {
                    paths.add(argument);
                    continue;
                }
                String key = argument.substring(2);
                if (!allowed.contains(key) || values.containsKey(key)) {
                    throw new IllegalArgumentException("Unknown or repeated option " + argument);
                }
                if (key.equals("synthetic-database")) {
                    values.put(key, "true");
                } else if (index + 1 < args.length && !args[index + 1].startsWith("--") && !args[index + 1].isBlank()) {
                    values.put(key, args[++index]);
                } else {
                    throw new IllegalArgumentException("Missing value for " + argument);
                }
            }
            if (paths.size() != pathCount) {
                throw new IllegalArgumentException("Unexpected number of positional arguments");
            }
            return new Options(Map.copyOf(values), List.copyOf(paths));
        }

        boolean has(String key) {
            return values.containsKey(key);
        }

        String get(String key, String fallback) {
            return values.getOrDefault(key, fallback);
        }

        String required(String key) {
            if (!has(key)) {
                throw new IllegalArgumentException("Missing --" + key);
            }
            return values.get(key);
        }

        String choice(String key, String fallback, String... choices) {
            String selected = get(key, fallback);
            if (!Set.of(choices).contains(selected)) {
                throw new IllegalArgumentException("Invalid --" + key);
            }
            return selected;
        }

        int integer(String key, int fallback, int min, int max) {
            int number = Integer.parseInt(get(key, Integer.toString(fallback)));
            if (number < min || number > max) {
                throw new IllegalArgumentException("Value out of range for --" + key);
            }
            return number;
        }

        BigDecimal decimal(String key, String fallback, BigDecimal min, BigDecimal max) {
            var value = new BigDecimal(get(key, fallback));
            if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                throw new IllegalArgumentException("Value out of range for --" + key);
            }
            return value;
        }
    }
}
