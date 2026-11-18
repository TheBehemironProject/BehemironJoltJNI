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

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BehemironLoader;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable;
import com.github.stephengold.joltjni.ConeConstraint;
import com.github.stephengold.joltjni.ConeConstraintSettings;
import com.github.stephengold.joltjni.DistanceConstraint;
import com.github.stephengold.joltjni.DistanceConstraintSettings;
import com.github.stephengold.joltjni.FixedConstraint;
import com.github.stephengold.joltjni.FixedConstraintSettings;
import com.github.stephengold.joltjni.GearConstraint;
import com.github.stephengold.joltjni.GearConstraintSettings;
import com.github.stephengold.joltjni.HingeConstraint;
import com.github.stephengold.joltjni.HingeConstraintSettings;
import com.github.stephengold.joltjni.JobSystemSingleThreaded;
import com.github.stephengold.joltjni.Jolt;
import com.github.stephengold.joltjni.ObjVsBpFilter;
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable;
import com.github.stephengold.joltjni.PhysicsSystem;
import com.github.stephengold.joltjni.PointConstraint;
import com.github.stephengold.joltjni.PointConstraintSettings;
import com.github.stephengold.joltjni.PulleyConstraint;
import com.github.stephengold.joltjni.PulleyConstraintSettings;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.RackAndPinionConstraint;
import com.github.stephengold.joltjni.RackAndPinionConstraintSettings;
import com.github.stephengold.joltjni.SixDofConstraint;
import com.github.stephengold.joltjni.SixDofConstraintSettings;
import com.github.stephengold.joltjni.SliderConstraint;
import com.github.stephengold.joltjni.SliderConstraintSettings;
import com.github.stephengold.joltjni.SwingTwistConstraint;
import com.github.stephengold.joltjni.SwingTwistConstraintSettings;
import com.github.stephengold.joltjni.TempAllocatorImpl;
import com.github.stephengold.joltjni.TwoBodyConstraint;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import com.github.stephengold.joltjni.BoxShape;

/**
 * JNI symbol smoke test for the fork's GetTotalLambda* glue. Creates one
 * instance of every two-body constraint type, steps the world, and invokes
 * every lambda getter, asserting finite results. A missing/wrongly named JNI
 * symbol fails here with UnsatisfiedLinkError.
 *
 * Run: java -cp <classes>;<natives> testjoltjni.app.behemiron.GlueSmoke
 */
public class GlueSmoke {
    // *************************************************************************
    // fields

    private static int callCount;

    private static PhysicsSystem physicsSystem;
    private static BodyInterface bodyInterface;
    private static TempAllocatorImpl tempAllocator;
    private static JobSystemSingleThreaded jobSystem;
    private static int bodyA;
    private static int bodyB;
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

        smokeFixed();
        smokeDistance();
        smokePoint();
        smokeHinge();
        smokeSlider();
        smokeCone();
        smokeSwingTwist();
        smokeSixDof();
        smokePulley();
        smokeGear();
        smokeRackAndPinion();

