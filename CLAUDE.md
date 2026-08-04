# CLAUDE.md — Hang Ngoc Pharmacy (PISMS)

Guidance for Claude Code working in this repository. Read this first, then
`docs/context/current-project-state.md` for the detailed feature/scope log.

**Snapshot taken 2026-08-04.** HEAD `abe1fc6 Merge pull request #158 from nguyentruong16/hoang`,
branch `hoang`, still **AHEAD of `origin/hoang`, not pushed**. Working tree clean apart from the
perpetually-untracked `.claude/`, `CLAUDE.md`, `docs/`, `run-dev.cmd` — normal here. This branch
auto-commits on save/checkpoint, so "working tree clean" reflects that, not an absence of recent
work — check `git log`. This session's own feature build (Purchase Invoice approval workflow) is
what changed since the last snapshot — see the box below and "🔴 Read fifth" for the full write-up.

`mvn compile` clean. `mvn test` → **536 run, 5 failures, 29 errors** (was 519/6/29 last snapshot).
`+17` net tests, all in `PurchaseinvoiceServiceTest` (53→70, new tests for the Nháp/Chờ duyệt/duyệt/
từ chối/xóa workflow — see "🔴 Read fifth"). **Failures dropped by one**: this session also fixed a
stale `SidebarMenuServiceTest` assertion (`accountantMenu_matchesTheAgreedGroupOrder` was missing the
already-shipped "Báo cáo ca" group from its `containsExactly` list — unrelated pre-existing staleness,
fixed in passing while touching that same test for the new Accountant "Hàng hóa" sidebar entry).
Everything else unchanged from the last snapshot (see Testing rules).

