package eu.exeris.caps.ratelimit.internal;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("production code does not implement its own buffers")
class NoHandWrittenBufferTest {

    private static Path classesRoot() throws URISyntaxException {
        return Path.of(ProblemResponses.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    @Test
    @DisplayName("no compiled production class is a LoanedBuffer or extends AbstractLoanedBuffer")
    void noLoanedBufferImplementation() throws Exception {
        Path root = classesRoot();
        assertThat(root).isDirectory();
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = root.relativize(file).toString().replace('/', '.').replaceAll("\\.class$", "");
                if (name.equals("module-info") || name.endsWith("package-info")) {
                    continue;
                }
                scanned++;
                Class<?> type = Class.forName(name, false, ProblemResponses.class.getClassLoader());
                for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                    if (LoanedBuffer.class.isAssignableFrom(c) && c != LoanedBuffer.class
                            || c.getSimpleName().equals("AbstractLoanedBuffer")) {
                        offenders.add(name);
                        break;
                    }
                }
            }
        }
        assertThat(scanned).isGreaterThan(5);
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("no production source implements LoanedBuffer, extends AbstractLoanedBuffer or names a heap buffer")
    void noSourceMentions() throws IOException, URISyntaxException {
        Path classes = classesRoot();
        Path sources = classes.getParent().getParent().resolve("src/main/java");
        assertThat(sources).isDirectory();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                if (text.matches("(?s).*(implements|extends)[^{;]*\\b(AbstractLoanedBuffer|LoanedBuffer)\\b.*")
                        || text.contains("HeapLoanedBuffer")) {
                    offenders.add(sources.relativize(file).toString());
                }
            }
        }
        assertThat(offenders).isEmpty();
    }
}