        System.out.println("GLUE-SMOKE-PASS calls=" + callCount);
        System.exit(0);
    }
    // *************************************************************************
    // private methods

    private static void check(Vec3 v, String what) {
        callCount += 3;
        if (!Float.isFinite(v.getX()) || !Float.isFinite(v.getY())
                || !Float.isFinite(v.getZ())) {
            throw new AssertionError("non-finite Vec3 from " + what);
        }
    }

    private static void check(float f, String what) {
        callCount += 1;
        if (!Float.isFinite(f)) {
            throw new AssertionError("non-finite float from " + what);
        }
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
        physicsSystem.init(64, 0, 32, 32, map, ovbFilter, ovoFilter);
        physicsSystem.setGravity(0f, -9.81f, 0f);

        tempAllocator = new TempAllocatorImpl(1024 * 1024);
        jobSystem = new JobSystemSingleThreaded(1024);

        bodyInterface = physicsSystem.getBodyInterface();
        final BoxShape shape = new BoxShape(0.5f, 0.5f, 0.5f);
        final RVec3 posA = new RVec3(0.0, 5.0, 0.0);
        final RVec3 posB = new RVec3(1.0, 5.0, 0.0);
        final Quat rot = new Quat(0f, 0f, 0f, 1f);
        final BodyCreationSettings settingsA = new BodyCreationSettings(
                shape, posA, rot, EMotionType.Dynamic, 1);
        final BodyCreationSettings settingsB = new BodyCreationSettings(
                shape, posB, rot, EMotionType.Dynamic, 1);
        bodyA = bodyInterface.createAndAddBody(settingsA, EActivation.Activate);
        bodyB = bodyInterface.createAndAddBody(settingsB, EActivation.Activate);
    }

    private static void step() {
        physicsSystem.update(1f / 60f, 1, tempAllocator, jobSystem);
    }

    private static void smoke(
            com.github.stephengold.joltjni.TwoBodyConstraintSettings settings,
            String what) {
        final TwoBodyConstraint constraint
                = bodyInterface.createConstraint(settings, bodyA, bodyB);
        physicsSystem.addConstraint(constraint);
        step();
        invokeLambdas(constraint, what);
        physicsSystem.removeConstraint(constraint);
    }

    private static void invokeLambdas(
            TwoBodyConstraint constraint, String what) {
        switch (what) {
            case "Fixed":
                final FixedConstraint fixed = (FixedConstraint) constraint;
                check(fixed.getTotalLambdaPosition(), what + ".position");
                check(fixed.getTotalLambdaRotation(), what + ".rotation");
                break;
            case "Distance":
                final DistanceConstraint dist = (DistanceConstraint) constraint;
                check(dist.getTotalLambdaPosition(), what + ".position");
                break;
            case "Point":
                final PointConstraint point = (PointConstraint) constraint;
                check(point.getTotalLambdaPosition(), what + ".position");
                break;
            case "Hinge":
                final HingeConstraint hinge = (HingeConstraint) constraint;
                check(hinge.getTotalLambdaPosition(), what + ".position");
                check(hinge.getTotalLambdaRotationX(), what + ".rotationX");
                check(hinge.getTotalLambdaRotationY(), what + ".rotationY");
                check(hinge.getTotalLambdaRotationLimits(),
                        what + ".rotationLimits");
                break;
            case "Slider":
                final SliderConstraint slider = (SliderConstraint) constraint;
                check(slider.getTotalLambdaPositionX(), what + ".positionX");
                check(slider.getTotalLambdaPositionY(), what + ".positionY");
                check(slider.getTotalLambdaPositionLimits(),
                        what + ".positionLimits");
                check(slider.getTotalLambdaRotation(), what + ".rotation");
                break;
            case "Cone":
                final ConeConstraint cone = (ConeConstraint) constraint;
                check(cone.getTotalLambdaPosition(), what + ".position");
                check(cone.getTotalLambdaRotation(), what + ".rotation");
                break;
            case "SwingTwist":
                final SwingTwistConstraint st = (SwingTwistConstraint) constraint;
                check(st.getTotalLambdaPosition(), what + ".position");
                check(st.getTotalLambdaTwist(), what + ".twist");
                check(st.getTotalLambdaSwingY(), what + ".swingY");
                check(st.getTotalLambdaSwingZ(), what + ".swingZ");
                break;
            case "SixDof":
                final SixDofConstraint six = (SixDofConstraint) constraint;
                check(six.getTotalLambdaPosition(), what + ".position");
                check(six.getTotalLambdaRotation(), what + ".rotation");
                break;
            case "Pulley":
                final PulleyConstraint pulley = (PulleyConstraint) constraint;
                check(pulley.getTotalLambdaPosition(), what + ".position");
                break;
            case "Gear":
                final GearConstraint gear = (GearConstraint) constraint;
                check(gear.getTotalLambda(), what + ".lambda");
                break;
            case "RackAndPinion":
                final RackAndPinionConstraint rp
                        = (RackAndPinionConstraint) constraint;
                check(rp.getTotalLambda(), what + ".lambda");
                break;
            default:
                throw new IllegalArgumentException(what);
        }
    }

    private static void smokeFixed() {
        final FixedConstraintSettings s = new FixedConstraintSettings();
        s.setAutoDetectPoint(true);
        smoke(s, "Fixed");
    }

    private static void smokeDistance() {
        final DistanceConstraintSettings s = new DistanceConstraintSettings();
        s.setPoint1(0.0, 5.0, 0.0);
        s.setPoint2(1.0, 5.0, 0.0);
        smoke(s, "Distance");
    }

    private static void smokePoint() {
        final PointConstraintSettings s = new PointConstraintSettings();
        s.setPoint1(0.0, 5.0, 0.0);
        s.setPoint2(1.0, 5.0, 0.0);
        smoke(s, "Point");
    }

    private static void smokeHinge() {
        final HingeConstraintSettings s = new HingeConstraintSettings();
        s.setPoint1(0.0, 5.0, 0.0);
        s.setPoint2(1.0, 5.0, 0.0);
        s.setHingeAxis1(0f, 1f, 0f);
        s.setHingeAxis2(0f, 1f, 0f);
        s.setNormalAxis1(1f, 0f, 0f);
        s.setNormalAxis2(1f, 0f, 0f);
        smoke(s, "Hinge");
    }

    private static void smokeSlider() {
        final SliderConstraintSettings s = new SliderConstraintSettings();
        s.setPoint1(0.0, 5.0, 0.0);
        s.setPoint2(1.0, 5.0, 0.0);
        s.setSliderAxis1(1f, 0f, 0f);
        s.setSliderAxis2(1f, 0f, 0f);
        s.setNormalAxis1(0f, 1f, 0f);
        s.setNormalAxis2(0f, 1f, 0f);
        smoke(s, "Slider");
    }

    private static void smokeCone() {
        final ConeConstraintSettings s = new ConeConstraintSettings();
        s.setPoint1(0.0, 5.0, 0.0);
        s.setPoint2(1.0, 5.0, 0.0);
        s.setTwistAxis1(0f, 1f, 0f);
        s.setTwistAxis2(0f, 1f, 0f);
        smoke(s, "Cone");
    }

    private static void smokeSwingTwist() {
        final SwingTwistConstraintSettings s
                = new SwingTwistConstraintSettings();
        s.setPosition1(0.0, 5.0, 0.0);
        s.setPosition2(1.0, 5.0, 0.0);
        s.setTwistAxis1(0f, 1f, 0f);
        s.setTwistAxis2(0f, 1f, 0f);
        s.setPlaneAxis1(1f, 0f, 0f);
        s.setPlaneAxis2(1f, 0f, 0f);
        smoke(s, "SwingTwist");
    }

    private static void smokeSixDof() {
        final SixDofConstraintSettings s = new SixDofConstraintSettings();
        s.setPosition1(0.0, 5.0, 0.0);
        s.setPosition2(1.0, 5.0, 0.0);
        smoke(s, "SixDof");
    }

    private static void smokePulley() {
        final PulleyConstraintSettings s = new PulleyConstraintSettings();
        s.setFixedPoint1(0.0, 10.0, 0.0);
        s.setFixedPoint2(1.0, 10.0, 0.0);
        s.setBodyPoint1(0.0, 5.0, 0.0);
        s.setBodyPoint2(1.0, 5.0, 0.0);
        smoke(s, "Pulley");
    }

    private static void smokeGear() {
        final GearConstraintSettings s = new GearConstraintSettings();
        smoke(s, "Gear");
    }

    private static void smokeRackAndPinion() {
        final RackAndPinionConstraintSettings s
                = new RackAndPinionConstraintSettings();
        smoke(s, "RackAndPinion");
    }
}
