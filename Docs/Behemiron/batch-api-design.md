# Behemiron batch API — design (evidence first)

> Status: design settled from measurements; two APIs to build, the rest deliberately
> NOT built. Scope: JNI-boundary only — Jolt's solver internals are already SIMD/SoA
> and are not ours to rewrite.

## 1. Measured boundary costs

Harness: `src/test/java/testjoltjni/app/behemiron/BoundaryProfiler.java`
(2000 kinematic bodies, Windows x64, MSVC Release/Sp fork build, best of 20 rounds,
3 warmup rounds; load/step excluded from the timed sections).

| workload | ns/body | ms per 2000-body tick | % of 50 ms |
| --- | ---: | ---: | ---: |
| read, per-body allocating (`getPosition`+`getRotation`) | 163–238 | 0.33–0.48 | 0.7–1.0 % |
| read, per-body store variants | 226–238 | 0.45–0.48 | 0.9–1.0 % |
| read, batch buffers (`getPositions`+`getRotations`) | 35–41 | 0.07–0.08 | 0.1–0.2 % |
| read, batch matrices (`getCenterOfComTransforms`) | 25–31 | 0.05–0.06 | 0.1 % |
| write, per-body (`setPositionAndRotation`) | 123–152 | 0.25–0.30 | 0.5–0.6 % |
| write, batch positions only (`setPositions`) | 62–72 | 0.12–0.14 | 0.2–0.3 % |
| **read, batch pos+rot (new combined API)** | **36.3** | **0.07** | **0.1 %** |
| **write, batch pos+rot (new combined API)** | **63.1** | **0.13** | **0.3 %** |
| step, kinematic world (empty solver work) | 0.7–0.8 | ~0 | ~0 % |

Reading of the numbers:

1. **Batched reads win 6–9×.** A read pair costs two JNI crossings per body
   (~110–120 ns each); one batch call amortizes the crossings to ~30–40 ns/body.
2. **Allocation is not the tax.** Allocating getters (163–238 ns) and store-variant
   getters (226–238 ns) are within noise of each other — the JNI crossing dominates,
   so "avoid object churn" is not by itself a reason to add APIs.
3. **Writes barely win.** Per-body `setPositionAndRotation` is a single call
   (123–152 ns/body). A positions-only batch costs 62–72 ns/body and still leaves
   rotations on the per-body path; a combined pos+rot batch is expected to land in
   the same range as per-body writes. Revisit only with engine numbers.
4. **At Minecraft scale the boundary is not the bottleneck.** Even the worst
   pattern (per-body allocating reads) costs ≤1 % of a 50 ms tick at 2000 bodies.
   This caps how much API surface is justified.

## 2. What we build (two APIs, both in `BatchBodyInterface`)

1. `getPositionsAndRotations(ConstBodyIdArray ids, DoubleBuffer positions,
   FloatBuffer rotations)` — halves the read path's crossings versus the existing
   two-call pair. Measured after implementation: **36.3 ns/body** (6.1× faster than
   per-body reads).
2. `setPositionsAndRotations(ConstBodyIdArray ids, DoubleBuffer positions,
   FloatBuffer rotations, EActivation activation)` — the missing rotation batch and
   the paired write. Upstream has `setPositions` only; kinematic drive needs both
   halves atomically. Measured after implementation: **63.1 ns/body** (2.0× faster
   than per-body `setPositionAndRotation`; rotations ride along nearly free versus
   the positions-only batch at 61.1 ns).

Both follow the existing batch conventions: `ConstBodyIdArray` + direct buffers +
`EActivation` on the setter; native loops mirror `getPositions`/`setPositions`.

## 3. What we deliberately do NOT build

- **Batch constraint lambdas** (break metrics): a model has tens of breakable
  constraints at most; per-call `getTotalLambda*` at ~100 ns is noise. The metric
  also differs per constraint type — a batch surface would be all combinatorial
  cost, no measurable gain.
- **Batch body creation/destruction**: event-driven (chunk load, ragdoll spawn),
  not per-tick. Measure first if chunk-heavy content ever shows up.
- **Bulk property getters/setters** (frictions, gravity factors, ...): tooling
  operations, not tick-loop operations; the upstream ones already exist for editing.
- **Vehicle/character batched updates**: entity counts are small and
  `CharacterVirtual.update` cost is per-character CPU, not crossing overhead.

## 4. Verification

- `GlueSmoke` (same package) validates the fork's 43 `GetTotalLambda*` JNI symbols
  end-to-end: one constraint instance of all 11 types, one step, every getter,
  finite results (`GLUE-SMOKE-PASS`).
- The two new APIs: JUnit equality test against per-body reads on a small world,
  plus a `BoundaryProfiler` rerun to confirm the ns/body targets.
- Step-cost context belongs to the engine integration profile (real body mix,
  vehicles, characters); the synthetic kinematic world here says nothing about it.

## 5. Local native build notes (Windows)

`gradle` fails to auto-detect this machine's VS 2026 (vswhere does not report the
VCTools component though `cl.exe` is installed), and a non-VS `gcc` on `PATH` gets
picked instead. Working recipe used for the measurements:

1. A writable shim directory with junctions to the real VS install plus the
   toolset version marker Gradle looks for:
   `vc/Auxiliary/Build/Microsoft.VCToolsVersion.default.txt` (content: the MSVC
   version, e.g. `14.44.35207`), junctions `VC/Tools`, `Common7`, `MSBuild`.
2. Build with a minimal PATH (no msys gcc) and the shim pinned:
   `gradlew -Pbt=Release -Pflavor=Sp -Ptarget=Windows64 -Ptool=visualCpp -PvsDir=<shim> assemble`
3. If the `auto/` JNI headers changed but a glue `.cpp` did not, Gradle's up-to-date
   check can miss the header dependency — delete the affected `*.obj` (or the whole
   `build/objs`) before rebuilding. Stale objects showed up as C++-name-mangled
   `Java_...` exports (UnsatisfiedLinkError at runtime).
