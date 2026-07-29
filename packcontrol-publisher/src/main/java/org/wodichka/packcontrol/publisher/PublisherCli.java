package org.wodichka.packcontrol.publisher;

import org.wodichka.packcontrol.updateformat.CancellationToken;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PublisherCli {
    private PublisherCli() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0])) {
            usage(out);
            return args.length == 0 ? 2 : 0;
        }
        try {
            String command = args[0];
            Map<String, String> options = parseOptions(args);
            return switch (command) {
                case "validate" -> validate(options, out);
                case "inspect" -> inspect(options, out);
                case "build" -> build(options, out);
                default -> {
                    err.println("Unknown command: " + command);
                    usage(err);
                    yield 2;
                }
            };
        } catch (Exception exception) {
            err.println("ERROR: " + exception.getMessage());
            return 1;
        }
    }

    private static int validate(Map<String, String> options, PrintStream out) {
        Path input = requiredPath(options, "--input");
        List<String> errors = new PublisherOutputValidator().validate(input);
        if (!errors.isEmpty()) {
            errors.forEach(error -> out.println("ERROR " + error));
            return 1;
        }
        out.println("Valid PackControl publication: " + input.toAbsolutePath().normalize());
        return 0;
    }

    private static int inspect(Map<String, String> options, PrintStream out) throws Exception {
        Path instance = requiredPath(options, "--instance");
        PublisherConfig config = PublisherConfig.read(configPath(options, instance));
        PackControlPublisher.Inspection inspection = new PackControlPublisher()
                .inspect(instance, config, CancellationToken.none());
        out.printf(
                "mods=%d resolved=%d overrides=%d%n",
                inspection.scan().mods().size(),
                inspection.mods().size(),
                inspection.scan().overrides().size()
        );
        inspection.mods().forEach(mod -> out.printf(
                "%s %s %s%n",
                mod.required() ? "required" : "optional",
                mod.file().path(),
                mod.downloads().getFirst()
        ));
        inspection.warnings().forEach(warning -> out.println("WARNING " + warning));
        inspection.errors().forEach(error -> out.println("ERROR " + error));
        return inspection.errors().isEmpty() ? 0 : 1;
    }

    private static int build(Map<String, String> options, PrintStream out) throws Exception {
        Path instance = requiredPath(options, "--instance");
        Path output = requiredPath(options, "--output");
        PublisherConfig config = PublisherConfig.read(configPath(options, instance));
        PackControlPublisher.BuildResult result = new PackControlPublisher()
                .build(instance, output, config, CancellationToken.none());
        result.warnings().forEach(warning -> out.println("WARNING " + warning));
        out.println("manifest: " + result.manifest());
        out.println("overrides: " + result.overrides());
        out.println("mrpack: " + result.mrpack());
        out.println("checksums: " + result.checksums());
        return 0;
    }

    private static Path configPath(Map<String, String> options, Path instance) {
        String configured = options.get("--config");
        return configured == null ? instance.resolve(PackControlPublisher.CONFIG_FILE) : Path.of(configured);
    }

    private static Path requiredPath(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required option " + name);
        }
        return Path.of(value);
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 1; index < args.length; index += 2) {
            if (!args[index].startsWith("--") || index + 1 >= args.length) {
                throw new IllegalArgumentException("Options must be provided as --name value pairs");
            }
            if (options.put(args[index], args[index + 1]) != null) {
                throw new IllegalArgumentException("Duplicate option " + args[index]);
            }
        }
        return options;
    }

    private static void usage(PrintStream output) {
        output.println("""
                PackControl Publisher
                  validate --input <publication-directory>
                  inspect  --instance <instance> [--config <publisher.json>]
                  build    --instance <instance> --output <directory> [--config <publisher.json>]

                Gradle example:
                  gradlew :packcontrol-publisher:run --args="build --instance pack --output dist"
                """);
    }
}
