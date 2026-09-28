# Car Damage AI-Pipeline

Research prototype: an AI-pipeline for recognizing visible car damage from
photos using Claude Vision, plus an objective benchmark of its quality.

This is the object of study. The Telegram bot and the price estimate are
a minimal demo interface / illustrative feature, not the research focus —
see the technical specification for the full framing.

**Status:** initial skeleton, per teacher's go-ahead to start the
repository and Spring Boot scaffold in parallel with finalizing the TZ.
Not yet runnable end-to-end (see "What's not done yet" below).

---

## Structure

```
src/main/java/com/cardamage/
├── controller/          REST endpoint for the DEMO scenario (3-10 photos)
│   └── advice/          global exception -> JSON error mapping
├── service/
│   ├── ClaudeVisionService.java     single-image call to Claude Vision
│   ├── DamageAnalysisService.java   retry + fallback around the above
│   └── DamageMergeService.java      multi-photo merging (demo scenario only)
├── validation/
│   └── DamageResponseValidator.java explicit Bean Validation call — see
│                                    its javadoc for why this exists
├── model/                POJOs (Damage, DamageAssessment) + JPA entity
├── repository/           Spring Data JPA repository (SQLite)
├── exception/            ValidationException, ClaudeApiException
└── config/               WebClient setup for the Anthropic API

src/main/resources/
├── application.yml            config, incl. anthropic.model (see TODO inside)
├── schema/damage-assessment-schema.json   reference contract, for humans
└── data/demo-prices.json      demo price lookup table (illustrative only)

scripts/
└── select_benchmark_samples.py   stratified dev/test sampling from CarDD
                                   (see its own docstring — this is the
                                   "fix identifiers before experiments"
                                   step from the TZ)

benchmark/
└── (empty until select_benchmark_samples.py is run against the real
    CarDD annotation files)
```

## Benchmark vs demo scenario — where each lives

Per the TZ, these two use different code paths on purpose:

- **Benchmark** (single CarDD image, IoU-matched against ground truth):
  offline, via `scripts/select_benchmark_samples.py` for sample selection.
  The benchmark *runner* itself (calling `DamageAnalysisService.analyzeImage`
  once per selected image, then computing recall/precision/F1/severity
  accuracy/Brier score against ground truth) is the next milestone — not
  yet in this skeleton.
- **Demo** (3-10 photos of one car, from a real user): the
  `POST /api/v1/assessment/analyze` REST endpoint, which calls
  `DamageAnalysisService` once per photo and merges results via
  `DamageMergeService`.

## What's implemented in this skeleton

- Spring Boot project structure and Maven build file
- `Damage` / `DamageAssessment` models with Bean Validation annotations
- `DamageResponseValidator` — the explicit validation step (annotations
  alone do not validate anything; see its javadoc)
- `ClaudeVisionService` — builds the prompt, calls the Anthropic Messages
  API via `WebClient`, parses and validates the response
- `DamageAnalysisService` — `@Retryable`/`@Recover` retry-then-fallback
  logic (3 attempts, then a well-formed `status=error` response)
- `DamageMergeService` — multi-photo merge rules for the demo scenario
- `DamageAssessmentController` — REST endpoint for the demo scenario, with
  input validation (3-10 images, format/size checks)
- SQLite persistence of demo sessions via Spring Data JPA
- `scripts/select_benchmark_samples.py` — stratified dev/test sampling,
  tested against synthetic COCO-format fixtures (18 dev + 24 test,
  reproducible with a fixed seed)
- Unit tests for `DamageResponseValidator` and `DamageMergeService`

## What's NOT done yet (next milestones)

- **Not built/run in this environment.** This container's network access
  does not include Maven Central, so `mvn compile` / `mvn test` have not
  actually been executed here. Please run `mvn clean verify` locally
  before relying on this.
- The exact Anthropic API model identifier in `application.yml`
  (`anthropic.model`) is marked as a TODO — verify it against current
  Anthropic documentation before the first real call, per the teacher's
  request to fix this precisely.
- `ClaudeVisionService` talks to the plain REST endpoint via `WebClient`
  rather than a vendor SDK, because the official Anthropic Java SDK's
  Maven coordinates weren't verified. Swap it in if it fits better.
- The benchmark runner (ground truth loading, IoU matching, metric
  computation — recall, precision, F1, severity accuracy, Brier score)
  is not implemented yet. `select_benchmark_samples.py` only produces the
  fixed list of image IDs to use.
- Ground truth labeling (part / severity / action for the 42 selected
  images, by two independent annotators) hasn't started — it depends on
  actually downloading CarDD, which requires manual access outside this
  environment.
- Telegram bot client itself (this REST API is meant to sit behind it).

## Running locally (once you have a JDK/Maven environment)

```bash
export ANTHROPIC_API_KEY=your_key_here
mvn clean verify
mvn spring-boot:run
```

## Running the sampling script

```bash
cd scripts
pip install -r requirements.txt   # not needed yet — stdlib only for now
python select_benchmark_samples.py \
    --val-annotations /path/to/CarDD/annotations/instances_val.json \
    --test-annotations /path/to/CarDD/annotations/instances_test.json \
    --seed 42 \
    --out-dir ../benchmark
```

Commit the resulting `benchmark/dev_examples.*` and
`benchmark/test_examples.*` files immediately — per the TZ, sample
identifiers must be fixed before any prompt tuning or labeling begins.
