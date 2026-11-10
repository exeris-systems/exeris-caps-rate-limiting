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

    /** True when the type is a LoanedBuffer implementation or a subtype of one, whatever its name. */
    static boolean isLoanedBuffer(Class<?> type) {
        return type != LoanedBuffer.class && LoanedBuffer.class.isAssignableFrom(type);
    }

    /** True when an implements or extends clause in the source names a loaned-buffer type. */
    static boolean declaresBufferSupertype(String text) {
        for (String keyword : List.of("implements", "extends")) {
            for (int at = text.indexOf(keyword); at >= 0; at = text.indexOf(keyword, at + keyword.length())) {
                int start = at + keyword.length();
                int end = text.length();
                for (char stop : new char[] {'{', ';'}) {
                    int found = text.indexOf(stop, start);
                    if (found >= 0 && found < end) {
                        end = found;
                    }
                }
                for (String token : text.substring(start, end).split("[^A-Za-z0-9_$]+")) {
                    if (token.equals("LoanedBuffer") || token.equals("AbstractLoanedBuffer")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Test
    @DisplayName("the class detector flags a test-tree LoanedBuffer implementation and passes an ordinary class")
    void classDetectorCanFail() {
        assertThat(isLoanedBuffer(TrackingAllocator.CountingBuffer.class)).isTrue();
        assertThat(isLoanedBuffer(ProblemResponses.class)).isFalse();
        assertThat(isLoanedBuffer(LoanedBuffer.class)).isFalse();
    }

    @Test
    @DisplayName("the source detector flags implements and extends clauses naming a buffer type and passes others")
    void sourceDetectorCanFail() {
        assertThat(declaresBufferSupertype("final class A implements Runnable, LoanedBuffer { }")).isTrue();
        assertThat(declaresBufferSupertype("class B extends AbstractLoanedBuffer {\n}")).isTrue();
        assertThat(declaresBufferSupertype("class C implements Runnable { LoanedBuffer field; }")).isFalse();
        assertThat(declaresBufferSupertype("class D { void m(LoanedBuffer b) { } }")).isFalse();
    }

    @Test
    @DisplayName("no compiled production class is a LoanedBuffer or a subtype of one")
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
                if (isLoanedBuffer(type)) {
                    offenders.add(name);
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
                if (declaresBufferSupertype(text) || text.contains("HeapLoanedBuffer")) {
                    offenders.add(sources.relativize(file).toString());
                }
            }
        }
        assertThat(offenders).isEmpty();
    }
}