> **🔴 RESOLVED this session: `Purchaseinvoice.approvedAt` is now wired up, and it's wired to a real
> Nháp → Chờ duyệt → Duyệt/Từ chối workflow, not a rubber-stamp.** The previous snapshot flagged this
> column (added `@NotNull` by a teammate with zero service-layer writer, and a docx that had started
> describing `Nháp`/`Chờ duyệt` statuses nothing in code produced) as a high-confidence blocker. The
> user confirmed the BA intent directly: **when the pharmacy has an active Accountant, the Accountant
> now creates purchase invoices and the Owner only approves/rejects them; when there is no active
> Accountant, the Owner still creates directly, one step, exactly as before.** Building this required
> also changing `approvedAt` from `NOT NULL` to nullable (the user explicitly approved this schema
> change, hand-patched into the entity + live DB + `current-database.sql`, no Flyway migration, same
> pattern as every other schema change in this repo) — a Nháp/Chờ duyệt row has no `approvedAt` yet by
> definition. Full mechanism, all the new service methods, and the live-verified test matrix are under
> **"🔴 Read fifth"** below; the **"a purchase invoice is always created as 'Nợ', no approval step"**
> business-decision bullet three sections down is now conditional, not universal — read the revised
> bullet before touching `PurchaseinvoiceService`.
>
> **(2026-08-03 history, still true) The `Financialsetting.setupConfirmed` / `@Version` reserve
> landed for real, and the full feature is built.** A teammate's `0ff6daf` commit added both back to the entity
> (this time with the DB hand-patched to match, and the docx updated too — confirmed
> `setupConfirmed boolean DEFAULT false` in `current-database.sql`, description *"Sau khi setting
> xong, sẽ khóa nhóm thuế và quỹ lại"*). This session built the actual feature on top of it: a new
> **`SetupConfirmedInterceptor`** blocks every authenticated request, for every role, until
> `Financialsetting.setupConfirmed = true` — Owner is redirected to `/owner/financial-setting`
> (editable) to complete setup, Pharmacist/Accountant to their own read-only view (new
> `/pharmacist/financial-setting` route added to match the existing `/accountant/financial-setting`
> one) with a "waiting on Owner" message. `revenueGroup`, `cashSafeBalance`, and `bankAccountBalance`
> now lock **together, in one shot**, the moment `setupConfirmed` flips true — replacing the old
> three-separate-signals approach. The two `annualRevenueThreshold1/2` fields were removed from the
> UI entirely (fixed at their `V13`-seeded values, `FinancialsettingService.saveSettings()` no longer
> reads them from the request at all). Full write-up under "🔴 Read third", rewritten again to reflect
> this — the file's older "reverted, don't rebuild without approval" language about this exact column
> is now obsolete; it *was* rebuilt, properly, on request.
>
> **🔴 Income's Owner-only auto-approve rule now applies to every role** — the old "Owner auto-
> completes, everyone else goes to Chờ duyệt" gate is gone entirely; see "🔴 Read fourth" below. A
> related `ShiftreportService` bug (double-crediting the cash fund on shift approval) was fixed in
> the same window, and the Accountant gained read-only access to `/accountant/shift-reports`.
>
> **The Accountant dashboard is built** (`13111a4`) — `PlaceholderController` is now **fully
> deleted** (0 routes). A brand-new **Employee Note** feature also landed (Owner writes free-text
> notes about staff, visible read-only on the employee's own `/profile`). The Notification system
> has **three** active producers (was one) plus pagination. Small UI polish also landed for
> Procurement Plan print (`c03b649`/`c869490`, a "unit conversion" hint column) and Invoice Create
> (`3894769`, an inline "1 Hộp = 10 Vỉ = 100 Viên" unit-chain hint next to each product) — both purely
> additive display features, no business-logic change.

## What this project is

Hang Ngoc Pharmacy **Inventory & Sales Management System** — a student capstone: a web app to
manage a retail pharmacy's products, stock, procurement, sales and finance. Multiple contributors
share branch `hoang` (this working copy) plus teammate work merged in from `duc`/`truong`/`thinh`
via PRs into `develop` and back. **This branch moves fast and auto-commits on save/checkpoint** —
`git status` being clean does NOT mean nothing changed; check `git log`. **Always re-verify with
`git log --oneline -30` and `git fetch` before trusting anything below.**

> `git log` sorts by commit date, so a freshly merged `develop` line can push your own recent
> commits well below the top. If your work looks missing, check
> `git merge-base --is-ancestor <sha> HEAD` before panicking.

## 🔴 Read first: the schema still cannot be rebuilt from the repo

`src/main/resources/db/migration/` is still **`V1`–`V15`** — nothing changed here this session, all
schema drift keeps being hand-applied, same as always:

- **`V1__baseline_existing_schema.sql` is an EMPTY PLACEHOLDER** — two comment lines, no
  `CREATE TABLE` at all ("Baseline migration for an existing Hibernate-managed schema"). The tables
  were originally made by Hibernate, never by a migration.
- `V3`–`V14` are seed data, written as `INSERT … SELECT … WHERE NOT EXISTS`, i.e. **idempotent**.
- **`V15__normalize_notification_table.sql` is the first and only real schema migration** (2026-07-29).

**So a fresh clone + empty MySQL + `flyway migrate` still will NOT reproduce the schema**, and with
`ddl-auto = validate` the app refuses to boot against it. Only the existing local `hang_ngoc_pisms`
works.

- **`docs/context/current-database.sql` was hand-updated again this evening** — now includes
  `Financialsetting.setupConfirmed`/`.version` and `Purchaseinvoice.approvedAt`, matching the live DB
  (confirmed via `DESCRIBE` on both tables). Column drift between the repo and the live DB is, once
  again, fully hand-managed with no migration trail.

**Do not write a schema migration to "catch up" unless the user explicitly asks.**

### 🟠 `@Version` optimistic locking — on FOUR entities again (Batch, Invoice, Purchaseinvoice, Financialsetting)

`Batch` got `@Version` on 2026-07-30. `Invoice`/`Purchaseinvoice` followed 2026-07-31 evening,
requiring the six-service guarded-save refactor already documented below.

`Financialsetting` briefly gained `@Version` (plus `revenueGroupConfirmed`) without approval earlier
today, was reverted at the user's request, and **has now been properly re-added** by a teammate
(`0ff6daf`) alongside `setupConfirmed` (not `revenueGroupConfirmed` — a different, related column —
see "🔴 Read third"), this time with the DB and docx updated to match. `FinancialsettingService`'s
`saveSettings()`/`adjustFundBalances()` still call plain `.save(...)`, **not** a guarded
`saveAndFlush()` — nobody has rebuilt the friendly-message wrapper the other three entities have, so
a genuine concurrent-edit race on this row still surfaces as a raw `ObjectOptimisticLockingFailureException`,
not a flash message. This is a real, still-open gap (see Known gaps), independent of whether the
column itself is now approved.

- **`InvoiceService.persistInvoice(Invoice)`** and **`PurchaseinvoiceService.persistPurchaseInvoice(Purchaseinvoice)`**
  remain the only sanctioned way to save those two entities — both call `saveAndFlush`, not `save`.
- **Treat adding `@Version` to any entity as requiring a check of every git-ignored test that mocks
  that entity's repository** for `.save(` → needs a matching `.saveAndFlush(` stub/verify, the same
  way a `WebConfig` dependency addition already requires checking every `@WebMvcTest`.

### 🟠 `Pharmacy-Database-Description.docx` — updated again this evening, still has real gaps

Regenerated/hand-edited again around 21:49 today (was 19:38 on 2026-07-31, then touched again
mid-session). At minimum, these are now known-stale or internally inconsistent:
- Invoice numbering no longer has an `"HD"` prefix (bare 8-digit zero-padded number).
- `Invoice.invoiceType` is now **always** `"Bán hàng"` — the old `"Hóa đơn GTGT"` value for group-3
  sales is no longer emitted (still recognised on old rows for backward compatibility).
- Tax formulas for groups 2/3 changed (see "🔴 Read third") — any tax-rate table in the docx is stale.
- ~~`Purchaseinvoice.approvedAt`'s docx description ("Thời điểm tạo thông báo") doesn't match its own
  field name, and the docx's `Nháp`/`Chờ duyệt` statuses don't match any code that exists~~ —
  **CLOSED 2026-08-04**: the user fixed the docx description alongside building the real workflow
  those statuses describe (see "🔴 Read fifth"); code and docx now agree here.

**Where the docx and `current-database.sql` disagree, the SQL wins.**

### 🔴 `stockadjustment` lost `createdBy` / `approvedBy` / `approvedAt` (2026-07-29)

Unchanged this session. A stock adjustment no longer records who created it — deliberate, documented
at `StockadjustmentService:573`. Do not re-add the fields; see "Dropped" for the consequence.

## 🔴 Read second: a return slip computes, it does not pay

Unchanged mechanism this session (no dropped fields, no new payout path), but two things were added
around it:

- **`ReturnService`/`ReturnPurchaseService` both gained `isTaxExempt()`**, and
  `ReturnDetailPageResponse`/`ReturnPurchaseDetailPageResponse` gained a `taxExempt` boolean alongside
  the existing `deductionGroup` flag — this **refines, not reverses**, the "groups 1/2 write zeros"
  rule below: the underlying zero-writing math is unchanged, but the return/return-purchase detail
  screens now distinguish *why* tax is zero (Group 1 = exempt, tax rows hidden entirely; Group 2 =
  zero with a "chưa khấu trừ" note).
- **`ReturnService` now fires `WorkflowNotificationService` events** on pending/approve/reject —
  see "Where things are → Notifications".

`b81e80b` dropped `refundCash` / `refundBanking` / `refundCredit` from the `return` table, entity and
DTOs. What survives is `totalRefund` + `offsetDebtAmount`. How the money physically leaves is
**Expense's** data.

- **`ExpenseService` is the only gateway to refunding a customer.** `listCustomerReturns()` +
  `cashRefundAmount()` (= `totalRefund − offsetDebtAmount`) are the whole contract.
- **`ShiftreportService`'s `totalCashOut` is the sum of Expense `paidByCash` for that shift** — it
  has no other source. A slip left without a `shiftReportID` is cash the register can never account
  for.
- **`offsetDebtAmount` is a real number now.** `ReturnService` computes
  `MIN(totalRefund, dư nợ hiện tại)` when `Financialsetting.autoOffsetDebtOnRefund` is on, `0` when
  off. Older notes saying "always 0" are **obsolete**.

**`Return.returnType` holds `CUSTOMER` / `SUPPLIER`.** The codebase also discriminates by FK —
`invoiceID != null` = customer, `purchaseID != null && invoiceID == null` = supplier. The two agree;
don't churn one into the other.

## 🔴 Read third: money moves automatically, tax formulas changed, and setup is now gated

One commit earlier today, `ef633bf` "update tax, finance setting", bundled two large,
previously-undocumented reversals. A follow-up correction removed an unapproved column, and then
**a teammate's later commit (`0ff6daf`) plus this session's own feature build made the corrected
idea real, properly this time.** Read this in full before touching `FinancialsettingService`,
`InvoiceService`, `IncomeService`, `ExpenseService`, `ShiftreportService`, `TaxperiodsnapshotService`,
`WebConfig`, or `financial-setting.html`.

**1. Financial Setting's fund balances are auto-reconciled, not manual — REVERSES the 2026-08-02
decision.** `FinancialsettingService` has two distinct write-through mechanisms:

- **`applyFundDelta(BigDecimal cashDelta, BigDecimal bankingDelta)`** — called from
  `InvoiceService.createSaleInvoice()` (every sale credits the fund immediately, no approval step),
  `IncomeService`'s `creditFundOnCompletion()` (only once a phiếu thu reaches "Hoàn thành" — which,
  per "Read fourth" below, now happens for every role), and `ShiftreportService.creditCashSafe()`
  (at shift **approval**, with just `(cashDiscrepancy, ZERO)` — a *correction* on top of what the
  sale already booked, not the whole handled-cash figure; see the bug fix in "Read fourth"). Treats a
  `null` stored balance as zero (auto-initializes).
- **`adjustFundBalances(BigDecimal cashDelta, BigDecimal bankDelta)`** — used only by `ExpenseService`:
  debits on `applyApproval()` (a slip reaching `COMPLETED`), credits back on `cancelExpense()`
  (reversing an already-disbursed slip). Silently **no-ops** if the stored balance is still `null`.
- **Both methods call plain `financialsettingRepository.save(entity)`** — despite `Financialsetting`
  having `@Version` again (see the `@Version` section above), nobody has rebuilt the guarded
  `saveAndFlush`-with-friendly-message wrapper. A genuine concurrent-edit race here is a raw
  exception, not a flash message. **Known gap, still open.**

**2. `setupConfirmed` now unifies THREE locks into one — built this session, on top of the column a
teammate added.** `Financialsetting.setupConfirmed` (boolean, `DEFAULT false`) is the single source of
truth for whether the Owner has completed the pharmacy's initial setup. Docx description: *"Sau khi
setting xong, sẽ khóa nhóm thuế và quỹ lại"* (once setup is done, lock the tax group and the funds).

- **`FinancialsettingService.isSetupConfirmed()`** — new, reads the flag directly.
- **`isRevenueGroupLocked()`** = `isSetupConfirmed() || taxperiodsnapshotRepository.count() > 0` (OR
  with the pre-existing "any period ever closed" signal, kept for continuity/defensiveness).
- **`isCashSafeBalanceLocked()`/`isBankAccountBalanceLocked()`** = `isSetupConfirmed() ||
  <field> != null` (OR with the pre-existing "already non-null" per-field signal).
- **`saveSettings()`**: while `!setupConfirmed`, the save that completes it must leave the entity
  with a non-null `revenueGroup` (already `@NotNull` on the DTO), `cashSafeBalance`, AND
  `bankAccountBalance` all set — missing either fund throws `IllegalArgumentException` with a
  friendly message and the whole save rolls back (`@Transactional`), nothing partial persists. Once
  all three are present, `setupConfirmed` flips `true` and stays `true` forever (no code ever sets it
  back to `false`).
- **`annualRevenueThreshold1`/`2` are no longer settable through this form at all** — the two
  `<input>`s were removed from `financial-setting.html`, and `saveSettings()` no longer reads them
  from the request; whatever value is currently stored (from `V13`'s seed, 1 tỷ / 3 tỷ) is permanent
  unless someone edits the DB directly.
- **New `SetupConfirmedInterceptor`** (`config/`), registered in `WebConfig` **ahead of**
  `PendingShiftInterceptor` (the most fundamental gate runs first) — blocks every authenticated
  request for every role until `isSetupConfirmed()` is true. No exceptions/always-allowed paths other
  than the settings screen itself, `/api/**`, static assets, and auth endpoints (unlike
  `PendingShiftInterceptor`, which still allows `/shift-reports`). Owner → redirected to
  `/owner/financial-setting` (editable). Pharmacist/Accountant → redirected to their own read-only
  view (`/pharmacist/financial-setting` is a **new route**, added to the same `GET` mapping
  `/accountant/financial-setting` already had) showing "Hệ thống chưa được thiết lập... chờ Chủ nhà
  thuốc" instead of the Owner's "Chưa hoàn tất thiết lập ban đầu" banner.
- **`WebConfig` gained a `FinancialsettingService` dependency** — every `@WebMvcTest` importing the
  real `WebConfig` needs a `@MockitoBean FinancialsettingService` now (already fixed in
  `NavigationRenderingTest`, `ProductPageControllerTest`, `PermissionControllerTest`).
- **A real bug was found and fixed via live browser testing**: `FinancialSettingPageController.save()`'s
  `bindingResult.hasErrors()` branch re-renders the same view but originally forgot to re-add
  `model.addAttribute("setupConfirmed", ...)` — the template's `th:if="${!setupConfirmed}"` banner
  then threw `SpelEvaluationException: EL1001E: Type conversion problem, cannot convert from null to
  boolean` (Thymeleaf's SpEL `!` on a missing/null model attribute throws, per the existing
  Thymeleaf gotcha already documented below). Fixed by adding the missing `model.addAttribute` call.

**Side effect on the confirm-modal UX**: `financial-setting.html`'s JS confirm dialog now only fires
while `!setupConfirmed`, listing all three fields ("Nhóm doanh thu hộ kinh doanh", "Số dư quỹ tiền
mặt", "Số dư quỹ ngân hàng") as what's about to lock — a single unified warning instead of the old
per-field ones. The submit button's label switches between "Hoàn tất thiết lập" (before) and "Lưu
thiết lập" (after).

**3. Tax formulas for groups 2 and 3 changed — "BA quyết định trực tiếp (chưa có tài liệu)" per the
javadoc, i.e. genuinely undocumented outside the code itself:**

- **Group 3 no longer uses the GTGT deduction method.** Both group 2 and group 3 now pay a flat
  **1% of revenue** (`TaxRevenueGroup.DIRECT_VAT_RATE`) — `vatInput`/`vatCarryforwardIn/Out` are
  permanently zero for every group now. `isDeductionGroup()` is kept only for
  `ReturnPurchaseService`/display purposes and **no longer implies GTGT is actually deducted.**
  **This directly contradicts the "Nhóm 3 (3–50 tỷ): GTGT khấu trừ" line under "Business decisions"
  below** — that line is now stale; treat the replacement bullet there as current.
- **Group 3's PIT rate changed from 15% to `GROUP3_PIT_RATE = 17%`.**
- **Group 2 gained an optional profit-based PIT method** via the previously-unread
  `Financialsetting.taxCalculationMethod` field (pre-existing column, read for the first time here) —
  a period can now be GTGT-flat and PIT-profit simultaneously (new `pitCostMethod` flag on
  `TaxPeriodComputationResponse`, independent of the existing `percentageMethod`).
- **Revenue sourcing changed to fix a double-counting bug**: `InvoiceRepository.findInPeriod` was
  renamed **`findValidInPeriod`** and now excludes superseded originals (an invoice with a `"Thay
  thế"` child, or an unsigned original at `returnStatus='FULL'` with no child ever created) from
  revenue/tax aggregation — previously both an original and its replacement were being summed.
  `InvoicedetailRepository.sumCostOfGoodsSoldInPeriod` got the same filter.
  `ReturndetailRepository.sumRestockedCostInPeriod` was **removed** as part of this — a functional
  refactor (the old design computed COGS then subtracted restocked cost separately; the new design
  excludes superseded invoices at the query source instead), not dead-code cleanup.
- **A separate `taxableIncomeRevenueOf()` method** now feeds TNCN specifically (adds supplier-
  commission Income, employee-liability Income, unknown-origin stock-count surplus cost, plus the
  gross value of `INTERNAL_USE`/`GIFT`/`SAMPLE` stock adjustments) — distinct from the GTGT revenue
  figure.

**Side effect on Invoice**: since GTGT is now computed the same way (flat, direct-on-revenue) for
groups 2 and 3, `InvoiceService.setInvoiceType()` **always writes `"Bán hàng"` now** — the
`isDeductionGroup()`-driven `"Hóa đơn GTGT"` branch was removed, and `resolvePattern()`'s
`kindPrefix` is hardcoded `'2'` (never `'1'`). No separate VAT-invoice document type is issued for
new sales any more. `ReturnService.INVOICE_TYPE_VAT` recognition (added `0f52652`, "add invoice type
group 3 into return") is now a backward-compat path for pre-`ef633bf` rows only.

## 🔴 Read fourth: Income lost its approval gate; a ShiftReport double-counting bug is fixed; Accountant can now see shift reports

**Income (`f5fba58`, "update logic income with role accountant and pharmacist"):** the old rule —
Owner's slip auto-completes, anyone else's goes to `STATUS_PENDING` awaiting Owner approval — is
**gone**. `createIncome()` dropped its `isOwner` parameter entirely; **every submitted income slip now
auto-completes regardless of role.** `STATUS_PENDING` and its counters/filters still exist in the code
(dead/legacy paths for old rows) but nothing new can reach that status any more. The role-conditional
"Gửi duyệt" button on `create-income.html` was deleted; only the Owner-style "Hoàn thành" submit
remains for everyone. **This makes the "Two-tier draft → submit → approve exists in Expense, Income
and ShiftReport" convention (below) stale for Income specifically** — Income kept draft→submit, lost
submit→approve.

**ShiftReport double-counting bug (`2db3ec1`, "fix logic shift report"):** `creditCashSafe(shift)`
used to deposit `actualClosingCash − openingCash` (the *whole* handled-cash figure) into
`cashSafeBalance` on shift approval — double-counting, because cash sales already credit the fund in
real time via `applyFundDelta` at the point of sale (see "Read third"). Fixed to deposit just
`shift.getCashDiscrepancy()` (the actual-vs-expected variance) instead. Confirmed one-way/terminal:
fires only from `closeShift()` (self-approve) and `approve()` (Owner approving a Pharmacist's shift),
both transitions into the terminal `APPROVED` state, so no double-credit risk remains.

**Accountant shift-report read access + cash-shortage collection (`51321d1`, "add view shift report
screen for accountant"):** `ShiftreportController` gained `ACCOUNTANT_BASE = "/accountant/shift-reports"`
mapped on **list + detail GET only** (no close/approve/reject for Accountant) — **this reverses the
"ShiftReport: Owner + Pharmacist" access statement below**, Accountant now has read-only visibility
so they can reconcile cash without running a shift themselves. Bundled in the same feature: a "Thu
tiền thâm hụt quỹ" (collect cash shortage) card on shift detail — when `cashDiscrepancy < 0`, Owner
or Accountant (not the Pharmacist who was short) can create a `SHIFT_SHORTAGE`-type Income slip
against the shortfall, prefilled via new `accountId`/`shiftReportOfAccountId` params on
`IncomeController`'s create route. New sidebar entry "Báo cáo ca" for Accountant.

## 🔴 Read fifth: Purchase Invoice creation moved to the Accountant (when there is one); Owner only duyệt/từ chối

BA request, 2026-08-04: "chủ nhà thuốc vẫn sẽ có chức năng tạo phiếu nhập. NHƯNG nếu có kế toán, thì
kế toán sẽ đảm nhiệm việc đó. Chủ nhà thuốc lúc đó sẽ không cần phải làm việc đó mà chỉ làm việc
duyệt." This is the resolution to the `Purchaseinvoice.approvedAt` risk the last snapshot flagged —
the docx's `Nháp`/`Chờ duyệt` statuses were describing exactly this workflow, just not yet built.
Read this in full before touching `PurchaseinvoiceService`, `PurchaseInvoicePageController`,
`PurchaseInvoiceStatus`, or `purchase-invoice/{create,detail,list}.html`. **The `/owner/approvals`
screen was deliberately left untouched** — the user said another teammate owns it, and Purchase
Invoice approve/reject lives entirely on its own detail page, not aggregated there.

**1. Who may create is now a live, role-conditional question — same shape as "who closes a tax
period" (`TaxperiodsnapshotService.canClose`).** New `PurchaseinvoiceService.hasActiveAccountant()` /
`canCreatePurchaseInvoice(String role)`:

- **Accountant** — may always create (reachable at all only implies the `/accountant/**` URL prefix
  passed, which already implies an enabled Accountant account).
- **Owner** — may create directly **only while there is no active Accountant**. The moment one
  Accountant account is enabled, `/owner/purchase-invoices/create` redirects to the list with
  `"Đã có Kế toán phụ trách tạo phiếu nhập — vui lòng vào phiếu \"Chờ duyệt\" để duyệt."` (checked both
  server-side on the route and via the `canCreate` model flag that hides the list's "Tạo phiếu nhập"
  button).
- **Pharmacist — lost create entirely, no longer conditional on anything.** The old
  `/pharmacist/purchase-invoices/create` GET/POST mappings (and the Pharmacist-only
  `procurement-plan-details` AJAX endpoint they used) were deleted outright, not just hidden. This is
  an explicit user decision ("dược không còn được phép tạo phiếu nhập nữa"), not a side effect.

**2. Two entirely different flows depending on who's creating — the class javadoc on
`PurchaseinvoiceService` used to say the old 3-step flow (create → duyệt → tạo lô) was permanently
"gộp thành 1 use case"; that is now only true for the no-Accountant path:**

- **No active Accountant → Owner's `createPurchaseInvoice()`, unchanged 1-step behavior**: `Batch`
  rows are created in the same transaction as before, but it now ALSO stamps
  `approvedAt = LocalDateTime.now()` — the Owner is, in effect, both creator and approver in this
  path, and the column is no longer left null.
- **Active Accountant → Nháp / Chờ duyệt / Duyệt / Từ chối / Xóa, a real multi-step workflow, and
  stock is received ONLY at the Duyệt step:**
  - `createPurchaseInvoiceDraft()` / `createPurchaseInvoiceForApproval()` — the create form's two
    submit buttons ("Lưu Nháp" / "Nộp duyệt"). Both persist the header + `Purchasedetail` lines;
    **neither creates a `Batch` row or touches `SupplierProduct.costPrice`** — `status` is `Nháp` or
    `Chờ duyệt`, `approvedAt` stays `null`.
  - `updatePurchaseInvoiceDraft()` / `submitPurchaseInvoiceDraft()` — a Nháp's own "Sửa phiếu nháp"
    screen (literally the create form pre-filled via `getDraftEditForm()`, reusing the same DTO/
    validation), with the same Lưu Nháp / Nộp duyệt choice. Editing wholesale-replaces the
    `Purchasedetail` rows (delete + re-insert) — safe because a Nháp never has a `Batch` yet.
  - `approvePurchaseInvoice()` — Owner-only, Chờ duyệt → recomputes `status` via the existing
    `resolveInvoiceStatus(totalAmount, paid=0)` (normally `"Nợ"`), stamps `approvedAt`, **and only
    here** calls the same `receiveStockForInvoice()` helper the no-Accountant path uses (creates
    `Batch` rows via `createBatchForDetail`, refreshes `SupplierProduct.costPrice`).
  - `rejectPurchaseInvoice()` — Owner-only, Chờ duyệt → back to Nháp with an appended note
    (`"Bị từ chối: <lý do>"`). **Nothing to reverse**, since stock was never received — this is why
    reject is trivially safe, unlike `cancelPurchaseInvoice()` which has to check every batch is
    untouched before it can reverse anything.
  - `deletePurchaseInvoiceDraft()` — Accountant-only (any authenticated Accountant, not just the
    creator — not restricted further per the user's answer "phiếu lưu dạng nháp có thể xóa được"),
    Nháp-only, hard-deletes the `Purchasedetail` rows then the `Purchaseinvoice` row itself.
  - `cancelPurchaseInvoice()` gained two new guards: throws if the invoice is `Nháp` ("vui lòng xóa
    thay vì hủy") or `Chờ duyệt` ("vui lòng từ chối thay vì hủy") — cancel is now only for a row that
    was actually approved at some point, keeping one clear transition per status instead of two
    overlapping ones.

**3. `Purchaseinvoice.approvedAt` is now nullable — a real, user-approved schema change, not a
workaround.** `@Column(name = "approvedAt")` lost both `@NotNull` and `nullable = false` on the
entity; the live DB column and `docs/context/current-database.sql` were hand-patched to match
(`datetime` now allows `NULL`, confirmed via `DESCRIBE purchaseinvoice`), no Flyway migration, same
pattern this repo always uses for schema drift. This finally makes `Purchaseinvoice.approvedAt`
consistent with every *other* `approvedAt` column in the schema (`Return`, `Expense`, `Shiftreport`,
`Stockcount` were always nullable) — it was the odd one out before.

**4. A real bug was found and fixed via live browser testing, not just unit tests**: the "Sửa phiếu
nháp" edit form's "Nộp duyệt" button silently did nothing — no network request at all, no console
error. Root cause: `PurchaseInvoiceCreateRequest.vatInvoiceDate`/`dueDate` and
`PurchaseInvoiceDetailCreateRequest.productionDate`/`expirationDate` had no `@DateTimeFormat`, so
Thymeleaf's `th:field` rendered a pre-filled `LocalDate` into `<input type="date">` using the request
locale's short format (`"8/4/26"`) instead of the ISO `yyyy-MM-dd` that input type requires — the
browser silently discarded the malformed value, the date field rendered empty, and this repo's own
`fragments/field-validation.html` submit-guard (capture-phase, blocks on any invalid visible required
field) correctly refused to submit a form with a blank required date. Invisible on the CREATE form
because that one starts with an empty date the user types by hand; only surfaced once an existing
`LocalDate` got pre-filled, which only the new edit screen does. Fixed by adding
`@DateTimeFormat(pattern = "yyyy-MM-dd")` to all four fields.

**5. Accountant gained Product list/detail sidebar access** — `/accountant/products` and
`/accountant/products/{id}` were already served by the existing `ProductPageController` role mapping
(dropped from the sidebar only, in the 2026-07-25 redesign — see "Dropped"); this session just added
the missing "Hàng hóa" `linkGroup` back to `SidebarMenuService.accountantMenu()`. No controller/
service change needed.

**`PurchaseinvoiceServiceTest`**: 53 → **70** tests (canCreatePurchaseInvoice ×4, owner-direct-now-
stamps-approvedAt, draft/pending-create ×2, approve ×2, reject ×2, delete-draft ×2, submit/update-draft
×2, cancel-guards-Nháp/Chờ-duyệt ×2). **Verified live, end to end, both roles**: Owner blocked/redirected
when an Accountant is active (confirmed via the seeded `thinhvc04`/`hoangnv05` Accountant accounts,
both enabled); Accountant create → Lưu Nháp → list shows "Nháp"; Owner opens it, sees no
approve/reject (not Chờ duyệt yet); Accountant Nộp duyệt directly on a fresh create → "Chờ duyệt";
Owner Duyệt → batch created (confirmed via DB `SELECT`, `storageQuantity` matches ratio × import qty),
`approvedAt` stamped, status "Nợ"; separately, Accountant edited a Nháp and Nộp duyệt from the edit
screen (this is where the date bug was caught and fixed), Owner Từ chối with a reason → back to
"Nháp" with the note visible, zero batches created; Accountant Sửa lại → Xóa phiếu nháp → gone from
the list. Left the dev DB with only the seed data afterward (deleted every throwaway invoice/batch/
detail this session created).

## Recent work

**My own (`hoangnv04`) work, this session (2026-08-04) — Purchase Invoice approval workflow (see "🔴
Read fifth" above for the full mechanism):**

- `PurchaseInvoiceStatus.PENDING_APPROVAL = "Chờ duyệt"` added alongside the existing `DRAFT = "Nháp"`
  (now actually used for the first time).
- `PurchaseinvoiceService`: `hasActiveAccountant()`/`canCreatePurchaseInvoice()`,
  `createPurchaseInvoiceDraft()`/`createPurchaseInvoiceForApproval()`,
  `updatePurchaseInvoiceDraft()`/`submitPurchaseInvoiceDraft()`, `getDraftEditForm()`,
  `approvePurchaseInvoice()`, `rejectPurchaseInvoice()`, `deletePurchaseInvoiceDraft()`; refactored
  the line-prep/detail-persist/stock-receipt logic out of `createPurchaseInvoice()` into shared
  private helpers so both the direct-Owner path and the new approval path call the identical
  `receiveStockForInvoice()`. `cancelPurchaseInvoice()` gained the Nháp/Chờ duyệt guards.
  `AccountpermissionRepository` added to the constructor.
- `PurchaseInvoicePageController`: Owner's create routes gated behind `canCreatePurchaseInvoice`;
  new Accountant create/edit/delete routes; new Owner approve/reject routes; detail page exposes
  `canApprove`/`canReject`/`canEditDraft`/`canDeleteDraft`; Pharmacist's create routes deleted.
- `purchase-invoice/create.html` reused for both create AND edit, and for both the Owner's
  single-button flow and the Accountant's dual-button (Lưu Nháp / Nộp duyệt) flow, via new
  `formAction`/`editMode`/`dualSubmit` model attributes. `detail.html` gained Duyệt/Từ chối/Sửa
  phiếu nháp/Xóa phiếu nháp buttons + matching confirm modals, all role/status-gated; the existing
  Hủy phiếu nhập button/modal now excludes Nháp/Chờ duyệt (and the accountant list-basePath case).
- `PurchaseInvoiceCreateRequest`/`PurchaseInvoiceDetailCreateRequest`: added
  `@DateTimeFormat(pattern = "yyyy-MM-dd")` to every `LocalDate` field (the bug fix in item 4 above).
- `SidebarMenuService.accountantMenu()`: new "Hàng hóa" `linkGroup` → `/accountant/products`.
- `entity/Purchaseinvoice.approvedAt`: `@NotNull`/`nullable = false` removed (user-approved schema
  change, live DB + `current-database.sql` hand-patched to match).
- `PurchaseinvoiceServiceTest` 53 → **70**; fixed the stale `SidebarMenuServiceTest.
  accountantMenu_matchesTheAgreedGroupOrder` assertion in passing (missing "Báo cáo ca").
- `mvn test` baseline: 519 → **536** run, 6→**5** failures (net −1, the stale-test fix), 29 errors
  unchanged.

**My own (`hoangnv04`) work, this session (2026-08-03 evening) — built the `setupConfirmed` gate:**

- **`FinancialsettingService`**: added `isSetupConfirmed()`; rewrote `isRevenueGroupLocked()`/
  `isCashSafeBalanceLocked()`/`isBankAccountBalanceLocked()` to OR-combine with it;
  `saveSettings()` now requires all three fields together on the confirming save and flips
  `setupConfirmed` on success; stopped reading `annualRevenueThreshold1/2` from the request at all.
- **New `config/SetupConfirmedInterceptor.java`**, registered in `WebConfig` ahead of
  `PendingShiftInterceptor`. New `/pharmacist/financial-setting` route on
  `FinancialSettingPageController` (same `GET` mapping as the existing Accountant one).
- **`financial-setting.html`**: removed the two threshold inputs; added required-field asterisks to
  the fund inputs (only while unlocked); rewrote the confirm-modal JS to a single unified warning
  covering all three fields at once, gated on `!setupConfirmed`; added a role-aware banner
  ("Chưa hoàn tất thiết lập ban đầu" for Owner vs. "Hệ thống chưa được thiết lập... chờ Chủ nhà thuốc"
  for everyone else); submit button label switches between "Hoàn tất thiết lập"/"Lưu thiết lập".
- **Found + fixed a real bug via live browser testing**: `FinancialSettingPageController.save()`'s
  validation-error branch didn't re-add `setupConfirmed` to the model, causing a `SpelEvaluationException`
  (null→boolean) on the template's `!setupConfirmed` banner check whenever a form submission failed
  Bean Validation.
- **`FinancialsettingServiceTest`**: 24 → **31** tests (added first-save-missing-fund-throws ×2,
  full-confirm-flow, already-confirmed-ignores-posted-values, `isRevenueGroupLocked`/fund-locked when
  `setupConfirmed` true, and a test proving `annualRevenueThreshold1/2` are never overwritten).
  Updated `NavigationRenderingTest`/`ProductPageControllerTest`/`PermissionControllerTest` to mock the
  new `WebConfig` → `FinancialsettingService` dependency.
- **Verified end to end in the running app, not just unit-tested**: block/redirect for both Owner and
  Pharmacist, required-field rejection (confirmed via direct DB check — no partial save), the confirm
  modal's exact wording, successful confirm → all three fields lock together → free navigation
  afterward, and the role-specific messaging on the read-only view.
- `mvn test` baseline: 512 → **519** run, same 6 failures/29 errors as before this build.

**Teammate work, same window** (`nguyentruong16`, commits `dbce767`/`372f387`/`c03b649`/`c869490`/
`3894769`, all 2026-08-03 evening): **`Purchaseinvoice.approvedAt`** added, required, unwired at the
time — the headline risk this snapshot flagged, since resolved and wired up properly on 2026-08-04,
see "🔴 Read fifth". Also: small cosmetic JS
refactor on Procurement Plan's supplier-grouping sort (early-return → if-block, no behavior change);
Procurement Plan print gained `stockUnit`/`unitConversionHint`/`units` response fields and a
CSS/colgroup rework of the print table (percentage column widths instead of fixed pixels, better text
wrapping) — purely display; Invoice Create gained an inline "unit conversion chain" hint (e.g. "1
Hộp = 10 Vỉ = 100 Viên") shown next to each product in search results and the cart, client-side JS
only, no server change.

**Teammate work, 2026-08-02 evening → 2026-08-03 (`3753922`, ~40 commits merged from `develop`,
authors nguyentruong16/DO NGOC DUC/Vu Cuong Thinh — this is a large batch, summarized module by
module):**

- **Accountant Dashboard** (`13111a4`) — closes the long-standing Known-gap. New
  `AccountantDashboardService` (1253 lines) + `AccountantDashboardResponse` (246 lines), route
  `/accountant/dashboard`, new template `dashboard/accountant-dashboard.html` (1018 lines). 6 metric
  cards (phiếu chi chờ duyệt, công nợ còn lại, thu hôm nay, hóa đơn chưa thanh toán, phiếu nhập trong
  ngày, hóa đơn VAT cần xử lý), a weekly Thu/Chi/Công nợ chart, up to 6 alerts, up to 8 recent
  activities, 4 quick actions. **`PlaceholderController` is now fully deleted** — 0 routes, not "down
  to 1". **🟠 Scalability note**: every section is computed fresh on every page load via
  `findAllWithRelations()` on Invoice/Income/Expense/Purchaseinvoice/Return — full-table loads, no
  caching, no new query methods. Harmless on this seed-only dev DB; worth a look if transactional
  volume ever grows. Zero tests.
- **Notification system — went from 1 producer to 3, plus pagination** (`1666ba3` + `ee84206`):
  - **New `WorkflowNotificationService`** (700 lines) — fires on Return/Expense/StockCount/ShiftReport
    pending→approved/rejected, and Income created (all 3 transitions except Income, which is
    create-only, matching "Read fourth" above). All the `NotificationType` constants it uses already
    existed as dead vocabulary before this commit; this is what makes them fire for the first time.
    Confirmed callers: `ExpenseService`, `ReturnService`, `ShiftreportService`, `StockcountService`,
    `IncomeService`.
  - **New `InventoryNotificationService`** (522 lines) + **`InventoryNotificationScheduler`** (69
    lines) — a third producer, scheduled: `LOW_STOCK`/`OUT_OF_STOCK` (compares summed active-batch
    storage against `Product.minStock`) and `EXPIRING_BATCH`/`EXPIRED_BATCH` (30-day window). Runs on
    `ApplicationReadyEvent` and every 5 minutes via `@Scheduled(cron = "${app.notification.inventory-scan-cron}")`
    — **`ProjectApplication.java` gained `@EnableScheduling`** for the first time to support this.
  - `TaxRevenueNotificationService` (the previously-sole producer) is **unchanged**, still exists.
  - **List pagination**: `NotificationRepository.searchForAccount()` now returns `Page<Notification>`;
    `NotificationPageController` gained `page`/`size` params (default 10, max 50) with filter-state-
    preserving mark-read/dismiss actions.
  - **Zero tests for any of the three producers**, despite the module roughly tripling in scope.
- **Employee Note — brand-new feature** (`edb41ba` + `f5a9a4d`): the Owner writes free-text notes
  (≤2000 chars) about a specific staff account from a hand-rolled modal on `owner/users.html`; the
  employee reads their own notes read-only on `/profile` under a new "Ghi chú nhân viên" card. Built
  on a **pre-existing** `Employeenote` entity/table (no new migration) — the classic "generated stub
  → real logic" pattern this codebase repeats. **Not tied to the `DESTROY_EMPLOYEE_FAULT`/employee-
  liability gap** — this is a general HR note. `EmployeenoteController` is `@RestController` at
  `/owner/users/{accountId}/notes` (GET/POST/PUT, no DELETE), Owner-only via the `/owner/**`
  URL-prefix gate; `ProfileController` reads notes for the *current* account via the service directly
  (bypassing the URL gate), which is how a non-Owner sees their own. Fires an `EMPLOYEE_NOTE_CREATED`
  notification. Zero tests.
- **Financial Setting fund reconciliation + tax formula overhaul** — see "🔴 Read third" above in full.
- **Income role-gate removal + ShiftReport bug fix + Accountant shift-report access** — see "🔴 Read
  fourth" above in full.
- **Invoice / Print / Return** (`a512f62`, `f2975ae`, `5d77b06`, `0f52652`, `b7e492b`, `44e8268`):
  - `InvoiceService` (+327 lines): the fund-reconciliation hook (Read third), invoice-type/numbering
    changes (Read third), a new server-side prescription-drug gate
    (`isPrescriptionProduct()`/`invoiceContainsPrescriptionProduct()`), and a full print-DTO rewrite
    (buyer legal block, `moneyAmountInWords()`, tax-authority code builder).
  - `templates/invoice/print.html` — near-total rewrite (+570 lines): a formal Vietnamese e-invoice
    layout. No QR code.
  - `templates/invoice/create-invoice.html` — client-side mirror of the server prescription gate;
    product search switched from substring `includes()` to prefix `startsWith()`.
  - `templates/invoice/invoice-detail.html` — 🔵 fixed the `returnStatus = FULL` display bug (a new
    "Phần giữ lại (không hoàn)" badge). Also added an "Xem hình ảnh hóa đơn" preview modal.
  - **`InvoiceServiceTest.java` exists but has only 2 tests**; no `ReturnServiceTest` /
    `ReturnPurchaseServiceTest` exist at all — both remain zero-tests / top risk despite new logic.
- **Procurement plan** (`c152560` + `120d9ba`): search switched from substring to **prefix match**;
  new combo-product exclusion; detail-line UI now groups rows by supplier.
- **Customer required-fields tightening** (`51ac42b`): `taxCode`/CCCD **and `address` mandatory** for
  every customer, bank account number + name mandatory for company customers.
- **Stock Count** (`bd6d3a1`): replaced native `confirm()`/raw alert divs with the standard toast/
  confirm fragments. `StockcountService` gained `WorkflowNotificationService` integration.
- **Search behavior change on Invoice Create** (`120d9ba`): product search now **gates prescription-
  required products out of results/cart entirely** unless a valid prescription code is entered.

**My own (`hoangnv04`) work, 2026-08-02 — Product/Purchase Invoice UI pass + Financial Setting fund
fields (462 → 491 tests):**

- **Product Detail** — `minStock`/`maxStock` now append the base unit's name; the unit-conversion
  table's ratio column renders relative to the base unit. New `ProductDetailResponse.baseUnitName`.
- **Product List** — prescription display fixed; new "sắp hết hạn" filter; single `keyword` box
  became an expandable 4-field search.
- **Product Create** — `minStock`, `maxStock`, `producerId`, `origin`, `typeId` are now required.
- **Purchase Invoice List/Detail/Create** — expandable search, date-preset dropdown, a read-only
  "Giá bán (đơn vị nhập)" reference column/line, keyed to the import unit not the base unit.
- **Financial Setting** — `cashSafeBalance`/`bankAccountBalance` became editable manual fields
  (**reversed the very next day** — see "Read third", they're auto-reconciled now).
- Test counts: `ProductServiceTest` 70 → **86**, `PurchaseinvoiceServiceTest` 44 → **53**,
  `FinancialsettingServiceTest` 9 → **13**. `mvn test` baseline 462 → **491** run.

**My own (`hoangnv04`) work, 2026-08-01 — tax group transitions are now automatic:**

- **🔴 Reverses the "group changes are manual" rule.** `TaxperiodsnapshotService.autoNextGroup()` +
  `applyAutomaticGroupTransition()` now decide `nextPeriodTaxType` from revenue vs. the two
  thresholds. 1→2 applies retroactively; 2→3 only takes effect starting next calendar year.
- `TaxperiodsnapshotServiceTest` 94 → **106**, `FinancialsettingServiceTest` 6 → **9**.

**My own (`hoangnv04`) work, 2026-07-31 evening → 2026-08-01 — comment cleanup, six modules.** No
logic changes.

**Teammate work, 2026-07-31 evening** (`nguyentruong16`/`duc`, PRs #142–#143, 11 commits): `@Version`
on `Invoice`+`Purchaseinvoice`; **`DebtOffsetService`** (new, 554 lines) manual "Bù trừ công nợ"
screen; password rule tightened; Pharmacist dashboard expanded.

**My own (`hoangnv04`) work, 2026-07-30/31 — the Expense module was reshaped:**

- **🔴 A phiếu chi is now ONE payment, and it cannot be edited.** `markPaid()` and the `fullyPaid`/
  `paid` form fields are gone; `amount == paid` always; approval goes straight to `COMPLETED`.
- **🔴 `GOODS_PAYMENT` ("Thanh toán hàng") split out of `OPERATIONAL`.**
- 77 tests (`ExpenseServiceTest`), rewritten substantially for the new rules.

**Teammate work, 2026-07-31** (`duc`): `@Version` on `Batch`; Customer/Supplier validation;
`PendingShiftInterceptor`; `money-input` gained `data-money-allow-negative`.

**My own (`hoangnv04`) work, 2026-07-30:** Price Settings detail modal; Product identity validation;
fixed the unreachable Notifications screen; `PlaceholderController` trimmed 9→1 route.

**My own (`hoangnv04`) work, 2026-07-27→29:** Tax period (Kỳ thuế) built end to end, 94 tests.
Expense: cash is Owner-only, cash always belongs to a shift. Purchase invoice always created as "Nợ".

**Teammate work, 2026-07-29 evening** (`thinh`/`truong`): Notifications built end to end (superseded/
expanded since), Owner + Pharmacist dashboards, tax revenue-threshold warning.

**Teammate work, 2026-07-27→29** (`duc`/`truong`/`thinh`): debt screen, Stock Adjustment rebuilt,
Income gained employee-reimbursement linking, `Invoice.returnStatus` implemented.

## Business decisions currently in force

- **Single store, exactly 3 roles**: `OWNER`, `PHARMACIST`, `ACCOUNTANT`, stored as a plain
  `accountpermission.role varchar(50)` string. `CHIEF_PHARMACIST` is fully removed.
- **🔴 The app is gated: nobody can use ANY screen until the Owner completes Financial Setting**
  (2026-08-03 evening, see "Read third"). `SetupConfirmedInterceptor` redirects every role to a
  financial-setting view (editable for Owner, read-only for everyone else) until
  `Financialsetting.setupConfirmed = true`. Completing setup requires `revenueGroup` +
  `cashSafeBalance` + `bankAccountBalance` all filled in one save; all three then lock forever,
  together. This is a brand-new, app-wide rule — nothing like it existed before today.
- **A return slip computes, it does not pay.** See "Read second".
- **🔴 A phiếu chi is ONE payment and is immutable (BA 2026-07-30). This REVERSES the old
  "`Expense.amount` is an obligation" rule** — if you find that phrasing anywhere else, it is stale.
  Nợ 500.000 mà hôm nay trả 200.000 thì phiếu đó **là** 200.000; hôm sau trả nốt 300.000 là **một
  phiếu chi khác**. Consequences, all load-bearing:
  - `amount == paid`, always. `fullyPaid`/`paid` are gone from `ExpenseCreateRequest`.
  - **`markPaid()` is deleted** — no endpoint, no button. Topping up means a new slip.
  - Approval goes straight to `COMPLETED`. `ExpenseStatus.AWAITING_PAYMENT` is never produced.
  - **A `COMPLETED` slip CAN be cancelled**, reversing the money via `applyPayment(-disbursed)` and
    `financialsettingService.adjustFundBalances()` in the positive direction.
  - `resolveAmount()` honours the posted amount, capped at what the document still owes.
  - **Multiple slips per document, for refunds too.**
- **🔵 A phiếu chi carries NO debt state — deliberately.** Nợ is a property of the **document**
  and the **Debt screen's** job, not the Expense's.
- **🔵 Only the Owner pays an Expense in cash.** An Accountant settles by transfer, holds no float,
  has no shift.
- **🔴 A purchase invoice's creation flow now depends on whether the pharmacy has an active
  Accountant (2026-08-04) — REVISES the old universal "always created as 'Nợ', no approval step"
  rule.** No active Accountant: Owner creates directly, still one step, still lands on `paid = 0` /
  `"Nợ"` immediately — that half of the old rule is unchanged. Active Accountant: the Accountant
  creates a Nháp or submits straight to Chờ duyệt, **no stock is received and no debt exists until the
  Owner duyệt** (or it's rejected back to Nháp, or deleted outright while still Nháp). Pharmacist can
  no longer create at all. See "🔴 Read fifth" for the full mechanism.
- **Money counts as disbursed only once a slip is approved** — `ExpenseService.disbursedAmount()`.
- **🔴 `GOODS_PAYMENT` ("Thanh toán hàng") is the ONLY type that links a purchase invoice** (BA
  2026-07-31, reverses the earlier "everything purchase-related is `OPERATIONAL`" rule).
- **"Chi phí vận hành" has no sub-types.**
- **🔴 Tax: quarters only, group changes are automatic, and groups 2/3 share the SAME GTGT method —
  this REVISES the earlier "group 3 uses deduction" statement.** Nhóm 1 (<1 tỷ) **miễn thuế hoàn
  toàn**. Nhóm 2 (1–3 tỷ) **and Nhóm 3 (3–50 tỷ) both pay GTGT = 1% doanh thu**
  (`TaxRevenueGroup.DIRECT_VAT_RATE` — deduction/khấu trừ method is no longer used by anyone;
  `isDeductionGroup()` survives only for `ReturnPurchaseService`/display, it no longer implies actual
  deduction). TNCN: Nhóm 2 = 0.5% doanh thu **by default, with an optional profit-based method** now
  selectable via `Financialsetting.taxCalculationMethod`; Nhóm 3 = **17%** thu nhập chịu thuế (was
  15% — `TaxRevenueGroup.GROUP3_PIT_RATE`) = doanh thu − giá vốn hàng bán − chi phí vận hành. Group 4
  is out of scope. Chuyển nhóm: 1→2 áp dụng ngay từ quý vượt ngưỡng, retroactively rewriting the
  previous closed period; 2→3 giữ nhóm cũ đến hết năm, only auto-applies at the Q4 close of the year
  the threshold was crossed. No scheduler exists — see Known gaps.
- **🔴 Income has NO approval gate for any role, as of 2026-08-03 — REVERSES the earlier "Owner
  auto-completes, everyone else needs approval" rule.** Every submitted phiếu thu auto-completes;
  `STATUS_PENDING` is dead vocabulary for new rows, kept only to render old ones. See "Read fourth".
- **🔴 Financial Setting's `cashSafeBalance`/`bankAccountBalance` are auto-reconciled from real
  transactions.** See "Read third" for the full `applyFundDelta`/`adjustFundBalances` mechanism.
  Each field, plus `revenueGroup`, now locks together the moment `setupConfirmed` becomes true — see
  the new app-wide setup-gate bullet at the top of this list.
- **🔵 `Invoice.invoiceType` is always `"Bán hàng"` now** — the old group-3 `"Hóa đơn GTGT"` branch
  was removed along with the tax-formula change above. Invoice numbering also lost its `"HD"` prefix —
  it's a bare zero-padded 8-digit number now. `ReturnService.INVOICE_TYPE_VAT` still recognises the
  old value for pre-`ef633bf` rows only.
- **🔵 Prescription-drug gating is now actively enforced, not just validated at submit.**
  `InvoiceService.invoiceContainsPrescriptionProduct()` blocks a sale server-side if the cart has an
  Rx product without a valid code; `create-invoice.html` mirrors this client-side.
- **🔵 Customer required fields expanded: `taxCode`/CCCD AND `address` are now mandatory for every
  customer**, and bank account number/name are mandatory for company customers — **narrower than, and
  separate from**, the pre-existing uniqueness-checking rule below.
- **🔵 Accountant has read-only access to ShiftReport** — `/accountant/shift-reports`, list + detail
  only, no close/approve/reject. **Reverses "ShiftReport: Owner + Pharmacist" only.** Paired with a
  "collect cash shortage" flow: Owner or Accountant (not the short Pharmacist) can raise a
  `SHIFT_SHORTAGE` Income slip against a shift's negative `cashDiscrepancy`.
- **Price Settings is a direct `Productunit.sellPrice` editor**, explicitly not a markup calculator.
  Money on that screen carries **no decimals** (HALF_UP to whole đồng); other modules keep 2.
- **Base-unit price cascade infers customization from formula agreement** — no DB flag.
- **`Batch.importPricePerBase` is GROSS** (VAT-inclusive). Confirmed team convention.
- **`Return.appliedRefundRate`** is hardcoded to `100.00` — V1 always refunds 100%.
- **"Ký hóa đơn" is a status flip that also mutates `invoicePattern`** (K→C), not a signature.
- **Shifts are created lazily on the first real sale or the first cash payout**, never at login, and
  only for Owner/Pharmacist. **Accountant never gets a shift** (but can now read others' — see above).
- **Who closes a tax period**: the Accountant; the Owner only when no enabled `ACCOUNTANT` account
  exists.
- **🔵 Stock Adjustment has 7 types and three overlapping-but-separate rule sets.**
  `EMPLOYEE_LIABLE_TYPES = {DESTROY_EMPLOYEE_FAULT, COUNT_DECREASE}`;
  `VAT_OUTPUT_TYPES = {INTERNAL_USE, GIFT, SAMPLE}`; `NO_EXPIRED_GOODS_TYPES` — same list as
  `VAT_OUTPUT_TYPES` on purpose but a separate constant. Don't merge them.
- **🔵 A completed Stock Adjustment can only be cancelled while its batches are untouched.**
- **🔵 Surplus of unknown origin becomes a NEW batch.**
- **🔵 Employee reimbursement is an Income like any other**, not its own shift-report line.
- **🔵 Return-Purchase VAT: groups 1/2 write zeros on the lines too, not just the total.**
- **🔵 Product identity: tên là bắt buộc + duy nhất; barcode và số đăng ký chỉ kiểm khi có nhập.**
  Bỏ trống = "chưa khai báo", checked even when blank so two blank rows still collide.
- **🔵 Product identity (2026-08-02): `minStock`/`maxStock`/`producerId`/`origin`/`typeId` required.**
- **🔵 Purchase Invoice's "Giá bán" reference is the đơn vị nhập (import unit), never the base
  unit, and is display-only.** Nothing on this screen writes back to `Productunit.sellPrice`.
- **🔵 Customer/Supplier uniqueness: the service layer is still the real gate; the live DB ALSO has
  a backstop** (`supplier.phone`/`.email`/`.taxCode`, `customer.phoneNumber`/`.taxCode` UNIQUE
  indexes, hand-added, not declared on the entities so Hibernate is unaware). A race past the service
  check surfaces as a raw `DataIntegrityViolationException`, not the friendly per-field message.

## Dropped / de-scoped — do NOT rebuild without an explicit request

- **`PlaceholderController` is fully deleted (2026-08-02/03), not just trimmed.** Every menu item —
  including the Accountant's `Tổng quan`, the last holdout — now resolves to a real controller.
- **`Return.refundCash` / `refundBanking` / `refundCredit`** — dropped by design.
- **`Return.expenseID` / `incomeID`** — dropped earlier (`38515f8`). `Expense.returnID` is now the
  only link direction, which is why the duplicate guard must read from the Expense side.
- **`ShiftReport.approvedBy`** — the physical column is gone from the DB too.
- **`StockAdjustment.createdBy` / `approvedBy` / `approvedAt`** — all three dropped from entity AND
  live DB on 2026-07-29. **Do not confuse this with `Purchaseinvoice.approvedAt`** (see "🔴 Read
  fifth") — different entity, and that one is now a real, wired-up approval-timestamp field.
- **`Branch` entity/repository/service/controller are deleted.** No `branchID` column anywhere.
- **`CHIEF_PHARMACIST`** is not a selectable role.
- **`ProductWarranty`, `DailyReport`** — absent from the codebase entirely.
- **Tax group 4 (>50 tỷ, monthly periods) and its 20% TNCN** — out of scope.
- **Stock Adjustment's approve/reject workflow** — removed 2026-07-28.
- **`ExpenseService.markPaid()` + the `/…/expenses/{id}/mark-paid` endpoint** — deleted 2026-07-30.
- **`ExpenseCreateRequest.fullyPaid` / `.paid`, and the "Đã chi đủ" checkbox** — same change.
- **A "Tình trạng công nợ" column on Expense** — built and removed the same day.
- **"Ngày chi" on the Expense create form** — removed 2026-07-30.
- **Price Settings as a markup calculator** — pivoted away from deliberately.
- **🔴 Income's `STATUS_PENDING`/approval gate for non-Owner roles (2026-08-03)** — every submission
  now auto-completes; the status constant and its counters are dead code for new rows, kept only to
  render pre-2026-08-03 slips. Do not build a new "submit for approval" UI for Income without asking.
- **The `"Hóa đơn GTGT"` invoice-type value for new sales (2026-08-03)** — every new sale invoice is
  `"Bán hàng"` now; the old value only appears on pre-`ef633bf` rows.
- **`Financialsetting.annualRevenueThreshold1`/`2` are no longer settable via the UI (2026-08-03
  evening)** — removed from `financial-setting.html`; fixed at their `V13`-seeded values.
  `revenueGroupConfirmed` (a briefly-shipped, unapproved column, distinct from `setupConfirmed`) was
  removed entirely earlier the same day — don't confuse the two if you see either name mentioned.
- **Sidebar entries removed in the 2026-07-25 redesign** (screens still work by direct URL):
  Owner `Danh sách hóa đơn VAT`, `Báo cáo tổng hợp theo ngày`; Pharmacist `Loại hàng`,
  `Nhà sản xuất`, `Danh sách vị trí`, `Nhà cung cấp`, `Danh sách phiếu nhập`, `Điều chỉnh kho`;
  Accountant `Loại hàng`, `Nhà sản xuất`, `Nhà cung cấp`, `Thiết lập tài chính`,
  `Báo cáo ngày`, `Hóa đơn VAT`. **Accountant `Hàng hóa` was re-added to the sidebar 2026-08-04** —
  no longer in this dropped list, see "🔴 Read fifth".
- **Pharmacist's Purchase Invoice create access, and its create-only `procurement-plan-details` AJAX
  endpoint (2026-08-04)** — `/pharmacist/purchase-invoices/create` (GET+POST) deleted outright, not
  hidden. Explicit user decision, not a side effect — do not re-add without asking. List/detail/print
  access for Pharmacist is unaffected. See "🔴 Read fifth".

## Tech stack & hard constraints

- **Spring Boot 4.0.6, Java 25, Maven** (`./mvnw`). **Lombok** in use.
- **MySQL** `hang_ngoc_pisms` @ `localhost:3306` (user `root` / pwd `123456`; datasource URL
  includes `?serverTimezone=Asia/Ho_Chi_Minh`). **Flyway** migrations `V1`–`V15`, but `V1` is empty
  and only `V15` alters a table. `ddl-auto = validate` → the DB schema is authoritative.
- **`spring-boot-starter-validation` is on the classpath** — Hibernate ORM auto-validates
  entity-level Jakarta Bean Validation annotations (like `@NotNull`) on persist. This is exactly why
  `Purchaseinvoice.approvedAt` briefly being `@NotNull` with no service-layer writer (2026-08-03,
  resolved 2026-08-04, see "🔴 Read fifth") was a real, not hypothetical, risk — this is how the
  framework actually behaves here. Keep it in mind before adding any new `@NotNull` entity field.
- **`@EnableScheduling` is present on `ProjectApplication`** (2026-08-03, for
  `InventoryNotificationScheduler`) — the first scheduled job in the app. Tax-notification/
  group-transition checks still have no scheduler of their own; don't assume this fixed that gap.
- **🔵 `ensureOpenShiftFor()` is role-guarded** — returns `null` for an Accountant instead of handing
  them a shift. `ExpenseService.attachOpenShift()` uses `ensureOpenShiftFor` for cash slips,
  `findDraftShift` for banking-only ones. The stamp uses the **creator's** shift.
- **🔴 Every write to `Purchaseinvoice.paid` must re-derive and store `status` in the same
  transaction.** `applyPayment()` is the only place `paid` changes after create.
- **Thymeleaf 3.1** — `#request`/`#session`/`#httpServletRequest` are NOT available in templates.
  **`th:inline` JS blocks must never embed a raw JPA entity** with an uninitialized lazy
  `@ManyToOne`/`@OneToMany`. Plain `Map<Integer, X>` projections are the established safe pattern,
  and **`th:inline` DTOs need pre-formatted `String` dates**. **`th:if` combining two separate
  `${...}` blocks with a literal ` and ` is invalid** (must be one `${a and b}`), **a ternary must
  not nest `${...}` inside its branches**, and **SpEL's `!` on `null` throws** (`${x == null}` is
  the safe null-check — confirmed again this session, see "Read third"'s bug-fix note).
  **`#fields` must be inside the `<form th:object=...>` scope.**
  **Spring-EL selection (`list.?[...]`) is unreliable when the predicate references an outer
  `th:each` variable** — use a `Map<Integer, X>` lookup.
- **`#numbers.formatDecimal(v, 0, ...)` drops the leading zero** — `0.5` renders as `,50`. Pass `1`
  for the minimum-integer-digits argument when the value can be below 1.
- **A `th:field` keeps an explicit `id` attribute** if you write one.
- **🔴 A `LocalDate` request-DTO field needs `@DateTimeFormat(pattern = "yyyy-MM-dd")` before it's ever
  pre-filled into an `<input type="date">` via `th:field`** — without it, Spring renders the value
  using the request locale's short format (e.g. `"8/4/26"`), which `<input type="date">` silently
  rejects, leaving the field looking empty even though the model has a real value. Invisible on a
  blank create form (nothing to pre-fill yet); only surfaces once an existing row's date gets edited —
  found via `PurchaseInvoiceCreateRequest.vatInvoiceDate` on the new "Sửa phiếu nháp" screen (🔴 Read
  fifth), where it silently blocked submit via this repo's own `field-validation.html` guard.
- **Thymeleaf exceptions firing mid-stream produce `net::ERR_INCOMPLETE_CHUNKED_ENCODING`**, not an
  error page. Blank page / FAILED GET with status 200 → check the server log for
  `TemplateInputException`/`SpelEvaluationException` first.
- **🔴 Two `@Controller`s mapping the same path = `IllegalStateException: Ambiguous handler methods
  mapped for '…'` — a 500 on EVERY request to that URL, and the app still starts cleanly.**
  **Whenever a real screen takes over a placeholder URL, delete the placeholder entry in the same
  change.**
- **Every page has a hidden logout `<form>` in the topbar, rendered before the page's main form.**
  Never blindly grab `document.querySelector('form')` — scope by id or by the form's `action`.
- **Logout posts to `/logout-guard`, not `/logout`.**
- **Spring Security IS present** (`config/SecurityConfig`). Login posts to `/signin`
  (`loginId`+`password`). Role trees are gated by URL prefix (`/owner/**`, `/pharmacist/**`,
  `/accountant/**`). CSRF is ON; MockMvc POST tests need `.with(csrf())`. **One concurrent session
  per account.** **A devtools restart invalidates all browser sessions.**
- **Generated `@RestController`s** — **do NOT modify**. Real screens are separate `@Controller` page
  controllers; the paired generated `@Service` **is** where to extend a stub into real logic.
  **Exceptions: `IncomeController` and `EmployeenoteController`** were converted/built directly.
- **Entity naming quirk:** multi-word tables get flat lowercase-after-first-letter class names —
  `Stockadjustment`, `Purchaseinvoice`, `Shiftreport`, `Financialsetting`, `Taxperiodsnapshot`,
  `Employeenote`, … When grepping, try both casings. **`Product`'s PK field is `productID`, not `id`.**
- **`accountpermission.accountPermissionID`** is `@Id` only though the column is AUTO_INCREMENT —
  inserts assign the PK via `findMaxId()+1`.
- **`Supplierproduct` has no JPA-level `@UniqueConstraint`** despite a DB unique index.
- **`Purchaseinvoice.isValidForDeduction` is recomputed on every read** and the stored column is not
  trusted.
- **🔵 `Invoice.returnStatus` is implemented and is only a CACHE.** `ReturnService` writes
  `NONE`/`PARTIAL`/`FULL`; `invoiceReturnCode()` recomputes it on every read and is the source of
  truth. `Purchaseinvoice` has its own separate `returnStatus`; do not confuse the two.
- **`InvoiceDetailCreateRequest.unitSellPrice` naming trap**: "Đơn giá" is hard-locked to
  `Productunit.sellPrice`, the javadoc's "optional override" claim is not honored.
- **`Invoice.invoicePattern` vs `invoiceNumber`**: `invoicePattern` is a 7-char type/series prefix,
  not unique, mutable after creation. `invoiceNumber` is the identifier, and is a bare 8-digit
  zero-padded number with no `"HD"` prefix (see Business decisions).
- **🔴 Date-storage is inconsistent (3 variants)** — `LocalDateTime`, real `Instant.now()`, and
  `Instant` via a `nowVn()` hack. `TaxperiodsnapshotService` has package-private helpers
  (`localStart`/`localEndExclusive`/`instantStart`/`instantEndExclusive`) that express one period in
  both styles — reuse them. Bounds are half-open `[start, end)`.
- Static assets `src/main/resources/static/assets` — do not edit. Theme's Bootstrap `--bs-primary`
  is orange `#E66239`; the app's real primary is green `#059669`.
- **Cloudinary** — Product photo upload. Config keys are env-var-backed. Never hardcode secrets.

## Build / test / run (Windows, PowerShell)

`JAVA_HOME` on this machine points to JDK 20; the project needs **JDK 25**. Override per command:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'; .\mvnw.cmd -B --no-transfer-progress -o -DskipTests compile
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'; .\mvnw.cmd -B --no-transfer-progress -o test
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'; .\mvnw.cmd -B --no-transfer-progress -o '-Dtest=TaxperiodsnapshotServiceTest,ExpenseServiceTest' test
```

- Quote `-Dtest=A,B` in PowerShell (the comma is parsed otherwise).
- Spring Boot 4 moved the slice annotations:
  `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` / `...AutoConfigureMockMvc`.
- **`ProjectApplicationTests` is `@SpringBootTest`, needs MySQL running locally — it passes.**
  `ddl-auto=validate` only checks schema *shape*, not nullability semantics, so this test alone never
  proved `approvedAt` was safe to use either way — that needed the live end-to-end run under "🔴 Read
  fifth" (2026-08-04), which is what actually confirmed the now-nullable column works correctly.
- **To run the app**: `.claude/launch.json` defines a `spring-boot` config pointing at
  `run-dev.cmd`. Use `preview_start`/`preview_stop`, not raw `mvn spring-boot:run`. **devtools
  auto-restart is active for CLASS changes** — **template-only edits need `mvn resources:resources`**.
  **A devtools restart invalidates the browser session** — expect to log back in. **`preview_stop`
  does not reliably kill the process** — verify with `Get-NetTCPConnection -LocalPort 8080`.
- **Browser-tool navigation often lags one request behind** — re-issue the same `navigate` with
  `force: true` before assuming something is broken.
- **Seed logins** (same bcrypt hash): `ngoctn01` (Owner), `hangvt02`/`vuta03`/`truongnd06`/`ducdn07`
  (Pharmacist), `thinhvc04`/`hoangnv05` (Accountant). Password **`12345678`**.
- **The dev DB is empty of transactional data** (`Product` has 20 rows). Insert throwaway rows to
  exercise a feature, then delete them. **Use `--default-character-set=utf8mb4` on the mysql CLI** or
  Vietnamese status strings insert mangled. The client lives at
  `C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe`.
- **`financialsetting` currently has `setupConfirmed=1` with `cashSafeBalance=5,000,000` /
  `bankAccountBalance=20,000,000`** (set during this session's live verification) — the app is
  usable end to end right now, not stuck at the onboarding gate.

## Testing rules (important)

- **Every new feature gets unit tests, passing, before moving on** — the rule for `hoang`-role work.
  Teammate commits do not consistently follow it: Accountant Dashboard, Employee Note,
  `WorkflowNotificationService`, `InventoryNotificationService` all landed with **zero tests**.
  `Purchaseinvoice.approvedAt` originally landed (teammate commit, 2026-08-03) with zero tests AND
  zero service wiring — since fixed properly, with 17 new tests, on 2026-08-04 (see "🔴 Read fifth").
- **🔴 NEVER bulk-edit a test file with `Get-Content -Raw` + `Set-Content`.** PowerShell 5.1 reads
  with the ANSI codepage, so every Vietnamese string in the file is destroyed on write. These files
  are git-ignored, so there is no version to restore from. Use the Edit tool for these files.
- **`mvn test`: 536 run, 5 failures, 29 errors — the accepted baseline** (was 519/6/29 on
  2026-08-03, before this session's Purchase Invoice approval workflow added 17 tests and fixed one
  stale assertion). Breakdown:
  - `+17` tests: `PurchaseinvoiceServiceTest` 53→**70** (Nháp/Chờ duyệt/duyệt/từ chối/xóa workflow —
    see "🔴 Read fifth").
  - `−1` failure: `SidebarMenuServiceTest.accountantMenu_matchesTheAgreedGroupOrder` fixed in passing
    (was missing the already-shipped "Báo cáo ca" group from its expected list — unrelated staleness,
    just happened to be touched for the new Accountant "Hàng hóa" entry).
  - Everything else unchanged: `SidebarMenuServiceTest` still has 1 stale failure
    (`ownerMenu_includesAbsorbedChiefPharmacistItems`, expects a `/owner/stock-adjustments/create`
    menu item that was never real), `ProductServiceTest`/`ProfileServiceTest` 2 each (stale), and the
    pre-existing 29-error test-context gap: `NavigationRenderingTest` (8) and
    `ProductPageControllerTest` (21), `@Import(SecurityConfig)` without providing
    `ReasonAwareSessionExpiredStrategy`/`SessionExpiryReasonRegistry`. **The real app boots fine**
    (verified live this session, including the new Purchase Invoice flows end to end).
- **Only 5 test files are tracked by git** — `.gitignore` line 34 is
  `/src/test/java/com/example/project`. Tracked: `CustomAccountDetailsServiceTest`,
  `NavigationRenderingTest`, `ProjectApplicationTests`, `OwnerUserServiceTest`, `ProfileServiceTest`.
  Everything else — including all 109 `TaxperiodsnapshotServiceTest` cases — is invisible to
  `git log`/`git show`/a fresh clone.
- **🔴 A teammate's change can break the git-ignored tests, and they cannot see it. Eight instances
  so far** (this session's own `WebConfig` → `FinancialsettingService` addition was caught and fixed
  proactively, not counted as a ninth "broken" instance since it never actually broke anything):
  1. `InvoiceService.createSaleInvoice()` gained an `allowDebt` parameter.
  2. `WebConfig` gained a `ShiftreportService` dependency.
  3. `WebConfig` gained a `NotificationService` dependency.
  4. The sidebar gained a `Thông báo` group for all three roles → `SidebarMenuServiceTest`.
  5. `Invoice`/`Purchaseinvoice` gained `@Version`, saves rerouted through `saveAndFlush` →
     `PurchaseinvoiceServiceTest`/`InvoiceServiceTest` both mocked `.save(...)` only.
  6. `TaxPeriodCloseRequest`/`TaxPeriodUpdateRequest` lost `nextPeriodTaxType`,
     `FinancialsettingService` gained a `TaxperiodsnapshotRepository` param — self-inflicted, broke
     compilation of `TaxperiodsnapshotServiceTest`/`FinancialsettingServiceTest`.
  7. `Taxperiodsnapshot.cashBalanceAtPeriodEnd` → `quarterlyRevenue` rename broke
     `TaxperiodsnapshotServiceTest` at two call sites, the same way it broke `mvn compile` itself.
  8. `InvoiceService`'s constructor gained a `FinancialsettingService` parameter —
     `InvoiceServiceTest.java`'s constructor call was one arg short. Fixed same-session.
  - **When adding `WebConfig` → `FinancialsettingService` this session, all three `@WebMvcTest`s
    that import the real `WebConfig`** (`NavigationRenderingTest`, `ProductPageControllerTest`,
    `PermissionControllerTest`) **were updated proactively**, per rule 2 below — no breakage this time.
- **A ninth way to lose these files, self-inflicted (2026-07-31):** bulk-editing
  `ExpenseServiceTest.java` with PowerShell `Get-Content -Raw`/`Set-Content` destroyed every
  Vietnamese string in it. Recovery worked only by re-decoding (UTF-8 → cp1252 → UTF-8).
- **Four rules, unchanged and still the right ones**:
  1. **Run the full suite after pulling teammate work**, not just the module you touched.
  2. **When you add a dependency to `WebConfig`, mock it in every `@WebMvcTest` that imports it.**
  3. **When you add `@Version` to an entity, grep every git-ignored test that mocks its repository
     for `.save(` and add a matching `.saveAndFlush(` stub/verify.**
  4. **When you change a service's constructor or a request DTO's fields, grep every git-ignored
     test that constructs it or calls the removed setter, before you consider the change done.**
- **Well covered**: `TaxperiodsnapshotService` (109), `ExpenseService` (84),
  `PurchaseinvoiceService` (70 — includes the Nháp/Chờ duyệt/duyệt/từ chối/xóa workflow, "🔴 Read
  fifth"), `PricesettingService` (49), `ProductService` (86, 2 stale), `OwnerPermissionService` (18),
  `SidebarMenuService` (13, 1 stale), `ProductImageStorageService` (11), `FinancialsettingService` (31).
- **Zero tests exist for** (descending risk): `ReturnService` (1492+) / `ReturnPurchaseService`
  (1012+) — top risk, changed heavily again this window; `StockadjustmentService` (1388);
  `IncomeService` (1210+, which just lost its approval gate — see "Read fourth"); `InvoiceService`
  (1480+, only 2 FEFO tests, despite gaining the fund hook, invoice-numbering rewrite, and
  prescription gating this window); `AccountantDashboardService` (1253, brand new);
  `WorkflowNotificationService` (700, brand new); `InventoryNotificationService` (522, brand new);
  `DebtService` (682+); `ShiftreportService` (659+, despite owning cash reconciliation);
  `ApprovalService` (348); `SupplierService` (264) / `CustomerService` (253) — the only guard against
  duplicate rows; `EmployeenoteService` (188, brand new); `NotificationService`/
  `TaxRevenueNotificationService`; `TypeService` / `ProcurementplanService` / `StockcountService`.

## Known gaps, not fixed (see `current-project-state.md` for the full list)

- ~~**`Purchaseinvoice.approvedAt` (`@NotNull`) has no service-layer writer.**~~ **CLOSED 2026-08-04**
  — column is now nullable, `approvedAt` is stamped by `approvePurchaseInvoice()` (Accountant-created
  flow) or immediately at creation (no-Accountant Owner flow). See "🔴 Read fifth".
- **`FinancialsettingService`'s fund-mutating saves have no optimistic-lock guard**, despite
  `Financialsetting` having `@Version` again. `applyFundDelta()`/`adjustFundBalances()` both call
  plain `.save(...)`; a genuine concurrent-edit race surfaces as a raw
  `ObjectOptimisticLockingFailureException`, not a friendly message, unlike the other three
  `@Version`-guarded entities in this codebase.
- **🔴 A `DESTROY_EMPLOYEE_FAULT` slip can never be billed to an employee.**
  `IncomeService.matchesResponsibleEmployee()` tries `adjustment.expenseID.accountID` first, then
  falls back to `stockCountID.createdBy`. But `StockadjustmentService` explicitly writes
  `setExpenseID(null)` on both create paths and `Expense.accountID` is never written by anything, so
  the first branch is dead code.
- **`Dac_ta_Income_StockAdjustment.xlsx` is cited all over `StockadjustmentService` /
  `StockAdjustmentCreateRequest` but is NOT in the repo.**
- ~~**The Accountant has no dashboard.**~~ **CLOSED 2026-08-02/03** — `AccountantDashboardService` +
  `/accountant/dashboard` are real; `PlaceholderController` is fully deleted.
- **Both the revenue-threshold warning and the 1→2 tax-group auto-transition fire only when someone
  opens or closes a tax period.** Still no scheduler for this — the `InventoryNotificationScheduler`
  is scoped only to stock/expiry alerts, it does not cover tax notifications.
- ~~**Financial Setting balances have no auto-reconciliation.**~~ **CLOSED, and now goes further** —
  `applyFundDelta()`/`adjustFundBalances()` auto-reconcile both funds, and `setupConfirmed` now gates
  the whole app until they (plus `revenueGroup`) are set. See "Read third".
- **`Expense.accountID` is never set**, including for `EMPLOYEE_ADVANCE_REPAYMENT`.
- **`StockadjustmentService`'s class javadoc still advertises "the approve/reject workflow"** that
  was deleted.
- **`@InitBinder` for vi-VN money is only on `TaxPeriodPageController`.** Every other `data-money`
  form still returns a raw 400 error page if the separator-stripping JS does not run.
- **Shift totals ignore Pending expenses in practice** — unaffected by the ShiftReport bug fix.
- **🟠 `AccountantDashboardService` computes everything from full-table `findAllWithRelations()`
  scans on every page load** — no caching, no pagination, no date-bounded queries.

## Conventions you'll reuse

- **Page controllers resolve `basePath` from the request URI** for role-correct links/redirects.
  Vietnamese UI copy; flash messages use `successMessage` / `errorMessage`.
- **🔴 Sorting a list "newest first" needs a tiebreaker on id.** `ExpenseService.resolveDate()` (and
  friends) truncate to `atStartOfDay`, so every slip created on the same day carries an identical
  `Instant`. Add `.thenComparing(X::getId, reverseOrder())`.
- **Status columns store the Vietnamese display string directly**, and cross-module spelling is
  inconsistent. **Never assume two modules spell a status the same way.**
- **The DB value is what the screen shows.** A display status comes from the stored column, never
  re-derived; an unrecognised value renders verbatim with a distinct "unknown" badge.
- **"Server-authoritative amount"** — Expense's refund payout and purchase payment both ignore the
  posted `amount` and derive it; the input is `readonly` + auto-filled by JS purely for display.
- **"Suggest a reference value, but let the user override"** — Purchase Invoice's `importPrice`,
  Type's VAT chips, procurement auto-fill.
- **Push a behaviour flag into the vocabulary, not an `if`** — `ExpenseType.PURCHASE_LINKABLE` +
  `supportsPurchaseInvoiceLink()`, `TaxRevenueGroup.isDeductionGroup()` / `isTaxExempt()`.
- **`data-money` + `fragments/money-input`** for thousand-separated money inputs. Read with
  `pmMoneyValue(input)`, never `Number(el.value)`. **`data-money-allow-negative`** opts a field into
  accepting a leading minus.
  - **🔴 The fragment registers its own `DOMContentLoaded` handler, and it runs AFTER the page's.**
    Defer any initial read with `setTimeout(fn, 0)`.
  - If your handler writes to a money field *and* listens on that field's `input`, guard against
    re-entry with a `syncing` flag.
- **"Suggest, then get out of the way" on split money fields.** Income's `balanceSplitPayment()`
  forces `cash + banking` to stay equal to the total; Expense deliberately does **not** copy that.
- **🔵 `fragments/number-input` for every other numeric field.** `<input type="number">` guard plus
  `data-digits` for text/tel fields holding digits.
- **`fragments/unique-check`** — live "already taken?" check on customer/supplier forms.
- **"Independent per-row save via its own small `<form>`"** — Price Settings, Expense detail actions.
- **"Optional `redirectTo` param, backward-compatible default"** — `/owner/approvals`.
- **Two-tier "draft → submit → approve"** still true for Expense and ShiftReport, each with its own
  rules. **No longer true for Income** — every submission auto-completes now (see "Read fourth").
  Stock Adjustment dropped out earlier. **Purchase Invoice gained a three-tier version conditionally
  (2026-08-04)** — draft → submit → approve/reject only when an Accountant is creating; the Owner's
  own no-Accountant create path stays single-step, self-approved. See "🔴 Read fifth".
- **FEFO stock deduction** repeats in `PurchaseinvoiceService` (receiving) and
  `InvoiceService.deductStock()` (selling).
- **"Add an entity field, add it to the generated REST DTO, forget the real service"** — a recurring
  bug shape here; `Purchaseinvoice.approvedAt` was the most recent instance (2026-08-03), since fixed.
- **🔵 "pending → notify Owners, approved/rejected → resolve the pending notification then
  notify the submitter"** — `WorkflowNotificationService`'s pattern across Return/Expense/StockCount/
  ShiftReport.
- **🔵 Prefix-match (`startsWith`) is replacing substring (`contains`) search in some places**
  (Procurement plan, Invoice Create's product search) but NOT others — check the specific screen.
- **🔵 "One flag gates several fields at once, checked at write time, not read time"** —
  `Financialsetting.setupConfirmed` unifies `revenueGroup`/`cashSafeBalance`/`bankAccountBalance`
  locking into a single boolean read via `isSetupConfirmed()`, OR-combined with each field's own
  legacy per-field signal for backward compatibility. New pattern as of this session; consider it if
  another module ever needs "several things lock together, once."

## Where things are

- **Tax period (Kỳ thuế)** — `service/TaxperiodsnapshotService`, `controller/TaxPeriodPageController`,
  `constant/TaxRevenueGroup`, `repository/TaxperiodsnapshotRepository`,
  `dto/request/TaxPeriod{Close,Update}Request`, `dto/response/TaxPeriod{ListItem,Detail,Computation}Response`,
  `templates/tax-period/{list,detail,preview}.html`. `/owner/tax-periods` + `/accountant/tax-periods`,
  no Pharmacist route. **109 tests.** Group transitions are automatic since 2026-08-01. **Formulas
  changed 2026-08-03 — groups 2/3 now share the same flat-1% GTGT method, group 3's PIT is 17%, group
  2 can opt into a profit-based PIT method** — see "Read third".
- **Expense (Phiếu chi)** — `service/ExpenseService`, `controller/{ExpensePageController,ExpenseController}`,
  `constant/{ExpenseStatus,ExpenseType}`, `templates/expense/{list,create,detail}.html`.
  `/owner/expenses` + `/accountant/expenses`, no Pharmacist route. **84 tests.** Only `GOODS_PAYMENT`
  links a purchase invoice. Now also credits/debits the Financial Setting fund on approve/cancel via
  `adjustFundBalances()` — see "Read third". Fires `WorkflowNotificationService` events.
- **Purchase invoice**: `service/PurchaseinvoiceService` — `applyPayment()` / `findPayableInvoices()` /
  `remainingDebt()` / `isDeductible()` are the Expense- and tax-facing API. `templates/purchase-invoice/*`.
  List/detail/print: Owner + Accountant + Pharmacist. **Create: Owner XOR Accountant, decided live by
  `canCreatePurchaseInvoice(role)`** — Accountant whenever reachable, Owner only while no Accountant
  is active; **Pharmacist can no longer create at all** (2026-08-04). When an Accountant creates, it
  goes through Nháp → Chờ duyệt → Owner Duyệt/Từ chối, with stock (`Batch`) received only at Duyệt —
  see "🔴 Read fifth" for the full mechanism. **70 tests.**
- **Return / Return-Purchase**: `service/{ReturnService,ReturnPurchaseService}`,
  `constant/{ReturnStatus,ReturnPurchaseStatus}`, `templates/{return,return-purchase}/*`. Return
  list+detail = all 3 roles, create = Owner + Pharmacist only. Return-Purchase is Owner-only.
  `ReturnService` owns `Invoice.returnStatus` + `invoiceReturnCode()`; both services now expose
  `isTaxExempt()` alongside the existing `deductionGroup` flag and fire `WorkflowNotificationService`
  events on Return's pending/approve/reject. **Zero tests — top risk, unchanged.**
- **Debt (Công nợ)**: `service/DebtService`, `controller/DebtController`, `templates/debt/*`.
  Owner + Accountant. Zero tests. Walk-in sales bucket under `WALK_IN_CUSTOMER_KEY = 0`.
  **`service/DebtOffsetService`** — separate manual netting tool at
  `/{owner,accountant}/debts/offset/{partyType}/{entityId}`, bypasses
  `ExpenseService`/`IncomeService`'s normal create path. Zero tests.
- **ShiftReport**: `service/ShiftreportService`, `controller/ShiftreportController` (incl.
  `/logout-guard`), `constant/ShiftReportStatus`, `templates/shift-report/*`. Owner + Pharmacist run
  shifts; **as of 2026-08-03 Accountant has read-only access** at `/accountant/shift-reports` (list +
  detail, no close/approve/reject) — new sidebar entry "Báo cáo ca". A shift's cash-fund credit on
  approval was fixed this window (was double-counting the whole handled-cash figure, now only credits
  the discrepancy — see "Read fourth"). New "collect cash shortage" flow raises a `SHIFT_SHORTAGE`
  Income slip when `cashDiscrepancy < 0`. Zero tests.
- **Income (Phiếu thu)**: `service/IncomeService`, `controller/IncomeController`, `templates/income/*`.
  All 3 roles, create included. **As of 2026-08-03, every submission auto-completes — no approval
  gate for any role any more** (was Owner-only auto-complete, everyone else pending). Also now credits
  the Financial Setting fund on completion (`creditFundOnCompletion()` → `applyFundDelta()`).
  `EMPLOYEE` type links one completed Stock Adjustment; `matchesResponsibleEmployee()` is that
  contract (still broken for `DESTROY_EMPLOYEE_FAULT` — Known gaps). Zero tests.
- **"Bán hàng"/Invoice**: `controller/InvoiceController`, `service/InvoiceService`, `templates/invoice/*`.
  Sets `Invoice.shiftReportID` on every sale; credits the fund via `applyFundDelta()` on creation.
  Invoice numbering is now a bare 8-digit zero-padded number (no `"HD"` prefix); `invoiceType` is
  always `"Bán hàng"`. Server-side prescription-drug gating on the cart
  (`invoiceContainsPrescriptionProduct()`), mirrored client-side, incl. an inline "unit conversion
  chain" hint (2026-08-03 evening) next to each product in search/cart. Print page (`/{id}/print`)
  was rewritten into a formal Vietnamese e-invoice layout, incl. a `?embed=1` iframe-preview mode.
  **Only 2 tests**, top risk alongside Return/ReturnPurchase.
- **Stock Adjustment / Stock Count**: `service/{StockadjustmentService,StockcountService}`,
  `constant/StockAdjustmentStatus` (`Nháp / Hoàn thành / Đã hủy`), `templates/stock-adjustment/*`.
  Consumes Stock Count, Income and Invoicedetail **read-only** through bare repositories.
  `StockcountService` now fires `WorkflowNotificationService` events too, and its list/create/detail
  templates use the standard toast/confirm fragments instead of native browser dialogs.
  Zero tests.
- **Price Settings**: `controller/PriceSettingPageController`, `service/PricesettingService`,
  `templates/owner/price-settings.html`. Owner-only. **49 tests.** Row's chart icon opens a
  "Chi tiết giá & thuế" modal (hand-rolled SVG chart + tax projection). **Unknown cost stays `null` →
  renders `—`, never 0.**
- **Notifications**: `service/NotificationService`, `controller/NotificationPageController`,
  `constant/Notification{Type,Category,Severity,Status,ReferenceType}`,
  `templates/notification/list.html`, `db/migration/V15__normalize_notification_table.sql`.
  All 3 roles. **THREE active producers, not one:** `TaxRevenueNotificationService` (unchanged,
  revenue-threshold warnings), `service/WorkflowNotificationService` (700 lines — pending/approved/
  rejected on Return/Expense/StockCount/ShiftReport, plus Income-created), and
  `service/InventoryNotificationService` (522 lines, scheduled via `InventoryNotificationScheduler`
  every 5 minutes — low/out-of-stock, expiring/expired batches). **List now paginates** (`page`/`size`,
  default 10/max 50, filter-state preserved across mark-read/dismiss actions). Notifications are
  per-account with a `dedupeKey`; `WebConfig` injects the service so every page's topbar gets its
  unread badge + dropdown. **Zero tests across all three producers.**
- **Dashboard**: `service/DashboardService` (Owner + Pharmacist) +
  **`service/AccountantDashboardService`** (new, 1253 lines — Accountant), `controller/RoleDashboardController`,
  `dto/response/{DashboardView,AccountantDashboardResponse}`,
  `templates/dashboard/{role-dashboard,accountant-dashboard}.html`. **All 3 roles now have a real
  dashboard** — `PlaceholderController` is fully deleted. Accountant's is computed fresh on every
  load from full-table repository scans (`findAllWithRelations()`), no caching. Zero tests on any of
  the three.
- **Employee Note — `/owner/users/{accountId}/notes`**:
  `controller/EmployeenoteController` (`@RestController`, GET/POST/PUT, Owner-only via URL prefix),
  `service/EmployeenoteService`, `repository/EmployeenoteRepository`,
  `dto/{request/Employeenote{Create,Update}Request,response/EmployeenoteResponse}`. Built on a
  **pre-existing** `Employeenote` entity/table (no new migration). The Owner writes free-text notes
  (≤2000 chars) about a staff member from a modal on `owner/users.html`; `ProfileController` reads
  them for the current account directly via the service (bypassing the `/owner/**` gate), rendered
  read-only on `profile.html` under "Ghi chú nhân viên". Fires an `EMPLOYEE_NOTE_CREATED` notification.
  **Not connected to the `DESTROY_EMPLOYEE_FAULT` employee-liability gap.** Zero tests.
- **Unified Approval**: `controller/ApprovalController`, `service/ApprovalService`,
  `templates/approval/list.html`. `/owner/approvals`, Owner-only. Aggregates Return / StockCount /
  ShiftReport / Expense — not Income, not StockAdjustment, not (yet) Purchase Invoice.
- **Product**: `ProductPageController` + `ProductService` + `templates/product/*`. Photo upload via
  Cloudinary. **86 tests.** List's search is 4 expandable free-text fields plus a "sắp hết hạn"
  checkbox. Create requires `minStock`/`maxStock`/`producerId`/`origin`/`typeId`.
- **Procurement plan**: `service/ProcurementplanService`, `templates/procurement-plan/*`. Search is
  now **prefix-match** (`startsWith`, was substring); combo products (`Type.sortType == "combo"`) are
  excluded from procurement entirely; detail-line UI groups rows by supplier. The print template
  (`procurement-plan-print.html`) got a unit-conversion display column and a percentage-based colgroup
  layout (2026-08-03 evening, purely cosmetic). Zero tests.
- **Sidebar/nav**: `SidebarMenuService` (single source of truth) + `SidebarInterceptor` + `WebConfig`
  + `templates/fragments/{sidebar,topbar}.html`. **`PlaceholderController` no longer exists** — fully
  deleted, not referenced anywhere. `fragments/sidebar.html` is fully data-driven — change the
  service, not the template. `WebConfig` now registers `SetupConfirmedInterceptor` FIRST, ahead of
  `PendingShiftInterceptor` and `SidebarInterceptor` — see "Read third". **Accountant gained a
  "Hàng hóa" group (2026-08-04)** pointing at `/accountant/products` — the route already worked
  (dropped from the sidebar only, in the 2026-07-25 redesign), just needed the menu entry back.
- **Auth/Security**: `security/*`, `service/CustomAccountDetailsService`, `config/SecurityConfig`,
  `context/CurrentUserContext`.
- **Financial Settings**: `FinancialSettingPageController` + `FinancialsettingService` +
  **`config/SetupConfirmedInterceptor`** (new, 2026-08-03 evening). **31 tests.** `revenueGroup`,
  `cashSafeBalance`, and `bankAccountBalance` now lock **together, in one shot**, driven by
  `Financialsetting.setupConfirmed` (`isSetupConfirmed()`, OR-combined with each field's older
  per-field signal for backward compat). Completing initial setup — filling all three at once — is
  now a hard, app-wide gate: no role can use any other screen until it's done (Owner does it;
  Pharmacist/Accountant just wait, redirected to a read-only view). `annualRevenueThreshold1`/`2` are
  no longer editable via this screen at all — fixed at their seeded values. `cashSafeBalance`/
  `bankAccountBalance` auto-reconcile afterward via `applyFundDelta()`/`adjustFundBalances()` — see
  "Read third". `Financialsetting` has `@Version` again (4th versioned entity), but the fund-mutating
  saves still use plain `.save(...)`, not a guarded `saveAndFlush()` — see Known gaps.
- **Customer / Supplier**: `service/{CustomerService,SupplierService}`. Service-level uniqueness +
  the live-check endpoint behind `fragments/unique-check`, backed by a live DB `UNIQUE` index
  backstop the entities don't declare. **Customer gained required fields**: `taxCode`/CCCD and
  `address` mandatory for everyone, bank fields mandatory for companies (previously all optional).
  Zero tests despite this new rule.
- **Shared UI fragments**: `templates/fragments/{confirm,toast,money-input,number-input,unique-check}.html`.
