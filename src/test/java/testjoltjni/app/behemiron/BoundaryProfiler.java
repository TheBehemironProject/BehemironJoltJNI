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
package testjoltjni.app.behemiron;

import com.github.stephengold.joltjni.BatchBodyInterface;
import com.github.stephengold.joltjni.BehemironLoader;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyIdArray;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable;
import com.github.stephengold.joltjni.Jolt;
import com.github.stephengold.joltjni.JobSystemSingleThreaded;
import com.github.stephengold.joltjni.ObjVsBpFilter;
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable;
import com.github.stephengold.joltjni.PhysicsSystem;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RMat44Array;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.TempAllocatorImpl;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;

/**
 * JNI boundary profiler: per-call vs batched body transform I/O at Minecraft
 * scale (2000 bodies), the workload evidence for the Behemiron batch-API
 * design. Reports ns per body-op and the implied cost per 20-TPS server tick.
 *
 * Run: java -cp <classes>;<natives> testjoltjni.app.behemiron.BoundaryProfiler
 */
public class BoundaryProfiler {
    // *************************************************************************
    // constants

    private static final int NUM_BODIES = 2000;
    private static final int WARMUP_ROUNDS = 3;
    private static final int MEASURE_ROUNDS = 20;
    // *************************************************************************
    // fields

    private static PhysicsSystem physicsSystem;
    private static BatchBodyInterface bodyInterface;
    private static TempAllocatorImpl tempAllocator;
    private static JobSystemSingleThreaded jobSystem;
    private static BodyIdArray ids;
    // *************************************************************************
    // new methods exposed

    public static void main(String[] args) {
        if (args.length > 0) {
            System.load(args[0]);
        } else {
            BehemironLoader.load();
        }
        Jolt.registerDefaultAllocator();
        Jolt.installCrashAssertCallback();
        Jolt.newFactory();
        Jolt.registerTypes();
        Jolt.registerHair();

        createWorld();

        final DoubleBuffer posBuf
                = Jolt.newDirectDoubleBuffer(NUM_BODIES * 3);
        final FloatBuffer rotBuf
                = Jolt.newDirectFloatBuffer(NUM_BODIES * 4);
        final RMat44Array matArray = new RMat44Array(NUM_BODIES);
        final RVec3 storePos = new RVec3(0.0, 0.0, 0.0);
        final Quat storeRot = new Quat(0f, 0f, 0f, 1f);

        verifyCombinedApi();

        report("read  per-body alloc ",
                time(() -> {
                    double sink = 0.0;
                    for (int i = 0; i < NUM_BODIES; ++i) {
                        final int id = ids.get(i);
                        final RVec3 p = bodyInterface.getPosition(id);
                        final Quat q = bodyInterface.getRotation(id);
                        sink += p.xx() + q.getW();
                    }
                    return sink;
                }));
        report("read  per-body store ",
                time(() -> {
                    double sink = 0.0;
                    for (int i = 0; i < NUM_BODIES; ++i) {
                        final int id = ids.get(i);
                        bodyInterface.getPosition(id, storePos);
                        bodyInterface.getRotation(id, storeRot);
                        sink += storePos.xx() + storeRot.getW();
                    }
                    return sink;
                }));
        report("read  batch buffers",
                time(() -> {
                    bodyInterface.getPositions(ids, posBuf);
                    bodyInterface.getRotations(ids, rotBuf);
                    return posBuf.get(0) + rotBuf.get(3);
                }));
        report("read  batch pos+rot  ",
                time(() -> {
                    bodyInterface.getPositionsAndRotations(ids, posBuf, rotBuf);
                    return posBuf.get(0) + rotBuf.get(3);
                }));
        report("read  batch matrices",
                time(() -> {
                    bodyInterface.getCenterOfMassTransforms(ids, matArray);
                    return matArray.get(0).getTranslation().xx();
                }));
        report("write per-body single",
                time(() -> {
                    for (int i = 0; i < NUM_BODIES; ++i) {
                        bodyInterface.setPositionAndRotation(ids.get(i),
                                storePos, storeRot, EActivation.DontActivate);
                    }
                    return storePos.xx();
                }));
        report("write batch positions",
                time(() -> {
                    bodyInterface.setPositions(ids, posBuf,
                            EActivation.DontActivate);
                    return posBuf.get(0);
                }));
        report("write batch pos+rot  ",
                time(() -> {
                    bodyInterface.setPositionsAndRotations(ids, posBuf, rotBuf,
                            EActivation.DontActivate);
                    return posBuf.get(0);
                }));
        report("step  kinematic world",
                time(() -> {
                    physicsSystem.update(
                            1f / 60f, 1, tempAllocator, jobSystem);
                    return 0.0;
                }));

        System.exit(0);
    }
    // *************************************************************************
    // private methods

    private static void report(String label, double nsPerRound) {
        final double nsPerBody = nsPerRound / NUM_BODIES;
        final double msPerTick = nsPerRound / 1.0e6;
        final double pctOfTick = 100.0 * msPerTick / 50.0;
        System.out.printf(
                "PROFILE %s : %8.0f ns/round %7.1f ns/body %6.2f ms/tick %5.1f%% of 50ms%n",
                label, nsPerRound, nsPerBody, msPerTick, pctOfTick);
    }

