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
package com.github.stephengold.joltjni;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads the Behemiron fork's Jolt-JNI native library from a platform natives
 * jar on the classpath. Replaces {@code NativeLibraryLoader} for engine use:
 * versioned extraction, cache-dir hygiene, dedicated-server safe (no LWJGL),
 * idempotent, and classloader-aware for modded environments such as NeoForge
 * and FCL/Pojav on Android.
 *
 * <p>The native library is looked up as a classpath resource using the same
 * layout the native jars are packaged in:
 * {@code <dir>/com/github/stephengold/<libraryName>}.</p>
 */
final public class BehemironLoader {
    // *************************************************************************
    // constants

    /**
     * resource prefix shared by all platform jars
     */
    final private static String RESOURCE_BASE = "com/github/stephengold/";
    /**
     * environment variable overriding the extraction cache root
     */
    final private static String CACHE_ENV = "BEHEMIRON_JOLT_CACHE";
    /**
     * serialization guard for {@link #load()}
     */
    final private static Object LOAD_LOCK = new Object();
    // *************************************************************************
    // fields

    /**
     * set once the native library has been loaded successfully
     */
    private static volatile boolean loaded;
    // *************************************************************************
    // constructors

    /**
     * A private constructor to inhibit instantiation of this class.
     */
    private BehemironLoader() {
    }
    // *************************************************************************
    // new methods exposed

    /**
     * Load the native library for the current platform if not already loaded.
     * Idempotent and thread-safe.
     *
     * @throws UnsatisfiedLinkError if no matching native is found on the
     * classpath or extraction/loading fails
     */
    public static void load() {
        if (loaded) {
            return;
        }
        synchronized (LOAD_LOCK) {
            if (loaded) {
                return;
            }
            final String osName = System.getProperty("os.name", "");
            final String osArch = System.getProperty("os.arch", "");
            final String runtimeName
                    = System.getProperty("java.runtime.name", "");
            final String vmVendor
                    = System.getProperty("java.vm.vendor", "");

            final List<String[]> candidates
                    = probeCandidates(osName, osArch, runtimeName, vmVendor);
            if (candidates.isEmpty()) {
                throw new UnsatisfiedLinkError(
                        "behemiron-jolt-jni: unsupported platform " + osName
                        + " / " + osArch);
            }

            final ClassLoader ownLoader
                    = BehemironLoader.class.getClassLoader();
            ClassLoader loader = ownLoader;
            if (loader == null) { // bootstrap classloader: defer to the context
                loader = Thread.currentThread().getContextClassLoader();
            }

            final String version = libraryVersion();
            IOException deferred = null;
            for (String[] candidate : candidates) {
                final String dir = candidate[0];
                final String libName = candidate[1];
                final String resource = dir + "/" + RESOURCE_BASE + libName;
                final URL url = loader.getResource(resource);
                if (url == null) {
                    continue;
                }
                try {
                    final Path target = extract(url, dir, libName, version);
                    System.load(target.toAbsolutePath().toString());
                    loaded = true;
                    return;
                } catch (IOException exception) {
                    deferred = exception;
                }
            }

            final String message = "behemiron-jolt-jni: no native extracted"
                    + " (candidates=" + candidates.size() + ")";
            final UnsatisfiedLinkError error
                    = new UnsatisfiedLinkError(message);
            if (deferred != null) {
                error.initCause(deferred);
            }
            throw error;
        }
    }

