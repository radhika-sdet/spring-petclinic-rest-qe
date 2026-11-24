# AI Usage Report

## Tools and Models Used
- **Google Gemini** (web chat interface) — used for codebase exploration guidance, risk ranking, test-first bug reproduction strategy, integration test scaffolding, and report structuring.
- *(Note: Briefly attempted to connect the Gemini Code Assist VS Code extension during setup, but switched directly to browser-based Gemini chat to stay within the assignment timebox.)*

## Meaningful Work Delegated to AI
1. **Controller & Test Architecture Analysis:** Provided source code from `VisitRestControllerV1`, `PetRestControllerV1`, `PetRestControllerV2`, `OwnerRestControllerV1`, `OwnerRestControllerV2Tests`, and `V2RestControllersTests` to identify validation and ownership inconsistencies across nested routes.
2. **Risk Brainstorming & Prioritization:** Used AI to structure and rank quality risks around nested `Owner -> Pet -> Visit` operations and compare verification options (Swagger/Postman manual checks vs. automated JUnit integration tests).
3. **Test & Fix Scaffolding:** Generated an initial JUnit 5 `@SpringBootTest` + `MockMvc` test class (`OwnerNestedResourceOwnershipTests`) and ownership-check implementation matching `getOwnersPet`.

## Session Evidence & Impact on Changes
- **Targeted code inspection:** AI highlighted the contrast inside `OwnerRestControllerV1` between `getOwnersPet` (which checked `owner.getPet(petId)`) and `updateOwnersPet` / `addVisitToOwner` (which omitted ownership checks).
- **Red-to-Green verification:** Following AI's recommendation to run `OwnerNestedResourceOwnershipTests` *before* modifying production code produced concrete baseline failure evidence (`204` and `201` responses instead of `404`) prior to applying the fix.
- **Diagnosing existing unit test failures:** When `OwnerRestControllerV1Tests` reported 2 failures after the controller fix, AI traced the cause to pre-existing mock fixtures (`testCreateVisitSuccess` omitting `findOwnerById` stubbing, and `testUpdateOwnersPetSuccess` pairing `ownerId=1` with unowned `petId=3`).

## Independent Verification Performed
1. **Verified `v1` vs `v2` API surface directly:** Before modifying `OwnerRestControllerV1.java`, I inspected `OwnerRestControllerV2.java`, `PetRestControllerV2.java`, and the live Swagger UI (`/petclinic/v3/api-docs`) to independently confirm that `v2` only exposes paginated `GET` endpoints and that all `/api/owners/{ownerId}/pets/...` write routes reside in `OwnerRestControllerV1`.
2. **Verified baseline bug reproduction via Maven:** Executed `OwnerNestedResourceOwnershipTests` against the unmodified revision `4cd8e1b0cd42578e882247d8801f6be5d402f118` and inspected Surefire failure output (`status expected:<404> but was:<204>` / `<201>`) rather than assuming the bug existed.
3. **Verified H2 seed data and JPA behavior:** Confirmed seed data relationships (`Owner 1` owns `Pet 1`, `Owner 2` owns `Pet 2`) and ran the full Maven test build to verify compilation, Jackson 3 compatibility, and regression safety.

## Suggestions Rejected or Modified (and Why)
1. **Removed `SecurityMockMvcConfigurers.springSecurity()` from the generated test setup:**
   - *AI suggestion:* The initial `OwnerNestedResourceOwnershipTests` draft included `.apply(springSecurity())` in `MockMvcBuilders.webAppContextSetup(context)`.
   - *Change & reason:* After inspecting `OwnerRestControllerV1Tests` and `V2RestControllersTests`, I saw existing controller tests use standalone/web-app `MockMvcBuilders` without `springSecurity()` and run with security disabled by default. I removed `.apply(springSecurity())` to keep the test aligned with the repository's existing test configuration and dependencies.
2. **Rejected manual Swagger/Postman-only verification as the primary artifact:**
   - *Change & reason:* Instead of relying on manual Swagger UI execution for evidence, I used automated JUnit + Maven integration tests so any reviewer can reproduce the exact verification in a single command from a clean checkout.

## What AI Got Wrong or Assumed Incorrectly
- **Predicted `500` error on non-existent `petId` in `addVisitToOwner`:** AI initially predicted that calling `POST /api/owners/1/pets/99999/visits` on the unmodified code would fail with an unhandled `500` database foreign-key exception. When I ran `addVisitToOwnerReturnsNotFoundForNonExistentPet` against the unmodified codebase, it already returned `404` because `ExceptionControllerAdvice` maps `DataIntegrityViolationException` to `404 Not Found`.
- **Assumed existing `OwnerRestControllerV1Tests` would pass unchanged after the fix:** Because the existing unit tests mocked `ClinicService` with inconsistent owner/pet IDs, two existing tests failed once ownership enforcement was added and required fixture alignment.

## What I Would Not Merge Without Further Verification
- Before merging into a production system with authentication enabled, I would verify behavior with `petclinic.security.enable=true` to confirm whether unauthorized cross-owner access should return `403 Forbidden` (for authenticated non-admin owners) vs. `404 Not Found` (to avoid resource ID enumeration), and run the suite against the MySQL/PostgreSQL profiles in addition to H2.
