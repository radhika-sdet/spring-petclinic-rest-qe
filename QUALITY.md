# Quality Investigation & Verification Report

## Scope and System Understanding
- **Target area:** Nested resource endpoints under `OwnerRestControllerV1` (`src/main/java/org/springframework/samples/petclinic/rest/controller/v1/OwnerRestControllerV1.java`), specifically:
  - `GET /api/owners/{ownerId}/pets/{petId}` (`getOwnersPet`)
  - `PUT /api/owners/{ownerId}/pets/{petId}` (`updateOwnersPet`)
  - `POST /api/owners/{ownerId}/pets/{petId}/visits` (`addVisitToOwner`)
- **System boundaries & behavior:**
  - Although the Java package is named `v1`, these controllers serve the primary `/api/owners/...` REST contract documented under the `owners` tag in OpenAPI (`v2` controllers only provide paginated `GET /api/v2/owners` and `GET /api/v2/pets` list endpoints).
  - Existing controller unit tests (`OwnerRestControllerV1Tests`) mock `ClinicService` via `@MockitoBean`, isolating HTTP serialization from real JPA/H2 entity relationships.
  - Within `OwnerRestControllerV1`, `getOwnersPet` verified ownership via `owner.getPet(petId)` and returned `404 Not Found` on mismatch, whereas `updateOwnersPet` and `addVisitToOwner` bypassed ownership checks.

## Risks Considered (Ranked)
1. **Cross-owner state mutation & visit injection (High impact, High likelihood — Selected):**
   - `updateOwnersPet` looked up `findPetById(petId)` globally without checking whether `petId` belonged to `ownerId`, allowing callers to overwrite another owner's pet (`204 No Content`).
   - `addVisitToOwner` ignored `ownerId` entirely and constructed a transient `Pet` stub (`pet.setId(petId)`), allowing visits to be attached to any pet under an unrelated or non-existent `ownerId` (`201 Created`).
2. **Unhandled persistence errors on non-existent child IDs (Medium-High impact, Medium likelihood — Partially verified):**
   - Constructing a detached `Pet` stub in `addVisitToOwner` without loading the entity risked database foreign-key errors (`500`) instead of `404 Not Found`. Baseline testing against H2 showed `ExceptionControllerAdvice` already mapped `DataIntegrityViolationException` to `404`, though explicit entity lookup is cleaner and avoids relying on DB constraint failures.
3. **Flat endpoint ID mismatch & cross-owner access on `/api/pets/{petId}` and `/api/visits` (Medium impact, Medium likelihood — Out of scope for timebox):**
   - Top-level endpoints (`PetRestControllerV1`, `VisitRestControllerV1`) accept requests without owner context and do not validate path ID vs. payload ID consistency on `PUT`.
4. **HTTP semantics & OpenAPI contract drift (Low-Medium impact, High likelihood — Out of scope for timebox):**
   - Update endpoints (`updateOwner`, `updatePet`, `updateVisit`) return `204 No Content` with a serialized JSON body, and `404` responses return empty bodies (`content-length: 0`) rather than the documented `ProblemDetail` schema.

## Changes and Rationale
I chose **Risk #1** because cross-owner data corruption has the highest business impact, directly contradicts `getOwnersPet` behavior in the same controller, and can be fixed and verified deterministically against the real H2 database within the timebox.

- **`src/main/java/org/springframework/samples/petclinic/rest/controller/v1/OwnerRestControllerV1.java`:**
  - Updated `updateOwnersPet` and `addVisitToOwner` to resolve `Owner` via `clinicService.findOwnerById(ownerId)` and verify pet ownership via `owner.getPet(petId)`, returning `404 Not Found` when either the owner does not exist or the pet does not belong to that owner.
- **`src/test/java/org/springframework/samples/petclinic/rest/controller/OwnerNestedResourceOwnershipTests.java`:**
  - Added a `@SpringBootTest` + `@Transactional` integration test suite running against the real H2 seed data (`Owner 1 -> Pet 1`, `Owner 2 -> Pet 2`) covering cross-owner pet update rejection, valid pet update, cross-owner visit injection rejection, non-existent owner rejection, non-existent pet `404`, and valid visit creation.