    /**
     * Test whether the native library has been loaded successfully.
     *
     * @return true if loaded, otherwise false
     */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * Enumerate the candidate native resources for a platform, most preferred
     * first. Exposed for testing and for custom embedding loaders.
     *
     * @param osName the operating-system name ({@code os.name}, not null)
     * @param osArch the operating-system architecture ({@code os.arch}, not
     * null)
     * @param runtimeName the runtime name ({@code java.runtime.name}, not null)
     * @param vmVendor the VM vendor ({@code java.vm.vendor}, not null)
     * @return a list of {@code {resourceDir, libraryName}} pairs (not null)
     */
    public static List<String[]> probeCandidates(
            String osName, String osArch, String runtimeName, String vmVendor) {
        final String os = osName.toLowerCase(Locale.ROOT);
        final String arch = osArch.toLowerCase(Locale.ROOT);
        final boolean isAndroid = runtimeName.toLowerCase(Locale.ROOT)
                .contains("android")
                || vmVendor.toLowerCase(Locale.ROOT).contains("android");

        final List<String[]> result = new ArrayList<>(2);
        if (isAndroid) {
            // FCL/Pojav and native Android launchers: one dir per ABI, as
            // repackaged from the multi-ABI AAR by the maven-repo workflow.
            switch (arch) {
                case "aarch64":
                case "arm64":
                    result.add(new String[] {
                        "android/arm64-v8a", "libjoltjni.so"});
                    break;
                case "arm":
                case "armv7l":
                    result.add(new String[] {
                        "android/armeabi-v7a", "libjoltjni.so"});
                    break;
                case "x86_64":
                case "amd64":
                    result.add(new String[] {
                        "android/x86_64", "libjoltjni.so"});
                    break;
                default:
                    break;
            }
            return result;
        }

        if (os.startsWith("windows")) {
            switch (arch) {
                case "amd64":
                case "x86_64":
                    // TODO: detect AVX2/FMA at runtime to prefer
                    // the -avx2/-fma jars
                    result.add(new String[] {
                        "windows/x86-64", "joltjni.dll"});
                    break;
                case "aarch64":
                case "arm64":
                    result.add(new String[] {
                        "windows/aarch64", "libjoltjni.dll"});
                    break;
                default:
                    break;
            }
        } else if (os.startsWith("linux")) {
            switch (arch) {
                case "amd64":
                case "x86_64":
                    result.add(new String[] {
                        "linux/x86-64", "libjoltjni.so"});
                    break;
                case "aarch64":
                case "arm64":
                    result.add(new String[] {
                        "linux/aarch64", "libjoltjni.so"});
                    break;
                default:
                    break;
            }
        } else if (os.startsWith("mac") || os.startsWith("darwin")) {
            switch (arch) {
                case "amd64":
                case "x86_64":
                    result.add(new String[] {
                        "osx/x86-64", "libjoltjni.dylib"});
                    break;
                case "aarch64":
                case "arm64":
                    result.add(new String[] {
                        "osx/aarch64", "libjoltjni.dylib"});
                    result.add(new String[] {
                        "osx/arm-v8", "libjoltjni.dylib"});
                    break;
                default:
                    break;
            }
        }
        return result;
    }
    // *************************************************************************
    // private methods

    /**
     * Extract a native-library resource to the cache and return its path.
     *
     * @param url the resource location (not null)
     * @param dir the platform directory inside the jar (not null)
     * @param libName the library file name (not null)
     * @param version the library version string (not null)
     * @return the absolute path of the extracted file (not null)
     * @throws IOException if extraction fails
     */
    private static Path extract(
            URL url, String dir, String libName, String version)
            throws IOException {
        final Path root = cacheRoot();
        final Path versionDir = root.resolve(version);
        final String safeDir = dir.replace('/', '-');
        final Path target = versionDir.resolve(safeDir + "-" + libName);
        Files.createDirectories(versionDir);

        if (Files.exists(target)) { // reuse the cached copy
            return target;
        }

        final Path temp = Files.createTempFile(versionDir, ".extract", null);
        try (InputStream stream = url.openStream()) {
            Files.copy(stream, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /**
     * Determine the cache root directory, honoring the
     * {@value #CACHE_ENV} override.
     *
     * @return the cache root (not null)
     */
    private static Path cacheRoot() {
        final String override = System.getenv(CACHE_ENV);
        if (override != null && !override.isEmpty()) {
            return Paths.get(override);
        }
        final String osName = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT);
        if (osName.startsWith("windows")) {
            final String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isEmpty()) {
                return Paths.get(localAppData, "BehemironJolt", "cache");
            }
            return Paths.get(System.getProperty("user.home"),
                    "AppData", "Local", "BehemironJolt", "cache");
        }
        if (osName.startsWith("mac")) {
            return Paths.get(System.getProperty("user.home"),
                    "Library", "Caches", "behemiron-jolt");
        }
        final String xdg = System.getenv("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isEmpty()) {
            return Paths.get(xdg, "behemiron-jolt");
        }
        return Paths.get(System.getProperty("user.home"),
                ".cache", "behemiron-jolt");
    }

    /**
     * Determine the library version for cache namespacing.
     *
     * @return the implementation version, or "dev" if unknown (not null)
     */
    private static String libraryVersion() {
        final Package pkg = BehemironLoader.class.getPackage();
        final String version
                = (pkg == null) ? null : pkg.getImplementationVersion();
        return (version == null || version.isEmpty()) ? "dev" : version;
    }
}
