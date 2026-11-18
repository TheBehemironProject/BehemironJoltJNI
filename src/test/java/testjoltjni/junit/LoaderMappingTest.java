/*
Copyright (c) 2026 TheBehemironProject

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */
package testjoltjni.junit;

import com.github.stephengold.joltjni.BehemironLoader;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * Automated JUnit4 tests for {@code BehemironLoader} platform probing. Pure
 * JVM: no native library is loaded.
 */
public class LoaderMappingTest {
    // *************************************************************************
    // new methods exposed

    /**
     * Verify desktop platform mappings, including the macOS arm64 fallback
     * chain.
     */
    @Test
    public void testDesktopMappings() {
        assertSingle("Windows 11", "amd64", "Java(TM) SE Runtime Environment",
                "Oracle Corporation", "windows/x86-64", "joltjni.dll");

        assertSingle("Windows 11", "aarch64", "Java(TM) SE Runtime Environment",
                "Oracle Corporation", "windows/aarch64", "libjoltjni.dll");

        assertSingle("Linux", "amd64", "OpenJDK Runtime Environment",
                "Eclipse Adoptium", "linux/x86-64", "libjoltjni.so");

        assertSingle("Linux", "aarch64", "OpenJDK Runtime Environment",
                "Eclipse Adoptium", "linux/aarch64", "libjoltjni.so");

        assertSingle("Mac OS X", "x86_64", "OpenJDK Runtime Environment",
                "Eclipse Adoptium", "osx/x86-64", "libjoltjni.dylib");

        final List<String[]> macArm = BehemironLoader.probeCandidates(
                "Mac OS X", "aarch64", "OpenJDK Runtime Environment",
                "Eclipse Adoptium");
        Assert.assertEquals(2, macArm.size());
        Assert.assertEquals("osx/aarch64", macArm.get(0)[0]);
        Assert.assertEquals("osx/arm-v8", macArm.get(1)[0]);
    }

    /**
     * Verify that the Android runtime is detected before the OS mapping and
     * produces ABI-specific candidates.
     */
    @Test
    public void testAndroidMappings() {
        final List<String[]> androidArm = BehemironLoader.probeCandidates(
                "Linux", "aarch64", "Android Runtime", "The Android Project");
        Assert.assertEquals(1, androidArm.size());
        Assert.assertEquals("android/arm64-v8a", androidArm.get(0)[0]);
        Assert.assertEquals("libjoltjni.so", androidArm.get(0)[1]);

        final List<String[]> androidX86 = BehemironLoader.probeCandidates(
                "Linux", "x86_64", "Android Runtime", "The Android Project");
        Assert.assertEquals(1, androidX86.size());
        Assert.assertEquals("android/x86_64", androidX86.get(0)[0]);
    }

    /**
     * Verify that unsupported platforms yield an empty candidate list.
     */
    @Test
    public void testUnsupportedPlatform() {
        final List<String[]> unknown = BehemironLoader.probeCandidates(
                "Solaris", "sparc", "OpenJDK Runtime Environment",
                "Eclipse Adoptium");
        Assert.assertTrue(unknown.isEmpty());
    }
    // *************************************************************************
    // private methods

    /**
     * Assert that probing yields exactly one expected candidate.
     *
     * @param osName the OS name to probe
     * @param osArch the OS architecture to probe
     * @param runtimeName the runtime name to probe
     * @param vmVendor the VM vendor to probe
     * @param expectedDir the expected resource dir
     * @param expectedLib the expected library name
     */
    private static void assertSingle(String osName, String osArch,
            String runtimeName, String vmVendor, String expectedDir,
            String expectedLib) {
        final List<String[]> candidates = BehemironLoader.probeCandidates(
                osName, osArch, runtimeName, vmVendor);
        Assert.assertEquals(1, candidates.size());
        Assert.assertEquals(expectedDir, candidates.get(0)[0]);
        Assert.assertEquals(expectedLib, candidates.get(0)[1]);
    }
}
