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

import com.github.stephengold.joltjni.BatchBodyInterface;
import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyIdArray;
import com.github.stephengold.joltjni.Jolt;
import com.github.stephengold.joltjni.PhysicsSystem;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.SphereShape;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import org.junit.Assert;
import org.junit.Test;
import testjoltjni.TestUtils;

/**
 * Automated JUnit4 tests for the fork's combined batch transform APIs
 * ({@code getPositionsAndRotations} / {@code setPositionsAndRotations}).
 *
 * @author TheBehemironProject
 */
public class BehemironBatchApiTest {
    // *************************************************************************
    // new methods exposed

    /**
     * Round-trip a batch of 8 bodies through both combined APIs and compare
     * against the per-body equivalents.
     */
    @Test
    public void testCombinedBatchTransforms() {
        TestUtils.loadNativeLibrary();
        TestUtils.initializeNativeLibrary();

        final int numBodies = 8;
        final PhysicsSystem physicsSystem
                = TestUtils.newPhysicsSystem(numBodies);
        final BatchBodyInterface bodyInterface
                = physicsSystem.getBodyInterface();
        final BodyIdArray ids = new BodyIdArray(numBodies);

        for (int i = 0; i < numBodies; ++i) {
            final SphereShape shape = new SphereShape(0.5f + 0.1f * i);
            final RVec3 pos = new RVec3(i * 3.0, 1.0, -i * 2.0);
            final Quat rot = new Quat(0f, 0f, 0f, 1f);
            final BodyCreationSettings settings = new BodyCreationSettings(
                    shape, pos, rot, EMotionType.Kinematic,
                    TestUtils.objLayerMoving);
            final int bodyId = bodyInterface.createAndAddBody(
                    settings, EActivation.DontActivate);
            ids.set(i, bodyId);
            TestUtils.testClose(shape, settings);
        }

        final DoubleBuffer posBuf
                = Jolt.newDirectDoubleBuffer(numBodies * 3);
        final FloatBuffer rotBuf
                = Jolt.newDirectFloatBuffer(numBodies * 4);
        for (int i = 0; i < numBodies; ++i) {
            posBuf.put(i * 3, 10.0 + i);
            posBuf.put(i * 3 + 1, 2.0 * i);
            posBuf.put(i * 3 + 2, -0.5 * i);
            final float angle = 0.1f * i;
            final float half = 0.5f * angle;
            rotBuf.put(i * 4, 0f);
            rotBuf.put(i * 4 + 1, (float) Math.sin(half));
            rotBuf.put(i * 4 + 2, 0f);
            rotBuf.put(i * 4 + 3, (float) Math.cos(half));
        }
        bodyInterface.setPositionsAndRotations(
                ids, posBuf, rotBuf, EActivation.DontActivate);

        // per-body readback must equal what the combined setter wrote
        final RVec3 storePos = new RVec3(0.0, 0.0, 0.0);
        final Quat storeRot = new Quat(0f, 0f, 0f, 1f);
        for (int i = 0; i < numBodies; ++i) {
            bodyInterface.getPosition(ids.get(i), storePos);
            bodyInterface.getRotation(ids.get(i), storeRot);
            Assert.assertEquals(posBuf.get(i * 3), storePos.xx(), 0.0);
            Assert.assertEquals(posBuf.get(i * 3 + 1), storePos.yy(), 0.0);
            Assert.assertEquals(posBuf.get(i * 3 + 2), storePos.zz(), 0.0);
            Assert.assertEquals(rotBuf.get(i * 4 + 3), storeRot.getW(), 0.0);
        }

        // the combined getter must agree with per-body reads exactly
        final DoubleBuffer readPos
                = Jolt.newDirectDoubleBuffer(numBodies * 3);
        final FloatBuffer readRot
                = Jolt.newDirectFloatBuffer(numBodies * 4);
        bodyInterface.getPositionsAndRotations(ids, readPos, readRot);
        for (int i = 0; i < numBodies; ++i) {
            bodyInterface.getPosition(ids.get(i), storePos);
            bodyInterface.getRotation(ids.get(i), storeRot);
            Assert.assertEquals(storePos.xx(), readPos.get(i * 3), 0.0);
            Assert.assertEquals(storePos.yy(), readPos.get(i * 3 + 1), 0.0);
            Assert.assertEquals(storePos.zz(), readPos.get(i * 3 + 2), 0.0);
            Assert.assertEquals(storeRot.getW(), readRot.get(i * 4 + 3), 0.0);
        }

        TestUtils.testClose(ids);
        TestUtils.cleanupPhysicsSystem(physicsSystem);
        TestUtils.cleanup();
    }
}
