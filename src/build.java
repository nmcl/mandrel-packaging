import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class build
{
    static final Logger logger = LogManager.getLogger(build.class);

    public static void main(String... args)
    {
        final var version = requireOption("version");
        final var verbose = Boolean.getBoolean("verbose");
        final var mavenProxy = System.getProperty("maven.proxy");

        final var options = new Options(version, verbose, mavenProxy);

        logger.info("Build Mandrel");
        SequentialBuild.build(options);
    }

    private static String requireOption(String name)
    {
        final var option = System.getProperty(name);
        if (Objects.isNull(option))
            throw new IllegalArgumentException(String.format(
                "Missing mandatory -D%s system property"
                , name
            ));

        return option;
    }
}

class Options
{
    final String version;
    final boolean verbose;
    final String mavenProxy;

    Options(String version, boolean verbose, String mavenProxy)
    {
        this.version = version;
        this.verbose = verbose;
        this.mavenProxy = mavenProxy;
    }
}

class SequentialBuild
{
    static void build(Options options)
    {
        // TODO git checkout mx version associated with mandrel version
        Mx.hookMavenProxy(options);
        Mx.build("sdk", options);
        Maven.install("sdk", options);
        // Mx.build("substratevm", options);
        // Maven.install("substratevm", options);
    }
}

class EnvVars
{
    public static final OperatingSystem.EnvVar JAVA_HOME_ENV_VAR =
        new OperatingSystem.EnvVar(
            "JAVA_HOME"
            , "/opt/labsjdk"
        );
}

class Mx
{
    static void build(String artifactName, Options options)
    {
        OperatingSystem.exec()
            .compose(Mx.mxbuild(options))
            .compose(LocalPaths::to)
            .apply(artifactName);
    }

    private static Function<Path, OperatingSystem.Command> mxbuild(Options options)
    {
        return path ->
            new OperatingSystem.Command(
                Stream.of(
                    "mx"
                    , options.verbose ? "-V" : ""
                    , "build"
                    , "--no-native"
                )
                , path
                , Stream.of(
                    EnvVars.JAVA_HOME_ENV_VAR
                )
            );
    }

    static void hookMavenProxy(Options options)
    {
        if (options.mavenProxy == null)
            return;

        prependMavenProxyToMxPy(options)
            .apply(backupOrRestoreMxPy());
    }

    private static Function<Path, Void> prependMavenProxyToMxPy(Options options)
    {
        return mxPy ->
        {
            try (var lines = Files.lines(mxPy))
            {
                final var replaced = lines
                    .map(prependMavenProxy(options))
                    .collect(Collectors.toList());
                Files.write(mxPy, replaced);
                return null;
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        };
    }

    private static Path backupOrRestoreMxPy()
    {
        Path backupMxPy = Path.of("/opt", "mx", "mx.py.backup");
        Path mxPy = Path.of("/opt", "mx", "mx.py");
        if (!backupMxPy.toFile().exists())
        {
            copyIgnoreAccessDenied(backupMxPy, mxPy);
        }
        else
        {
            copy(backupMxPy, mxPy, StandardCopyOption.REPLACE_EXISTING);
        }

        return mxPy;
    }

    private static void copyIgnoreAccessDenied(Path backupMxPy, Path mxPy)
    {
        try
        {
            Files.copy(mxPy, backupMxPy);
        }
        catch (AccessDeniedException e)
        {
            // Ignore if unable to get access to copy
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static void copy(Path from, Path to, CopyOption... copyOptions)
    {
        try
        {
            Files.copy(from, to, copyOptions);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static Function<String, String> prependMavenProxy(Options options)
    {
        return line ->
        {
            var mavenBaseURL = String.format("\"%s/\"", options.mavenProxy);
            return line.contains("_mavenRepoBaseURLs")
                ? line.replaceFirst("\\[", String.format("[ %s,", mavenBaseURL))
                : line;
        };
    }

}

class LocalPaths
{
    static Path to(String artifact)
    {
        return Path.of("/tmp", "mandrel", artifact);
    }

    static String from(Path path)
    {
        return path.getFileName().toString();
    }
}

class Maven
{
    static final Map<String, String> GROUP_IDS = Map.of(
        "sdk", "io.mandrel.sdk"
        , "substratevm", "io.mandrel.nativeimage"
    );

    static final Map<String, String> ARTIFACT_IDS = Map.of(
        "sdk", "graal-sdk"
        , "substratevm", "svm"
    );

    static void install(String artifactName, Options options)
    {
        OperatingSystem.exec()
            .compose(Maven.mvnInstall(options))
            .compose(LocalPaths::to)
            .apply(artifactName);
    }
    
    private static Function<Path, OperatingSystem.Command> mvnInstall(Options options)
    {
        return path ->
        {
            final var artifactName = LocalPaths.from(path);
            final var groupId = GROUP_IDS.get(artifactName);
            final var artifactId = ARTIFACT_IDS.get(artifactName);

            return new OperatingSystem.Command(
                Stream.of(
                    "mvn"
                    , options.verbose ? "--debug" : ""
                    , "install:install-file"
                    , String.format("-DgroupId=%s", groupId)
                    , String.format("-DartifactId=%s", artifactId)
                    , String.format("-Dversion=%s", options.version)
                    , "-Dpackaging=jar"
                    , String.format(
                        "-Dfile=%s/mxbuild/dists/jdk11/%s.jar"
                        , path.toString()
                        , artifactId
                    )
                    , "-DcreateChecksum=true"
                )
                , path
                , Stream.of(
                    EnvVars.JAVA_HOME_ENV_VAR
                )
            );
        };
    }
}

class OperatingSystem
{
    static final Logger logger = LogManager.getLogger(OperatingSystem.class);

    static Function<OperatingSystem.Command, Void> exec()
    {
        return OperatingSystem::exec;
    }

    private static Void exec(Command command)
    {
        final var commandList = command.command
            .filter(Predicate.not(String::isEmpty))
            .collect(Collectors.toList());

        logger.debugf("Execute %s in %s", commandList, command.directory);
        try
        {
            var processBuilder = new ProcessBuilder(commandList)
                .directory(command.directory.toFile())
                .inheritIO();

            command.envVars.forEach(
                envVar -> processBuilder.environment()
                    .put(envVar.name, envVar.value)
            );

            Process process = processBuilder.start();

            if (process.waitFor() != 0)
            {
                throw new RuntimeException(
                    "Failed, exit code: " + process.exitValue()
                );
            }

            return null;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    static class Command
    {
        final Stream<String> command;
        final Path directory;
        final Stream<EnvVar> envVars;

        Command(Stream<String> command, Path directory, Stream<EnvVar> envVars)
        {
            this.command = command;
            this.directory = directory;
            this.envVars = envVars;
        }
    }

    static class EnvVar
    {
        final String name;
        final String value;

        EnvVar(String name, String value)
        {
            this.name = name;
            this.value = value;
        }
    }
}

final class Logger
{
    final String name;

    Logger(String name)
    {
        this.name = name;
    }

    void debugf(String format, Object... params)
    {
        System.out.printf("DEBUG [%s] %s%n"
            , name
            , String.format(format, params)
        );
    }

    public void info(String msg)
    {
        System.out.printf("INFO [%s] %s%n"
            , name
            , msg
        );
    }
}

final class LogManager
{
    static Logger getLogger(Class<?> clazz)
    {
        return new Logger(clazz.getSimpleName());
    }
}
