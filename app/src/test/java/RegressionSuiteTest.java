import org.junit.Test;

/**
 * JUnit entry for the plain-Java regression harnesses under tests/ (compiled into this source set
 * by app/build.gradle.kts). Each harness throws AssertionError on failure, which fails the test.
 * They stay runnable on their own through tests/audio/run.ps1 and tests/hooks/run.ps1.
 */
public class RegressionSuiteTest {
    private static final String[] NO_ARGS = new String[0];

    @Test public void audio() { AudioRegression.main(NO_ARGS); }

    @Test public void tapTiming() { TapTimingRegression.main(NO_ARGS); }

    @Test public void folderIndex() { FolderIndexRegression.main(NO_ARGS); }

    @Test public void hookDiagnostics() { HookDiagnosticsRegression.main(NO_ARGS); }
}