- **`src/test/java/org/springframework/samples/petclinic/rest/controller/OwnerRestControllerV1Tests.java`:**
  - Updated two mocked unit test fixtures (`testCreateVisitSuccess` and `testUpdateOwnersPetSuccess`) that previously omitted `findOwnerById` stubbing or paired `ownerId=1` with unowned `petId=3` because the old controller did not enforce ownership.

## Evidence
### Main Verification Command (Runs from Clean Checkout)
```bash
./mvnw test -Dtest=OwnerNestedResourceOwnershipTests,OwnerRestControllerV1Tests
```
*(On Windows PowerShell: `.\mvnw.cmd test "-Dtest=OwnerNestedResourceOwnershipTests,OwnerRestControllerV1Tests"`)*

No Docker, network access, credentials, or manual steps are required; tests run against the embedded H2 database.

### Observed Results
- **Before controller fix (Red baseline):**
  - `Tests run: 6, Failures: 3, Errors: 0, Skipped: 0`
  - `updateOwnersPetRejectsPetBelongingToDifferentOwner`: expected `<404>` but was `<204>`
  - `addVisitToOwnerRejectsPetBelongingToDifferentOwner`: expected `<404>` but was `<201>`
  - `addVisitToOwnerRejectsNonExistentOwner`: expected `<404>` but was `<201>`
- **After controller fix and mock fixture alignment (Green):**
  - `Tests run: 35, Failures: 0, Errors: 0, Skipped: 0` (`BUILD SUCCESS`)
- *(Note: To reproduce the "Before" failures locally, temporarily restore `OwnerRestControllerV1.java` to commit `4cd8e1b0cd42578e882247d8801f6be5d402f118` and run `OwnerNestedResourceOwnershipTests`.)*
```bash
git checkout 4cd8e1b0cd42578e882247d8801f6be5d402f118 -- src/main/java/org/springframework/samples/petclinic/rest/controller/v1/OwnerRestControllerV1.java
./mvnw test -Dtest=OwnerNestedResourceOwnershipTests
git checkout HEAD -- src/main/java/org/springframework/samples/petclinic/rest/controller/v1/OwnerRestControllerV1.java
```


## Confidence Limits
- **What the evidence shows:**
  - Nested `PUT` and `POST .../visits` endpoints under `/api/owners/{ownerId}/pets/{petId}` now enforce owner-pet association and return `404 Not Found` on mismatches against the real Spring Data JPA + H2 persistence layer.
  - Existing `OwnerRestControllerV1Tests` pass without regression.
- **What the evidence does not show:**
  - **Authentication/Authorization enforcement:** Tests run with `@WithMockUser(roles = "OWNER_ADMIN")` and `petclinic.security.enable=false` by default; role-based access control (`401`/`403`) and per-owner user authorization were not tested.
  - **Other database engines:** Verified only on embedded H2 (not MySQL or PostgreSQL profiles).
  - **Other endpoints & concurrency:** Flat endpoints (`/api/pets/{petId}`, `/api/visits`), `DELETE /api/owners/{ownerId}` cascade behavior, and concurrent updates were not tested.

## Work Left Out
- Auditing flat `/api/pets/{petId}` and `/api/visits` endpoints for path/body ID mismatches and missing reference validation (`404` vs `500`).
- Enabling `petclinic.security.enable=true` to test `OWNER_ADMIN` vs `VET_ADMIN` vs unauthenticated access boundaries.
- Aligning HTTP `204` empty-body rules and `404` `ProblemDetail` payloads with the OpenAPI specification.

## Next Step (With One More Day)
1. Audit and add integration tests for flat resource endpoints (`PetRestControllerV1`, `VisitRestControllerV1`, `VetRestControllerV1`), including path-vs-body ID validation on `PUT` requests.
2. Add a Spring Security integration test profile (`petclinic.security.enable=true`) verifying `401`/`403` behavior across roles.
3. Introduce OpenAPI contract validation in CI so runtime status codes and error schemas cannot drift from the specification.

## Time Spent
Approximately **1 hour 30 minutes** of counted time (excluding initial repository/environment setup).
