package ai.openpush.android.liveupdates;

import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import org.junit.Test;

public class SourceTextTests {
    @Test
    public void liveUpdateSourcesContainNoNulBytes() throws IOException {
        Path working = Paths.get(System.getProperty("user.dir"));
        Path source = Files.isDirectory(working.resolve("src/main"))
                ? working.resolve("src/main")
                : working.resolve("openpush-live-updates/src/main");
        try (Stream<Path> files = Files.walk(source)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                try {
                    for (byte value : Files.readAllBytes(path)) {
                        if (value == 0) {
                            fail("NUL byte in source file: " + path);
                        }
                    }
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            });
        }
    }
}