    // Correctness of the combined batch APIs against the per-body equivalents:
    // exact equality expected (same getters underneath).
    private static void verifyCombinedApi() {
        final DoubleBuffer posBuf
                = Jolt.newDirectDoubleBuffer(NUM_BODIES * 3);
        final FloatBuffer rotBuf
                = Jolt.newDirectFloatBuffer(NUM_BODIES * 4);

        // write via the combined setter, read back per body
        for (int i = 0; i < NUM_BODIES; ++i) {
            posBuf.put(i * 3, 100.0 + i * 0.5);
            posBuf.put(i * 3 + 1, 10.0);
            posBuf.put(i * 3 + 2, -i * 0.25);
            rotBuf.put(i * 4, 0f);
            rotBuf.put(i * 4 + 1, 0f);
            rotBuf.put(i * 4 + 2, 0f);
            rotBuf.put(i * 4 + 3, 1f);
        }
        bodyInterface.setPositionsAndRotations(ids, posBuf, rotBuf,
                EActivation.DontActivate);

        final RVec3 storePos = new RVec3(0.0, 0.0, 0.0);
        final Quat storeRot = new Quat(0f, 0f, 0f, 1f);
        for (int i = 0; i < NUM_BODIES; ++i) {
            bodyInterface.getPosition(ids.get(i), storePos);
            bodyInterface.getRotation(ids.get(i), storeRot);
            if (storePos.xx() != 100.0 + i * 0.5 || storePos.yy() != 10.0
                    || storePos.zz() != -i * 0.25
                    || storeRot.getW() != 1f) {
                throw new AssertionError("combined setter mismatch at " + i);
            }
        }

        // read via the combined getter, compare against per-body
        bodyInterface.getPositionsAndRotations(ids, posBuf, rotBuf);
        for (int i = 0; i < NUM_BODIES; ++i) {
            bodyInterface.getPosition(ids.get(i), storePos);
            bodyInterface.getRotation(ids.get(i), storeRot);
            if (posBuf.get(i * 3) != storePos.xx()
                    || posBuf.get(i * 3 + 1) != storePos.yy()
                    || posBuf.get(i * 3 + 2) != storePos.zz()
                    || rotBuf.get(i * 4 + 3) != storeRot.getW()) {
                throw new AssertionError("combined getter mismatch at " + i);
            }
        }
        System.out.println("PROFILE check combined APIs : PASS (" + NUM_BODIES + " bodies)");
    }

    private static double time(Sink sink) {
        for (int r = 0; r < WARMUP_ROUNDS; ++r) {
            sink.run();
        }
        long best = Long.MAX_VALUE;
        for (int r = 0; r < MEASURE_ROUNDS; ++r) {
            final long t0 = System.nanoTime();
            sink.run();
            final long elapsed = System.nanoTime() - t0;
            if (elapsed < best) {
                best = elapsed;
            }
        }
        return best;
    }

    private static void createWorld() {
        final ObjectLayerPairFilterTable ovoFilter
                = new ObjectLayerPairFilterTable(2);
        ovoFilter.enableCollision(1, 1);
        ovoFilter.enableCollision(0, 1);

        final BroadPhaseLayerInterfaceTable map
                = new BroadPhaseLayerInterfaceTable(2, 1);
        map.mapObjectToBroadPhaseLayer(0, 0);
        map.mapObjectToBroadPhaseLayer(1, 0);

        final ObjVsBpFilter ovbFilter = new ObjVsBpFilter(2, 1);
        ovbFilter.disablePair(0, 0);

        physicsSystem = new PhysicsSystem();
        physicsSystem.init(NUM_BODIES + 64, 0, 65535, 65535,
                map, ovbFilter, ovoFilter);
        physicsSystem.setGravity(0f, -9.81f, 0f);

        tempAllocator = new TempAllocatorImpl(16 * 1024 * 1024);
        jobSystem = new JobSystemSingleThreaded(2048);

        bodyInterface = physicsSystem.getBodyInterface();
        final BoxShape shape = new BoxShape(0.5, 0.5, 0.5);
        final Quat rot = new Quat(0f, 0f, 0f, 1f);
        ids = new BodyIdArray(NUM_BODIES);
        for (int i = 0; i < NUM_BODIES; ++i) {
            final double x = (i % 50) * 1.5 - 37.5;
            final double z = ((i / 50) % 40) * 1.5 - 30.0;
            final RVec3 pos = new RVec3(x, 5.0 + (i / 2000), z);
            final BodyCreationSettings settings = new BodyCreationSettings(
                    shape, pos, rot, EMotionType.Kinematic, 1);
            final int id = bodyInterface.createAndAddBody(
                    settings, EActivation.DontActivate);
            ids.set(i, id);
        }
    }
    // *************************************************************************
    // private interfaces

    private interface Sink {
        double run();
    }
}
