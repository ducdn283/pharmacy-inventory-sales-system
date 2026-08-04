# Current Project State

Snapshot for handing off to a fresh session (no prior chat history needed). Written **2026-08-01**,
updated **2026-08-02**, updated again **2026-08-03** (twice), updated again **2026-08-04** (the
Purchase Invoice approval workflow — resolves the `Purchaseinvoice.approvedAt` risk this file
flagged as high-confidence-but-unproven at the end of the previous snapshot).

**Branch `hoang`. HEAD `abe1fc6 Merge pull request #158 from nguyentruong16/hoang`. Still AHEAD of
`origin/hoang`, not pushed.** Working tree clean apart from the perpetually-untracked `CLAUDE.md`,
`docs/`, `.claude/`, `run-dev.cmd` — normal and expected, they are never committed.

`mvn compile` → clean. `mvn test` → **536 run, 5 failures, 29 errors** (was 519/6/29 on 2026-08-03).
`+17` net tests, all in `PurchaseinvoiceServiceTest` (53→**70**, covering the new Nháp/Chờ duyệt/
duyệt/từ chối/xóa workflow, §0.14). Failures dropped by one — a stale `SidebarMenuServiceTest`
assertion was fixed in passing (see §7). Full breakdown in §7.

> **🔴 RESOLVED 2026-08-04: `Purchaseinvoice.approvedAt` is now wired up (§0.14).** The previous
> snapshot flagged this `@NotNull` column (teammate's `dbce767` commit, zero service-layer writer) as
> a high-confidence, not-yet-proven blocker, and noted the docx had started describing `Nháp`/`Chờ
> duyệt` statuses nothing in code produced. The user confirmed the intent directly: **when the
> pharmacy has an active Accountant, the Accountant now creates purchase invoices and the Owner only
> duyệt/từ chối them; when there is no active Accountant, the Owner still creates directly, one step,
> exactly as before.** `approvedAt` was changed from `NOT NULL` to nullable (user-approved schema
> change, hand-patched into the entity + live DB + `current-database.sql`, no migration) — a Nháp/Chờ
> duyệt row has no `approvedAt` yet by definition. §0.14 is the full mechanism write-up; §3's old
> "purchase invoice always created as Nợ, no approval step" bullet is now conditional, not universal.
>
> **The `Financialsetting.setupConfirmed` idea — reserved on 2026-08-03 (§9 item -0), explicitly NOT
> built pending a teammate's column — has now landed for real, and the feature is fully built
> (§0.13).** A teammate's `0ff6daf` commit added `setupConfirmed`/`@Version` back to the entity
> (properly this time — DB and docx updated to match). This session built
> `config/SetupConfirmedInterceptor` on top of it: **every role is now hard-blocked from every screen
> until the Owner completes Financial Setting** (`revenueGroup` + both fund balances, all three
> filled in one save, then locked together forever). `revenueGroupConfirmed` — the earlier, different,
> unapproved column that was reverted — is NOT what shipped; do not confuse the two names if either
> comes up again.
>
> **Financial Setting's `cashSafeBalance`/`bankAccountBalance` auto-reconciliation itself is
> UNCHANGED and still in force** — this REVERSES the 2026-08-02 "no auto-reconciliation, explicitly
> deferred" decision (§0.10/§2.12), and a separate, undocumented tax-formula overhaul (group 3 no
> longer uses GTGT deduction; PIT 15%→17%) also still stands. Read §0.11 (mechanism) and §0.13 (the
> `setupConfirmed` gate built on top of it) before touching `FinancialsettingService`, `InvoiceService`,
> `IncomeService`, `ExpenseService`, `ShiftreportService`, `TaxperiodsnapshotService`, `WebConfig`, or
> `financial-setting.html`.
>
> **🔴 Income's approval gate is gone for every role (§0.12)** — the old "Owner auto-completes,
> everyone else needs approval" rule no longer applies to anyone. A related `ShiftreportService`
> double-crediting bug was fixed in the same window, and the Accountant gained read-only access to
> `/accountant/shift-reports` (§0.12).
>
> **The Accountant Dashboard is built** (§2.13) — `PlaceholderController` is now **fully deleted**
> (0 routes), closing the former §6 item 2. A brand-new **Employee Note** feature also landed (§2.13).
> The Notification system went from **one** producer to **three**, plus pagination (§2.13).
>
> **`Invoice` and `Purchaseinvoice` gained `@Version` on 2026-07-31 evening** (§0.8) — a bigger deal
> than `Batch.version` was, because it required a centralized guarded-save rewrite across six services
> and broke two git-ignored test files on the way in (§7.3). `Financialsetting` is `@Version`'d again
> too as of this evening (§0.13) — **4 versioned entities total now**, but unlike the other three, its
> fund-mutating saves still use plain `.save(...)`, not a guarded `saveAndFlush()` — see §9's open
> items.
>
> **🔴 Tax group transitions became fully automatic on 2026-08-01** (§0.9) — REVERSES the
> "group changes are manual" rule §2.1/§3 used to state. **The formulas themselves then changed again
> on 2026-08-03** (§0.11) — groups 2 and 3 now share the same flat-1% GTGT method, and group 3's PIT
> rate moved from 15% to 17%.

> **Re-verify before trusting this document.** Several contributors land commits via PRs into
> `develop` and back, sometimes within the same wall-clock session, and this branch auto-commits on
> save/checkpoint. Run `git log --oneline -30` and `git fetch` first.
>
> A trap when you do: `git log` sorts by commit date, so a freshly merged `develop` line can push
> your own recent commits well below the top. If your work looks missing, check with
> `git merge-base --is-ancestor <sha> HEAD` before panicking.

---

## 0. Headline items — read these before touching anything

### 0.1 🔴 The schema still cannot be rebuilt from the repo — even though V15 now exists

`src/main/resources/db/migration/` reached **`V1`–`V15`** on 2026-07-29, which is less progress than
the number suggests. Read the three parts separately:

| Migration | What it actually is |
|---|---|
| `V1__baseline_existing_schema.sql` | **EMPTY** — two comment lines, no `CREATE TABLE`. The tables were made by Hibernate, never by a migration. |
| `V2`–`V14` | Password-reset token + seed data, written `INSERT … SELECT … WHERE NOT EXISTS` → **idempotent**. |
| `V15__normalize_notification_table.sql` | **The first real schema migration.** 16 guarded `ALTER TABLE` statements — and every one of them targets `Notification` only. |

So the headline is unchanged: **a fresh clone + empty MySQL + `flyway migrate` will not reproduce
the schema**, and with `ddl-auto = validate` the app **fails to start** against such a database.
Only the existing local `hang_ngoc_pisms` works. Every change outside `Notification` —
`taxperiodsnapshot`, `paidByCredit`, `nextPeriodTaxType`, `incomeTax`, `Invoice.returnStatus`, the
`return` column drops, the `stockadjustment` column drops, `Income.shiftReportOfAccountID` — is
still hand-applied. Nothing warns you at build time; `mvn compile` passes.

> **The Flyway history was reset and replayed at 2026-07-29 22:48** (a `<< Flyway Baseline >>` row
> plus V1–V15, all with the same timestamp). It did no damage precisely because V1 creates nothing
> and V3–V14 are idempotent — row counts were unchanged afterwards. Don't read that replay as
> evidence the schema came from migrations.

**`docs/context/current-database.sql` is authoritative and verified fresh (2026-07-31.)** Every
table/column was diffed against the live MySQL via `information_schema`: **366 columns on each side,
zero drift**. The only live-only tables are `flyway_schema_history` and `passwordresettoken`, which
come from the `V1`/`V2` migrations and have never appeared in the descriptive SQL.

**Newest hand-applied change: `Batch.version`** (`15ea14d`, `duc`, 2026-07-31). `@Version` optimistic
locking, so the live table gained `version int NOT NULL DEFAULT 0`. The live DB, the descriptive SQL
and the docx all already have it — but, as ever, **no migration**. A database without that column
cannot boot the app under `ddl-auto = validate`.

Do **not** write a schema-altering migration to catch up unless the user explicitly asks.

### 0.1b 🟠 The .docx disagrees with the DB in five places — trust the SQL

`Pharmacy-Database-Description.docx` was regenerated 2026-07-31 19:38 — **before** the
`Invoice`/`Purchaseinvoice` `@Version` change that same evening (§0.8), so it's already behind again
by the time you read this.

| The docx says | Reality |
|---|---|
| `Invoice.status` includes `Đã trả hàng toàn bộ` / `Đã trả hàng 1 phần` | `ReturnService` no longer writes those; `status` is pure debt lifecycle (§2.5). **Unfixed across three regenerations now** (07-29, 07-30, 07-31) — chase the owner rather than waiting for the next rebuild |
| `Expense.expenseType` lists five values, no `GOODS_PAYMENT` | Six types since 2026-07-31 (§0.7) |
| `Expense.status` lists `Chờ thanh toán` | The value still renders for old rows, but **no new slip can reach it** (§0.3) |
| `Invoice` has no `version` field | Added the same evening the docx was regenerated (§0.8) — just missed the cutoff |
| `PurchaseInvoice` has no `version` field | Same story |

~~`StockAdjustment` has `createdBy` / `approvedBy` / `approvedAt`~~ — corrected 2026-07-29.
~~`Invoice.returnStatus` description blank~~ — filled in.
~~`Batch.version` undocumented~~ — added in the 2026-07-31 19:38 rebuild.

### 0.2 🔴 A return slip computes, it does not pay

`b81e80b` dropped `refundCash` / `refundBanking` / `refundCredit` from the `return` table, the
entity and every DTO. What remains for money is **`totalRefund` + `offsetDebtAmount`**. The rule,
stated verbatim in `ReturnService`:

> *"Phiếu trả CHỈ TÍNH tiền, không chi tiền: cách chi (tiền mặt / chuyển khoản) là dữ liệu của
> phiếu chi bên Kế toán."*

Consequences:

1. **`ExpenseService` is the single gateway to refunding a customer.** `listCustomerReturns()` finds
   eligible returns; `cashRefundAmount(ret)` = `totalRefund − offsetDebtAmount` is what leaves.
2. **`ShiftreportService.totalCashOut` has no other source** — it is the sum of `paidByCash` over the
   Expense slips carrying that `shiftReportID`.
3. **`offsetDebtAmount` is now a real number** (changed 2026-07-27/28). `ReturnService` computes
   `MIN(totalRefund, dư nợ hiện tại)` when `Financialsetting.autoOffsetDebtOnRefund` is on, and `0`
   when it is off. Earlier snapshots said "always 0" — **that is obsolete**, and it is why the
   subtraction in `cashRefundAmount` matters.

### 0.3 🔴 A phiếu chi is ONE payment and cannot be edited (BA 2026-07-30)

**This replaces the previous "`Expense.amount` is an obligation" rule entirely.** The old model let a
slip under-pay itself and be topped up later via `markPaid()`; that is gone.

> Phiếu nhập 500.000, hôm nay trả 200.000 → **một phiếu chi 200.000**. Hôm khác trả nốt 300.000 →
> **một phiếu chi khác, 300.000**. Phiếu cũ không sửa.

| What | Now |
|---|---|
| `amount` vs `paid` | **always equal** — a slip never under-pays itself |
| `fullyPaid`, `paid` on the form | **removed** from `ExpenseCreateRequest`; "Đã chi đủ" checkbox gone |
| `markPaid()` + `/…/mark-paid` endpoint + its form | **deleted** |
| Status after approval | always `COMPLETED`; `AWAITING_PAYMENT` is never produced again |
| Cancelling a `COMPLETED` slip | **now allowed** (was blocked) |
| Amount when a document is linked | **typed by the user**, capped at what the document still owes; blank = pay it all off |
| Slips per document | **many** — including refunds |

Two consequences worth stating on their own, because both were forced rather than chosen:

- **Cancel had to open up.** Approval now completes a slip immediately, so the old
  "không thể hủy phiếu đã hoàn thành" guard would have left a mis-keyed slip with no correction path
  whatsoever. `cancel()` reverses the money with `applyPayment(-disbursedAmount)`; `applyPayment`
  already supported a negative delta.
- **Refunds had to become instalment-able.** With `markPaid()` gone, a part-paid refund could never be
  completed. The old one-slip-per-return `Set` guard became `committedByReturnId()`, mirroring
  `IncomeService.accountedByReturnId()`.

`ExpenseService.resolveAmount()` / `cappedByDocument()` implement the amount rule: a posted value over
the cap **throws** rather than being silently trimmed, so the creator learns their number was rejected.

### 0.3b 🔵 A phiếu chi carries NO debt state — deliberately

Built on 2026-07-30 as a "Tình trạng công nợ" column (constant class, DTO fields, list column, detail
rows) and **removed the same day** at the user's correction. The rule:

> Nợ là thuộc tính của **chứng từ**, không phải của phiếu chi. Phiếu chi chỉ là một lần chi tiền.

Where the outstanding amount actually lives: on `Purchaseinvoice` (`paid` / `status`), on a return
(remaining refund), and — for tracking — on the **Debt screen**. `IncomeService` is the precedent and
behaves identically: no debt field on a phiếu thu, the remainder appears only in the document picker
(`listDebtInvoices()` shows `invoice.getDebtAmount()`). `ExpenseServiceTest` carries a comment block
where those tests used to be, explaining the absence so nobody re-adds it.

### 0.4 🔵 `ensureOpenShiftFor()` is role-guarded now — the old "never call it" rule is retired

A teammate added a `runsRegister()` check at the single place shifts are created, so it returns
`null` for an Accountant instead of handing them a shift that
`ShiftreportController.logoutGuard()` would then block every logout on. **This fixed the
`IncomeService` lockout bug that earlier snapshots flagged as 🔴.**

Consequently `ExpenseService.attachOpenShift()` now:

- **`ensureOpenShiftFor` for a slip with `paidByCash > 0`** — cash out of the drawer must belong to a
  shift, and an Owner may pay before the day's first sale;
- **`findDraftShift` for a banking-only slip** — a transfer must not conjure a register session into
  existence.

The stamp uses the **creator's** shift, not the approver's or the payer's.

### 0.5 🔴 Every write to `Purchaseinvoice.paid` must re-derive and store `status`

`PurchaseinvoiceService.applyPayment()` is now the **only** place `paid` changes at all — a purchase
invoice is created with `paid = 0` and status `Nợ`, always (§3). It always calls
`resolveInvoiceStatus()` alongside. Miss that and the list shows "Nợ" on a fully-paid invoice —
exactly the bug `e3f5864` was written to fix. It deliberately does **not** touch
`isValidForDeduction`, which is recomputed on every read.

### 0.6 🔴 `stockadjustment` dropped `createdBy` / `approvedBy` / `approvedAt`

The approval-workflow removal (2026-07-28) went all the way to the schema on 2026-07-29. The live
table is down to 9 columns. **A stock adjustment therefore no longer records who created it** — a
deliberate call, documented in `StockadjustmentService:573`, not an oversight.

It has one non-obvious consequence in another module: `IncomeService.matchesResponsibleEmployee()`
resolves the employee liable for a loss, and its `expenseID` branch is now dead code. See §6 item 1.

### 0.7 🔴 `GOODS_PAYMENT` split out of `OPERATIONAL` (BA 2026-07-31)

**Reverses the 2026-07-26 decision** that put everything purchase-invoice-related under
`OPERATIONAL` on the grounds that "supplier debt *is* the import invoice, not a separate kind of
payment". In practice that made "Chi phí vận hành" balloon and stop answering the one question it
exists for: *how much went on điện, nước, lương this month*.

| Type | Label | Links a purchase invoice? |
|---|---|---|
| `GOODS_PAYMENT` **(new)** | Thanh toán hàng | ✅ **the only one** |
| `OPERATIONAL` | Chi phí vận hành | ❌ **no longer** — điện, nước, lương only |
| `DEBT_PAYMENT` | Trả nợ | ❌ (debt arising elsewhere, no document) |
| `RETURN_REFUND_PAYOUT` / `EMPLOYEE_ADVANCE_REPAYMENT` / `OTHER` | | ❌ |

`ExpenseType.PURCHASE_LINKABLE = [GOODS_PAYMENT]`, guarded by
`purchaseLinkableTypes_isGoodsPaymentOnly`. A stale `purchaseId` posted with `OPERATIONAL` is now
ignored like any other non-linkable type.

Two knock-on points:

- **Tax stays as it was, and gets cleaner.** `TaxperiodsnapshotService.DEDUCTIBLE_EXPENSE_TYPES`
  remains `OPERATIONAL` only — **do not add `GOODS_PAYMENT`**, tiền hàng is already inside giá vốn.
  The repository query's extra "not linked to a purchase invoice" filter now exists purely to handle
  rows saved before this change.
- **One line outside Expense had to move.** The Debt screen's "Chi trả" button passes an expense type
  into the create screen (`DebtController` → `debt/payable-detail.html`). Left on `OPERATIONAL` it
  would have opened the form with the invoice picker hidden, so it now sends `GOODS_PAYMENT`. No
  `DebtService` logic was touched.

**Old rows keep `OPERATIONAL` + a `purchaseID`** and render normally. No migration was written.

### 0.8 🟠 `@Version` optimistic locking landed on `Invoice` + `Purchaseinvoice` (2026-07-31 evening)

`Batch` got `@Version` on 2026-07-30 (`15ea14d`) with no real fallout — nothing concurrently races on
one batch row. `Invoice` and `Purchaseinvoice` followed the next evening (`3fe745f`), and this time it
had teeth: a teammate immediately had to touch **six services** (`c420009`) because a plain `.save()`
on a `@Version`-ed row can throw `ObjectOptimisticLockingFailureException` the instant two writers
race on it — and as of this same evening there are genuinely more ways to race on one invoice than
there used to be (see below).

**The fix, centralized:**

- `InvoiceService.persistInvoice(Invoice)` and `PurchaseinvoiceService.persistPurchaseInvoice(Purchaseinvoice)`
  are now the only sanctioned way to save these two entities. Both call `repository.saveAndFlush(...)`
  — not `.save()`, so the version check fires immediately inside the current transaction rather than
  silently at commit — wrapped in a try/catch that turns the optimistic-lock exception into
  `IllegalArgumentException("Hóa đơn/Phiếu nhập vừa được người khác cập nhật (…). Vui lòng tải lại
  trang…")`.
- Since every page controller already catches `IllegalArgumentException` for its flash error message,
  **this needed zero changes on the calling side** — a genuine write race now surfaces as an ordinary
  form error instead of a 500.
- Every direct `invoiceRepository.save(...)` / `purchaseinvoiceRepository.save(...)` call site was
  migrated to go through these two methods: `ReturnService`, `ReturnPurchaseService`, `IncomeService`,
  `DebtOffsetService` (§2.9), plus the two owning services themselves.

**Why concurrency risk on these two entities specifically went up this week:** `GOODS_PAYMENT` (§0.7)
allows multiple live Expense slips against one purchase invoice now, and the new `DebtOffsetService`
(§2.9) can independently touch the same invoice's debt. `@Version` is what makes two of those collide
safely — one wins, the other gets a clean, translated error — instead of one silently overwriting the
other's write with a stale value.

**Collateral damage, found and fixed same-session:** `PurchaseinvoiceServiceTest` (17 of 44 tests) and
`InvoiceServiceTest` (2 of 2) broke the moment this landed — both are git-ignored, so the teammate who
made the change had no way to see it. Root cause: both mocked `.save(...)` on their repository, but the
guarded-save methods call `.saveAndFlush(...)`; an unstubbed Mockito method returns `null`, which first
surfaced as NPEs (`invoice.getId()` on null) and then, once a `saveAndFlush` stub was added, as
`verify(repository).save(...)` failures ("wanted but not invoked") since `.save()` is genuinely never
called on that path anymore. Fixed by adding matching `.saveAndFlush(...)` stubs and updating the
`verify()` targets — full write-up in §7.3, which now tracks this as the fifth instance of the
"teammate change silently breaks a git-ignored test" hazard.

### 0.9 🔴 Tax group transitions are now fully automatic (2026-08-01)

**Reverses the "group changes are manual" rule §2.1 built the tax module under.** That rule read:
"the group is never derived from revenue; `nextPeriodTaxType` defaults to the current group and a
human changes it if they decide to." As of today, a human cannot change it at all — there is no
field left to post it through.

**The mechanism, `TaxperiodsnapshotService.autoNextGroup(period, groupOfPeriod)`:**

- **1 → 2 is immediate and retroactive.** The moment the year's revenue reaches ngưỡng 1, the
  quarter already in progress must be taxed under nhóm 2 in full (not just from the day of
  crossing) — per the same rule the revenue-threshold notification's copy always quoted:
  "tính thuế ngay từ chính quý phát sinh vượt ngưỡng." Since `group(period N) =
  nextPeriodTaxType(period N-1)`, the only way to make the *current, not-yet-closed* quarter read
  as nhóm 2 is to rewrite the *previous, already-closed* period's `nextPeriodTaxType` — which is
  exactly what `applyAutomaticGroupTransition()` does, plus syncing `Financialsetting.revenueGroup`
  in the same call. With no previous snapshot at all (the very first period ever), there is nothing
  on the chain to retro-fix, so the seed column is what changes instead.
- **2 → 3 is deferred to next year, and needs no retroactive write at all.** Crossing ngưỡng 2
  mid-year still owes nhóm 2 for the rest of that year (Nghị định 68); nhóm 3 only starts the
  following January. Since a period can only be closed after it has already ended, by the time the
  year's Q4 is closeable the whole year's revenue is already a settled fact — so `autoNextGroup()`
  simply checks, **inside `closePeriod()`/`updateLatest()` and only when the period being closed is
  itself December**, whether that year's `revenueForYear()` crossed ngưỡng 2, and forces
  `nextPeriodTaxType = DEDUCTION` right there. No flag needs to be persisted mid-year to remember
  the crossing; the fact recomputes itself fine by the time it matters.
- **`applyAutomaticGroupTransition()` (the 1 → 2 half) is called from the same two places
  `TaxRevenueNotificationService` already used** — opening the Tax Period list/preview screens, and
  closing a period — via a new wrapper, `TaxRevenueNotificationService.checkGroupTransitionAndWarn()`.
  There is still no scheduler in this app, so a crossed threshold sits un-applied until someone
  visits; `closePeriod()` also calls it defensively at its own start so closing is correct even if
  nobody visited first. Idempotent — once the group is no longer `EXEMPT`, it is a no-op.
- **`Financialsetting.revenueGroup` becomes a genuinely live-synced column, not just a first-period
  seed.** This closes a latent bug: `ReturnPurchaseService.revenueGroup()` reads that column
  *directly*, never the chain, so once the chain diverged from the seed after the first period ever
  closed, that service would have silently kept using a stale group forever. `closePeriod()` /
  `updateLatest()` both call `syncFinancialSettingRevenueGroup(nextGroup)` right after saving — safe
  to do unconditionally there because a period can only be closed/amended after its `endDate` has
  passed, which is exactly the "khi vượt qua endDate thì cập nhật revenueGroup" rule this feature
  was asked for in the first place.

**UI consequences:**

- **`TaxPeriodCloseRequest.nextPeriodTaxType` and `TaxPeriodUpdateRequest.nextPeriodTaxType` are both
  deleted.** The "Nhóm áp dụng cho kỳ sau" `<select>` on `tax-period/preview.html` became a read-only
  `${autoNextGroupDisplay}` line (new controller method `previewAutoNextGroup()`); the same dropdown
  on `tax-period/detail.html`'s amend form was deleted outright (the value is already shown read-only
  in the info card above it). `TaxPeriodPageController.groupLabels()` and the `groupOptions`/
  `groupLabels` model attributes it fed are gone with it.
- **`FinancialsettingService.isRevenueGroupLocked()`** = `taxperiodsnapshotRepository.count() > 0`.
  `owner/financial-setting.html`'s `<select id="fsRevenueGroup">` gets `th:disabled="${!editable or
  revenueGroupLocked}"`; because a disabled `<select>` doesn't submit at all, a locked render also
  emits a hidden `<input name="revenueGroup">` carrying the current value so `@NotNull` validation
  still passes — `FinancialsettingService.saveSettings()` ignores whatever value arrives for
  `revenueGroup` while locked regardless (keeps the stored one), so a client tampering with that
  hidden input has no effect. Before the first period ever closes, the field is still the one place
  a human types the seed.
- **`TaxRevenueNotificationService`'s messaging was rewritten** to stop claiming the system "chỉ
  cảnh báo, không tự động đổi nhóm thuế" — no longer true for the 1 → 2 case. A new notification type,
  `NotificationType.TAX_GROUP_CHANGED`, fires when `applyAutomaticGroupTransition()` actually changes
  something; the existing 80%/exceeded warnings still fire for the still-deferred 2 → 3 case.

**Verified live** (2026-08-01): seeded a throwaway `2026-Q1` snapshot via SQL so `2026-Q2` became the
next closeable period (the *real* due period, `2026-Q3`, has not ended yet as of this snapshot's
date), logged in as an Accountant seed account, closed `2026-Q2` for real through the UI, confirmed
"Kỳ cần chốt tiếp theo" advanced to `2026-Q3` and the Financial Settings "Nhóm doanh thu" field
became `disabled` immediately after — then deleted the throwaway rows and confirmed the field
unlocked again.

**Collateral damage, same session:** removing the field from both request DTOs broke every
`TaxperiodsnapshotServiceTest` helper/test that called the now-gone setter (compile error, not just
a runtime failure); adding the `TaxperiodsnapshotRepository` dependency to
`FinancialsettingService`'s constructor broke `FinancialsettingServiceTest` at the same level
(`NoSuchMethodError`). Both are git-ignored — see §7.3, now tracking this as the sixth instance of
the hazard, and the first one self-inflicted rather than caused by a teammate's change landing
unseen. Fixed same-session: `TaxperiodsnapshotServiceTest` 94 → **106** (dropped the two now-invalid
"rejects an unknown group" tests, added coverage for `autoNextGroup`/`applyAutomaticGroupTransition`/
the Q4-only 2→3 escalation/the `Financialsetting` sync), `FinancialsettingServiceTest` 6 → **9**
(added `isRevenueGroupLocked()` + the ignore-when-locked case).

### 0.10 🔴 Product / Purchase Invoice / Financial Setting UI pass (2026-08-02)

Three modules changed in one session, plus an unrelated teammate compile-break fixed along the way.
Full build log in §2.12; this section is the "read before touching these screens again" summary.

**Product:**
- **Detail** — `minStock`/`maxStock` and the unit-conversion table's ratio column now show the base
  unit's name (`"300 viên"`, `"1 Vỉ = 10 Viên"`) via a new `ProductDetailResponse.baseUnitName`
  field. Free fix along the way: "Tổng tồn kho" had silently rendered with no unit at all since it
  referenced a `baseUnitName` template variable no controller ever set — Thymeleaf's
  `#strings.isEmpty(null) == true` swallowed the error instead of throwing.
- **List** — "Yêu cầu đơn thuốc" (§6 item 16, now closed) derives from `Type.name` normalized
  against `"thuốc kê đơn"` — no schema change. New "sắp hết hạn" filter (90 days,
  `BatchRepository.findProductIdsNearExpiry`). The single `keyword` box became an expandable
  4-field search (mã sản phẩm / tên / mã vạch / nhà sản xuất — the 4th field added in a follow-up
  round, replacing the old exact-id `producerId` dropdown with free-text `producerQuery`).
  `searchProducts()` is now 9 params; the 5-arg legacy overload's 3rd param changed type from
  `Integer producerId` to `String producerQuery`.
- **Create** — `minStock`/`maxStock`/`producerId`/`origin`/`typeId` are now required
  (`ProductService.validateRequiredFields()`), with `*` markers and placeholders.

**Purchase Invoice:**
- **List** — supplier dropdown filter removed entirely (the model wiring, not
  `PurchaseinvoiceService.listSuppliers()` itself — Create's own supplier picker still needs it).
  `keyword` replaced by the same expandable-search pattern (mã phiếu / nhà cung cấp / sản phẩm).
  The two bare date inputs became a preset dropdown (Hôm nay/Hôm qua/Tuần này/Tuần trước/Tháng
  này/**Tháng trước/2 tháng trước/3 tháng trước**/Tùy chọn) with an arrow-pointer popover for the
  custom range, all client-side JS — `searchPurchaseInvoices()` only changed shape for the
  search/supplier part (`codeQuery`/`supplierQuery`/`productQuery` replacing `keyword`+`supplierId`),
  not the date handling.
- **Detail** — new read-only "Giá bán (đơn vị nhập)" column on the batch table.
- **Create** — same figure shown as a small line under the product name, not an editable field.
  **This one had a real bug fixed mid-session**: it first shipped keyed off the **base unit**, which
  silently disagreed with the "Đơn vị" column whenever a product's isDefault unit differs from its
  isBaseUnit one (e.g. imported by "Hộp", priced by "Viên"). Fixed by extracting
  `PurchaseinvoiceService.resolveImportUnitByProduct()` (same priority
  `resolveImportUnit()`/`getImportUnitNameByProduct()` already used: isDefault > isBaseUnit > lowest
  id) so `getSellPriceByProduct()` and the "Đơn vị" column always resolve to the same unit.
  **Also briefly write-through**: for part of the session, editing this field called
  `PricesettingService.updatePrice()` (same base-unit-cascade logic Price Settings uses) — reverted
  same session when the user asked for display-only instead. `PurchaseinvoiceService`'s dependency
  on `PricesettingService` (and the `@Lazy` needed to break the circular bean dependency it created
  with the pre-existing `PricesettingService → TaxperiodsnapshotService → PurchaseinvoiceService`
  chain — see §7.3) was added and then fully removed again; no trace remains in either direction.

**Financial Setting:**
- `cashSafeBalance`/`bankAccountBalance` — two of the three columns §6 item 7 called permanently
  inert — are now editable, prefilled like every other field, with a `balanceUpdatedAt` timestamp.
  `saveSettings()` only writes (and stamps the timestamp) when the posted value differs from what's
  stored, so saving an unrelated field on the same form can't spuriously touch it.
  **No automatic reconciliation against Invoice/Income/Expense was built — the user explicitly
  deferred that mid-session** after discussing the shape (a store-wide fund, triggered from
  `ShiftreportService.closeShift()`, summing finalized cash/banking movements since the last
  reconciliation). §6 item 7 is updated to reflect the new, smaller gap.
- All 5 money fields on this screen (`annualRevenueThreshold1/2`, `openingCashDefault`, and the two
  new balance fields) switched from plain `type="number"` to `data-money`/`fragments/money-input` —
  this screen had never used that fragment before, unlike Expense/Income/Purchase Invoice.

**Unrelated fix, same session:** a teammate's rename of `Taxperiodsnapshot.cashBalanceAtPeriodEnd` →
`quarterlyRevenue` (entity + `TaxPeriodDetailResponse`) left 4 call sites in
`TaxperiodsnapshotService` and 2 Thymeleaf reads in `tax-period/detail.html` pointing at a method
that no longer existed — broke `mvn compile` outright, which is how it surfaced immediately rather
than lurking as a runtime `SpelEvaluationException`. `TaxPeriodCloseRequest`/
`TaxPeriodUpdateRequest.cashBalanceAtPeriodEnd` (the posted form field) was untouched by the rename
and is a different thing despite the similar name — don't confuse the two if this comes up again.

Verified end to end in the running app for all of the above (not just unit-tested): created and
cancelled a throwaway purchase invoice to exercise the create→detail round trip and confirm
`Productunit.sellPrice` was untouched; edited and saved the two new Financial Setting balance
fields and confirmed the timestamp only moves on a real change; exercised every new filter/search/
preset via `javascript_tool` against the live page. Test counts: `ProductServiceTest` 70 → **86**,
`PurchaseinvoiceServiceTest` 44 → **53**, `FinancialsettingServiceTest` 9 → **13**.

---

### 0.11 🔴 Financial Setting funds are now live-reconciled, and tax formulas changed underneath everyone (2026-08-03, corrected same session)

One commit, `ef633bf` "update tax, finance setting", bundled two large, previously-undocumented
reversals — and, on top of them, added `@Version`/`revenueGroupConfirmed` to `Financialsetting`
without approval. The user caught this and had both reverted, without any DB/migration change. This
section is the detailed mechanism write-up, **already reflecting the correction**; §2.13 is the
build-log summary.

**A. Fund auto-reconciliation — REVERSES §0.10's "no auto-reconciliation, explicitly deferred".
Still in force, unaffected by the correction below.** `FinancialsettingService` has two distinct
write-through mechanisms:

- **`applyFundDelta(BigDecimal cashDelta, BigDecimal bankingDelta)`** — called from
  `InvoiceService.createSaleInvoice()` (every sale credits the fund immediately, no approval step),
  `IncomeService.creditFundOnCompletion()` (only once a phiếu thu reaches "Hoàn thành" — which, per
  §0.12, now happens for every role, not just Owner), and `ShiftreportService.creditCashSafe()` (at
  shift **approval**, with just `(cashDiscrepancy, ZERO)` — a *correction* on top of what the sale
  already booked, not the whole handled-cash figure; the old version of this method double-counted,
  see §0.12's bug-fix write-up). Treats a `null` stored balance as zero (auto-initializes).
- **`adjustFundBalances(BigDecimal cashDelta, BigDecimal bankDelta)`** — used only by `ExpenseService`:
  debits on `applyApproval()` (a slip reaching `COMPLETED`), credits back on `cancelExpense()`
  (reversing an already-disbursed slip, using a pre-cancel `wasDisbursed` snapshot). Silently
  **no-ops** if the stored balance is still `null` (no auto-init, unlike the other method).
- **🔴 CORRECTED this session**: both methods now call plain `financialsettingRepository.save(entity)`.
  `Financialsetting` briefly had `@Version` plus a guarded `saveGuardingConcurrentEdit()` →
  `saveAndFlush` + friendly-Vietnamese-message wrapper ("Thiết lập tài chính vừa được cập nhật ở nơi
  khác (có thể do một phiếu chi vừa giải ngân)...") on `adjustFundBalances`'s path only —
  `applyFundDelta` never had the guard even before the correction. Both the `@Version` field and the
  guard method have been removed; **neither method catches `ObjectOptimisticLockingFailureException`
  any more**, since there's no version column left to conflict on. A genuine last-write-wins race on
  this one row is possible again — the same situation as before `ef633bf` ever landed. This was a
  deliberate, accepted trade-off: the columns backing the stricter guard weren't approved, so the
  guard went with them rather than inventing a replacement.
- **`cashSafeBalance`/`bankAccountBalance` still lock permanently the instant they're first saved
  non-null** (`isCashSafeBalanceLocked()`/`isBankAccountBalanceLocked()`) — **entirely unaffected by
  the correction**, since these columns and this rule pre-date this session. `financial-setting.html`
  still disables locked fields with a "Đã khoá" hint and shows a `fragments/confirm` modal warning
  ("sẽ được khoá vĩnh viễn") before any save that would newly lock a fund field.
- **🔴 `revenueGroup`'s lock is back to ONE reason, not two.** `ef633bf` had added a
  `revenueGroupConfirmed` `Boolean` column giving `revenueGroup` a second, independent "lock on first
  save, even before any tax period closes" mechanism (`isRevenueGroupLocked()` returning true if
  EITHER "any period closed" OR `revenueGroupConfirmed` was set). **Removed this session, without any
  DB change**, at the user's explicit request (unapproved schema addition). `isRevenueGroupLocked()`
  is back to its original, single-reason form: `taxperiodsnapshotRepository.count() > 0`. **This is a
  real behavior change from what `ef633bf` shipped** — a freshly-set `revenueGroup` can now be edited
  repeatedly until the first tax period closes, instead of locking after one save. A true "lock on
  first confirmed save, before any period closes" cannot be built without persisting a new flag
  somewhere — `revenueGroup` itself is unusable as that signal since `V13` always seeds it with a
  concrete default (1), so nullity can't distinguish "never touched" from "confirmed as 1." If this
  stricter lock is wanted back, it needs an approved schema change, not a workaround.
- **`Financialsetting.address` field left in place** — not flagged as a problem by the user, and
  already hand-patched into both the live DB and `current-database.sql`. `@NotNull`, 100 chars,
  seeded via `V13` as `'Số 172 Phố Tuệ Tĩnh, Phường Uông Bí, Tỉnh Quảng Ninh, Việt Nam'`.
- **`Financialsetting` does NOT have `@Version`** — briefly added in the same commit as
  `revenueGroupConfirmed`, removed with it this session. Still 3 versioned entities total in the
  codebase (Batch, Invoice, Purchaseinvoice), not 4.
- Broke `InvoiceServiceTest.java` (missing the new 9th-of-11 constructor arg) — **fixed this
  session**, see §7.3 instance 8. This fix is unrelated to the `@Version`/`revenueGroupConfirmed`
  correction and still stands, since `InvoiceService` still depends on `FinancialsettingService` for
  the (retained) `applyFundDelta()` hook.
- **🔴 Follow-up, same evening: the reverted idea came back for real, properly this time — see §0.13.**
  A teammate added a *different*, approved column (`setupConfirmed`, not `revenueGroupConfirmed`)
  with the DB and docx updated to match, and this session built a full onboarding-gate feature on top
  of it (`SetupConfirmedInterceptor`, unifying `revenueGroup`/`cashSafeBalance`/`bankAccountBalance`
  locking into one flag). Everything above in this subsection describes the state *before* that —
  still accurate history, just no longer the current mechanism for locking these three fields. §0.13
  is the current mechanism.

**B. Tax formula overhaul — "BA quyết định trực tiếp (chưa có tài liệu)" per the javadoc, i.e.
genuinely undocumented outside the code itself:**

- **Group 3 no longer uses the GTGT deduction method.** Both group 2 and group 3 now pay a flat
  **1% of revenue** (`TaxRevenueGroup.DIRECT_VAT_RATE`) — `vatInput`/`vatCarryforwardIn/Out` are
  permanently zero for every group now. `isDeductionGroup()` is kept only for
  `ReturnPurchaseService`/display and **no longer implies GTGT is actually deducted.** **This
  directly contradicts §3's "Nhóm 3: GTGT khấu trừ" line** — that line has been rewritten below.
- **Group 3's PIT rate changed from 15% to `GROUP3_PIT_RATE = 17%`.**
- **Group 2 gained an optional profit-based PIT method** via the previously-unread
  `Financialsetting.taxCalculationMethod` field (pre-existing column, read for the first time here) —
  a period can be GTGT-flat and PIT-profit simultaneously (new `pitCostMethod` flag on
  `TaxPeriodComputationResponse`, independent of the existing `percentageMethod`).
- **Revenue sourcing changed to fix a double-counting bug**: `InvoiceRepository.findInPeriod` was
  renamed **`findValidInPeriod`** and now excludes superseded originals (an invoice with a `"Thay
  thế"` child, or an unsigned original at `returnStatus='FULL'` with no child ever created) from
  revenue/tax aggregation — previously both an original and its replacement were being summed.
  `InvoicedetailRepository.sumCostOfGoodsSoldInPeriod` got the identical filter.
  `ReturndetailRepository.sumRestockedCostInPeriod` was **removed** as part of this — a functional
  refactor (the old design computed COGS then subtracted restocked cost separately; the new design
  excludes superseded invoices at the query source instead), not dead-code cleanup. Verified
  `TaxperiodsnapshotService` already calls `findValidInPeriod` at every former call site — no stale
  caller left, compiles fine.
- **A separate `taxableIncomeRevenueOf()` method** now feeds TNCN specifically (adds supplier-
  commission Income, employee-liability Income, unknown-origin stock-count surplus cost, plus the
  gross value of `INTERNAL_USE`/`GIFT`/`SAMPLE` stock adjustments), distinct from the GTGT figure.

**Side effect on Invoice**: since GTGT is now computed identically for groups 2 and 3,
`InvoiceService.setInvoiceType()` **always writes `"Bán hàng"` now** — the `isDeductionGroup()`-driven
`"Hóa đơn GTGT"` branch was removed, `resolvePattern()`'s `kindPrefix` is hardcoded `'2'`. No separate
VAT-invoice document type is issued for new sales. `ReturnService.INVOICE_TYPE_VAT` recognition
(added `0f52652`, "add invoice type group 3 into return", which at the time made group-3 sale
invoices returnable again) is now a backward-compat path for pre-`ef633bf` rows only.

**Also in this commit**: invoice numbering lost its `"HD"` prefix (bare 8-digit zero-padded number,
`generateInvoiceNumber()` throws past 99,999,999); `InvoiceService` gained server-side prescription-
drug gating (`isPrescriptionProduct()`/`invoiceContainsPrescriptionProduct()`); the print page
(`getPrintPage()`) was rewritten with a full Vietnamese-number-to-words reader, buyer legal block,
and tax-authority-code builder — all private helpers, no new public methods, only the constructor
gained `FinancialsettingService`.

### 0.12 🔴 Income lost its approval gate; ShiftReport double-counted cash until today; Accountant can now see shift reports (2026-08-03)

**Income (`f5fba58`, "update logic income with role accountant and pharmacist"):** the old rule —
Owner's slip auto-completes, anyone else's goes to `STATUS_PENDING` awaiting Owner approval — is
**gone**. `createIncome()` dropped its `isOwner` parameter entirely; every submitted income slip now
auto-completes regardless of role. `STATUS_PENDING` and its counters/filters still exist (dead/legacy
paths for old rows) but nothing new can reach that status. The role-conditional "Gửi duyệt" button on
`create-income.html` was deleted; only the Owner-style "Hoàn thành" submit remains for everyone.
**This makes §"Conventions"'s "Two-tier draft → submit → approve exists in Expense, Income and
ShiftReport" stale for Income specifically** — it kept draft→submit, lost submit→approve. Also in
this window: `IncomeService` gained `FinancialsettingService` (credits the fund on completion, §0.11)
and `WorkflowNotificationService` (fires `incomeCreated` to Owner+Accountant, excluding the creator)
dependencies; `IncomeController.create()` gained `accountId`/`shiftReportOfAccountId` params to
prefill the form from a shift's cash-shortage card (see below).

**ShiftReport double-counting bug (`2db3ec1`, "fix logic shift report"):** `creditCashSafe(shift)`
used to deposit `actualClosingCash − openingCash` (the *whole* handled-cash figure) into
`cashSafeBalance` on shift approval — double-counting, because cash sales already credit the fund in
real time via `applyFundDelta` at the point of sale (§0.11). Fixed to deposit just
`shift.getCashDiscrepancy()` (the actual-vs-expected variance) instead. Confirmed one-way/terminal:
fires only from `closeShift()` (self-approve) and `approve()` (Owner approving a Pharmacist's shift),
both transitions into the terminal `APPROVED` state, so no double-credit risk remains going forward.

**Accountant shift-report read access + cash-shortage collection (`51321d1`, "add view shift report
screen for accountant"):** `ShiftreportController` gained `ACCOUNTANT_BASE = "/accountant/shift-reports"`
mapped on **list + detail GET only** (no close/approve/reject for Accountant) — a new class-level
javadoc explicitly states Owner/Pharmacist run shifts, the Accountant does not, but must be able to
read them to reconcile cash. **This reverses §3's "ShiftReport: Owner + Pharmacist" access line**
below. Bundled feature: a "Thu tiền thâm hụt quỹ" (collect cash shortage) card on shift detail — when
`cashDiscrepancy < 0`, Owner or Accountant (not the short Pharmacist themselves) can create a
`SHIFT_SHORTAGE`-type Income slip against the shortfall, prefilled via the new `accountId`/
`shiftReportOfAccountId` params. `ShiftreportService.search()` gained a `discrepancy`
(`SHORTAGE`/`SURPLUS`) filter; `getDetail()` gained `cashShortage`/`shortageIncomeId`/
`shortageIncomeCode`/`shortageIncomeStatus` fields. New sidebar entry "Báo cáo ca" for Accountant.

### 0.13 🔴 `setupConfirmed` built for real (app-wide onboarding gate); `Purchaseinvoice.approvedAt` likely broken (2026-08-03 evening)

Two unrelated things landed within the same window: this session finished a feature that had been
explicitly reserved earlier the same day (§9 item -0, before it was resolved), and a teammate's
separate commit added a required entity field with no code to fill it.

**A. `Financialsetting.setupConfirmed` — the reserved plan, now built.** Earlier today, `version`/
`revenueGroupConfirmed` were reverted from `Financialsetting` at the user's request (§0.11), and the
user then explicitly asked for a *different*, unified locking column to be added later by a teammate
first, deferring the actual UI/logic work until it existed (§9 item -0, "put that into reserve"). A
teammate's `0ff6daf` commit ("update finance setting", 2026-08-03 evening) delivered exactly that:
`Financialsetting.setupConfirmed` (`boolean DEFAULT false`) plus `@Version` again, both reflected in
the live DB and in `current-database.sql` this time — no more unmigrated drift on this specific pair
of columns. Docx description: *"Sau khi setting xong, sẽ khóa nhóm thuế và quỹ lại"*.

This session then built the actual feature on top of the column, resolving the two open UX questions
from §9 item -0 exactly as the user answered them ("1. đúng rồi, chặn hết... 2. đúng rồi. chặn hết...
3. không sửa ui nữa" — block every role hard, no exceptions, and don't touch anything beyond what was
asked):

- **`FinancialsettingService.isSetupConfirmed()`** (new) reads the flag. `isRevenueGroupLocked()`/
  `isCashSafeBalanceLocked()`/`isBankAccountBalanceLocked()` all became `isSetupConfirmed() || <old
  per-field signal>` — the confirm flag is now the primary lock reason, OR-combined with each field's
  pre-existing legacy signal (mostly for defensiveness/continuity, not because it's still needed).
- **`saveSettings()`**: while unconfirmed, the completing save must leave `revenueGroup` (already
  `@NotNull` on the DTO) **and** both fund balances non-null in the same transaction, or it throws
  `IllegalArgumentException` with a friendly Vietnamese message and nothing persists
  (`@Transactional` rollback — verified via direct DB check, no partial row). Once all three are
  present, `setupConfirmed` flips `true` and nothing ever flips it back.
- **`annualRevenueThreshold1`/`2` removed from the form entirely** (per explicit instruction #3 —
  "the UI was already right, don't touch it further" wasn't quite it; rather, the two threshold
  inputs were removed from the template and `saveSettings()` stopped reading them from the request,
  since they're meant to stay fixed at their `V13`-seeded values and were never meant to be
  user-editable — a design decision made earlier in the session, before the build, and just executed
  here).
- **New `config/SetupConfirmedInterceptor`**, modeled on the existing `PendingShiftInterceptor`,
  registered in `WebConfig` **ahead of** `PendingShiftInterceptor`/`SidebarInterceptor` (the most
  fundamental gate runs first). Blocks every authenticated request, every role, no exceptions beyond
  the settings screen itself, `/api/**`, static assets, and auth endpoints — deliberately **no**
  `/shift-reports` carve-out the way `PendingShiftInterceptor` has, matching "chặn hết" (block
  everything) from the user's answer. Owner → redirected to `/owner/financial-setting` (editable).
  Pharmacist/Accountant → redirected to a **new** `/pharmacist/financial-setting` route (added to
  match the pre-existing `/accountant/financial-setting` one), read-only, with a "waiting on Owner"
  message instead of the Owner's "chưa hoàn tất thiết lập" banner.
- **`WebConfig` gained a `FinancialsettingService` constructor dependency** — every `@WebMvcTest`
  importing the real `WebConfig` needs a `@MockitoBean FinancialsettingService` now.
  `NavigationRenderingTest`, `ProductPageControllerTest`, and `PermissionControllerTest` were updated
  proactively in the same change, so this did not become a ninth instance of the §7.3 hazard.
- **`financial-setting.html`**: role-aware warning banner; required asterisks on the fund inputs
  while unlocked; a single unified confirm-modal message listing all three fields about to lock
  together, gated on `!setupConfirmed`; submit button label toggles "Hoàn tất thiết lập" ↔
  "Lưu thiết lập".
- **Real bug found + fixed via live browser testing, not just unit tests**:
  `FinancialSettingPageController.save()`'s `bindingResult.hasErrors()` branch re-renders the same
  view but originally omitted `model.addAttribute("setupConfirmed", ...)`. The template's
  `th:if="${!setupConfirmed}"` banner then threw `SpelEvaluationException: EL1001E: Type conversion
  problem, cannot convert from null to boolean` — Thymeleaf's SpEL `!` on a null/missing model
  attribute throws, a gotcha already documented elsewhere in this file (§ conventions). Manifested in
  the browser as `net::ERR_INCOMPLETE_CHUNKED_ENCODING`, matching the documented mid-stream-exception
  pattern. Fixed by adding the missing `model.addAttribute` call to that branch.
- **`FinancialsettingServiceTest`**: 24 → **31** — added first-save-missing-cash-throws,
  first-save-missing-bank-throws, full-confirm-flow-sets-flag, already-confirmed-ignores-posted-
  thresholds, `isRevenueGroupLocked`/fund-locked-when-setupConfirmed-true (×2), and a test proving
  `annualRevenueThreshold1`/`2` are never overwritten by a save.
- **Verified live, end to end, both roles**: Owner blocked/redirected pre-setup, required-field
  rejection with no partial DB write, the exact confirm-modal wording, a successful confirm locking
  all three fields together, free navigation afterward, and the Pharmacist's read-only waiting view.
  Left the dev DB with `setupConfirmed=1`, `cashSafeBalance=5,000,000`, `bankAccountBalance=20,000,000`
  — the app is usable end to end right now, not stuck at the gate.

**B. `Purchaseinvoice.approvedAt` — new, required, unwired.** A separate teammate commit, `dbce767`
("update db purchaseinvoice", same evening), added:

```java
@NotNull
@Column(name = "approvedAt", nullable = false)
private LocalDateTime approvedAt;
```

to `Purchaseinvoice.java`, with a matching field + constructor param on `PurchaseinvoiceResponse`.
Confirmed via `DESCRIBE purchaseinvoice` that the live DB has this column, `NOT NULL`, no default —
matching the entity, same hand-patched-schema pattern as always (§0.1). **`grep -n approvedAt
src/main/java/.../service/PurchaseinvoiceService.java` returns nothing** — no create/update path sets
this field. `spring-boot-starter-validation` is confirmed present in `pom.xml`, which means Hibernate
ORM auto-validates entity-level Bean Validation annotations (`@NotNull` included) on persist —
**`createPurchaseInvoice()` should throw a `ConstraintViolationException` on every call.**

A live-reproduction attempt this session was inconclusive: JS `.click()` on the create form's submit
button produced no network request; `form.requestSubmit()` triggered an unexplained session logout
with nothing in the server log; a raw `fetch()` POST hit an unrelated `openingCashDefault`
BigDecimal-parse error from bypassing the page's money-formatting JS (posting `"1.000.000"` with
literal dots). Time/effort cost led to stopping the reproduction attempt rather than continuing to
fight the JS-heavy form — **this finding is high-confidence from code+schema inspection, not
empirically witnessed firing.** Verify by actually creating a purchase invoice before relying on
`PurchaseinvoiceService.createPurchaseInvoice()`.

The updated `Pharmacy-Database-Description.docx` (re-extracted from `word/document.xml` this
session) describes `approvedAt` as *"Thời điểm tạo thông báo"* ("notification creation time") — a
description that doesn't match the field's own name, suggesting either a copy-paste error or that the
column was drafted in a hurry. The same docx table now lists `Purchaseinvoice.status` as `Nháp / Chờ
duyệt / Nợ / Nợ một phần / Hoàn thành/ đã hủy` — two new values (`Nháp`, `Chờ duyệt`) that don't exist
as constants anywhere in `constant/PurchaseInvoiceStatus.java` (`DRAFT = "Nháp"` already existed as
dead code pre-dating this session; `Chờ duyệt` doesn't exist at all). **Best working theory: a
teammate started scaffolding a not-yet-built Purchase Invoice approval workflow, schema and docs
first, service layer not yet touched.** This would be a real reversal of the current, working,
documented "a purchase invoice is always created as 'Nợ', no approval step" rule (§2.3/§3) — but
since no service code reflects it yet, treat the *current* rule as still the accurate one, and this
column as a likely-in-progress teammate change, not a shipped feature. Do not build against the
"Nháp/Chờ duyệt" workflow without confirming with the BA; do not remove `approvedAt` either without
asking, in case it's mid-flight teammate work.

**This theory was confirmed correct the very next session — see §0.14.** The user explicitly asked
for exactly the "Nháp/Chờ duyệt" workflow this section predicted, the docx description was corrected
alongside it, and `approvedAt` is now a real, wired, nullable field.

### 0.14 🔴 Purchase Invoice creation moved to the Accountant (when there is one); Owner only duyệt/từ chối (2026-08-04)

BA request, verbatim: *"chủ nhà thuốc vẫn sẽ có chức năng tạo phiếu nhập. NHƯNG nếu có kế toán, thì kế
toán sẽ đảm nhiệm việc đó. Chủ nhà thuốc lúc đó sẽ không cần phải làm việc đó mà chỉ làm việc duyệt."*
This is the resolution to §0.13.B's `approvedAt` risk — the docx's `Nháp`/`Chờ duyệt` statuses were
describing exactly this workflow, just not yet built at the time. **The `/owner/approvals` screen
was deliberately left untouched** — the user stated another teammate owns it, and Purchase Invoice
approve/reject lives entirely on its own detail page, not aggregated there. Read this section in full
before touching `PurchaseinvoiceService`, `PurchaseInvoicePageController`, `PurchaseInvoiceStatus`, or
`templates/purchase-invoice/{create,detail,list}.html`.

**A. Who may create is now a live, role-conditional question — same shape as "who closes a tax
period" (`TaxperiodsnapshotService.canClose`, §2.1).** New
`PurchaseinvoiceService.hasActiveAccountant()` / `canCreatePurchaseInvoice(String role)`:

| Role | May create? |
|---|---|
| Accountant | Always (reaching the route at all already implies an enabled Accountant account) |
| Owner | Only while there is **no** active Accountant |
| Pharmacist | **Never** — removed entirely, not conditional on anything |

The Owner's `/owner/purchase-invoices/create` GET/POST redirect to the list with
`"Đã có Kế toán phụ trách tạo phiếu nhập — vui lòng vào phiếu \"Chờ duyệt\" để duyệt."` the moment an
Accountant account is enabled — checked server-side on the route (not just a hidden button) and via
the `canCreate` model flag driving the list page's "Tạo phiếu nhập" button visibility. Pharmacist's
old `/pharmacist/purchase-invoices/create` GET/POST mappings, and the Pharmacist-only
`procurement-plan-details` AJAX endpoint they used, were **deleted outright**, not hidden — an
explicit user decision ("dược không còn được phép tạo phiếu nhập nữa"), not a side effect. List/
detail/print access for Pharmacist is unaffected.

**B. Two entirely different flows depending on who's creating.** `PurchaseinvoiceService`'s class
javadoc used to say the old 3-step flow (create → duyệt → tạo lô) was permanently "gộp thành 1 use
case"; that is now only true for the no-Accountant path:

- **No active Accountant → `createPurchaseInvoice()`, unchanged 1-step behavior.** `Batch` rows are
  still created in the same transaction as before, but the method now ALSO stamps
  `approvedAt = LocalDateTime.now()` — the Owner is, in effect, both creator and approver on this
  path, and the column is no longer left null.
- **Active Accountant → Nháp / Chờ duyệt / Duyệt / Từ chối / Xóa, a real multi-step workflow, and
  stock is received ONLY at the Duyệt step:**
  - `createPurchaseInvoiceDraft()` / `createPurchaseInvoiceForApproval()` — the create form's two
    submit buttons ("Lưu Nháp" / "Nộp duyệt"). Both persist the header + `Purchasedetail` lines;
    **neither creates a `Batch` row nor touches `SupplierProduct.costPrice`** — `status` is `Nháp` or
    `Chờ duyệt`, `approvedAt` stays `null`.
  - `updatePurchaseInvoiceDraft()` / `submitPurchaseInvoiceDraft()` — a Nháp's own "Sửa phiếu nháp"
    screen, which is literally the create form pre-filled via the new `getDraftEditForm()` (same DTO,
    same validation, same client-side JS), with the same Lưu Nháp / Nộp duyệt choice. Editing
    wholesale-replaces the `Purchasedetail` rows (delete all, re-insert) — safe because a Nháp never
    has a `Batch` yet to reconcile against.
  - `approvePurchaseInvoice()` — Owner-only, Chờ duyệt → recomputes `status` via the pre-existing
    `resolveInvoiceStatus(totalAmount, paid=0)` (normally lands on `"Nợ"`, since `paid` is always 0
    before approval — no Expense can link an invoice that was never "Nợ"), stamps `approvedAt`, and
    **only here** calls the shared `receiveStockForInvoice()` helper — the exact same helper the
    no-Accountant path's `createPurchaseInvoice()` calls, extracted so both paths receive stock
    identically (creates `Batch` rows via `createBatchForDetail`, refreshes
    `SupplierProduct.costPrice`).
  - `rejectPurchaseInvoice()` — Owner-only, Chờ duyệt → back to Nháp with an appended note
    (`"Bị từ chối: <lý do>"`, reusing the same `appendNote()` pattern `cancelPurchaseInvoice()`
    already used). **Nothing to reverse**, since stock was never received — this is why reject is
    trivially safe, unlike `cancelPurchaseInvoice()`, which has to check every batch is untouched
    before it's allowed to reverse anything (§0.5-adjacent logic, unchanged).
  - `deletePurchaseInvoiceDraft()` — any Accountant (not restricted to the original creator — the
    user's answer was "phiếu lưu dạng nháp có thể xóa được" with no ownership qualifier), Nháp-only,
    hard-deletes the `Purchasedetail` rows then the `Purchaseinvoice` row itself. No `Batch`/debt/
    payment ever existed to clean up.
  - `cancelPurchaseInvoice()` gained two new guards: throws if the invoice is `Nháp` ("vui lòng xóa
    thay vì hủy") or `Chờ duyệt` ("vui lòng từ chối thay vì hủy") — cancel is now reserved for a row
    that was actually approved at some point, keeping one clear transition per status instead of two
    overlapping ones (a Nháp could otherwise be both "deleted" and "cancelled" with different code
    paths reaching the same end state).

**C. `Purchaseinvoice.approvedAt` is now nullable — a real, user-approved schema change, not a
workaround.** `@Column(name = "approvedAt")` lost both `@NotNull` (Bean Validation) and
`nullable = false` (JPA/DDL) on the entity; the live DB column and `current-database.sql` were
hand-patched to match (`datetime` now allows `NULL`, confirmed via `DESCRIBE purchaseinvoice` both
before and after), no Flyway migration, the same pattern this repo always uses for schema drift
(§0.1). This finally makes `Purchaseinvoice.approvedAt` consistent with every *other* `approvedAt`
column in the schema — `Return`, `Expense`, `Shiftreport`, `Stockcount` were always nullable; this one
was the odd one out from the moment it landed.

**D. A real bug was found and fixed via live browser testing, not just unit tests.** The "Sửa phiếu
nháp" edit form's "Nộp duyệt" button silently did nothing on click — no network request fired at all,
no console error, no visible feedback. Diagnosis path: `read_network_requests` showed zero POSTs after
the click; a JS-level `form.addEventListener('submit', ...)` probe confirmed the `submit` event itself
never fired; per-field `checkValidity()` inspection found `vatInvoiceDate` failing native validation
with an empty `.value` despite the DTO clearly carrying `2026-08-04` from the DB. Root cause:
`PurchaseInvoiceCreateRequest.vatInvoiceDate`/`.dueDate` and
`PurchaseInvoiceDetailCreateRequest.productionDate`/`.expirationDate` had no `@DateTimeFormat`, so
Thymeleaf's `th:field` rendered the pre-filled `LocalDate` into `<input type="date">` using the
request locale's short date format (`"8/4/26"`) instead of the ISO `yyyy-MM-dd` that input type
requires — the browser silently discarded the malformed `value` attribute, the date input rendered
empty, and this repo's own `fragments/field-validation.html` submit-guard (capture-phase, blocks
submission on any invalid *visible* required field) correctly refused to submit a form with a blank
required date, exactly as designed. Invisible on the CREATE form, because that one starts with an
empty date the user types by hand — the malformed pre-fill only happens when an *existing* `LocalDate`
value gets rendered back into the form, which only the new edit screen ever does for this DTO. Fixed
by adding `@DateTimeFormat(pattern = "yyyy-MM-dd")` to all four fields; re-verified live afterward
that the same edit → Nộp duyệt flow now actually posts and flips the status.

**E. Accountant gained Product list/detail sidebar access.** `/accountant/products` and
`/accountant/products/{id}` were already served by the existing `ProductPageController` role mapping
(dropped from the *sidebar only*, in the 2026-07-25 redesign — §4/§5) — this session just added the
missing "Hàng hóa" `linkGroup` back to `SidebarMenuService.accountantMenu()`. No controller or service
change needed; the route had been reachable by direct URL the whole time.

**`PurchaseinvoiceServiceTest`**: 53 → **70** tests — `canCreatePurchaseInvoice` (×4: Owner allowed/no
Accountant, Owner blocked/Accountant active, Accountant always allowed, Pharmacist never allowed),
owner-direct-create-now-stamps-approvedAt-and-receives-stock, draft-create/pending-create (×2, assert
no `Batch`/`SupplierProduct` save), approve (×2: receives-stock-and-stamps-approvedAt,
not-pending-throws), reject (×2: returns-to-draft-with-note-touches-no-stock, not-pending-throws),
delete-draft (×2: draft-deletes-details-then-invoice, not-draft-throws), submit/update-draft (×2:
submit-replaces-lines-flips-to-pending, update-not-draft-throws), cancel-guards (×2: draft-throws,
pending-throws).

**Verified live, end to end, both roles, using the seeded `thinhvc04`/`hoangnv05` Accountant accounts
(both enabled) and `ngoctn01` (Owner):** Owner blocked/redirected from create once an Accountant is
active; Accountant creates → Lưu Nháp → list shows "Nháp", Owner detail view shows no approve/reject
(status isn't Chờ duyệt yet); a second invoice created and Nộp duyệt'd directly from the create form →
"Chờ duyệt"; Owner Duyệt → `Batch` created (confirmed via direct `SELECT`, `storageQuantity` correctly
scaled by the import unit's ratio), `approvedAt` stamped, status "Nợ", `SupplierProduct.costPrice`
updated; separately, Accountant edited a Nháp and Nộp duyệt'd from the "Sửa phiếu nháp" screen (this
is where the date-formatting bug in item D was caught and fixed mid-session), Owner Từ chối with a
typed reason → back to "Nháp" with the note visible on the detail page, zero batches created; back as
Accountant, Sửa lại → Xóa phiếu nháp → gone from the list, confirmed via a follow-up DB query. Left the
dev DB with only the pre-existing seed data afterward — every throwaway invoice/batch/detail this
session created was deleted once verification finished.

---

## 1. Technology stack

| Area | Choice |
|---|---|
| Runtime / build | **Spring Boot 4.0.6, Java 25, Maven** (`./mvnw`). Lombok in use. |
| Persistence | **MySQL** `hang_ngoc_pisms` @ `localhost:3306` (`root` / `123456`, URL carries `?serverTimezone=Asia/Ho_Chi_Minh`). Spring Data JPA / Hibernate, **`ddl-auto = validate`**. |
| Migrations | **Flyway**, `src/main/resources/db/migration`, **`V1`–`V15`** — `V1` is empty and only `V15` alters a table, see §0.1. |
| View layer | **Thymeleaf 3.1**, server-rendered. Vietnamese UI copy throughout. |
| Security | **Spring Security**, form login at `/signin` (`loginId` + `password`), CSRF on, **one concurrent session per account**. |
| Files | **Cloudinary** for product photos — never DB blobs. Config is env-var backed. |
| Front-end | Purchased Bootstrap theme in `src/main/resources/static/assets` — **do not edit**. Tabler icons (`ti ti-*`). Theme `--bs-primary` is orange `#E66239`; the app's real primary is green `#059669`. |
| Tests | JUnit 5 + Mockito + AssertJ; a few `@WebMvcTest` slices. |

**Build commands** need an explicit JDK override — machine `JAVA_HOME` points at JDK 20:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'; .\mvnw.cmd -B --no-transfer-progress -o -DskipTests compile
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'; .\mvnw.cmd -B --no-transfer-progress -o test
```

**Dev DB has almost no transactional data.** `Product` has 20 rows; the transactional tables are
empty or hold only throwaway rows. Insert what you need to exercise a feature, then delete it.

> **Always pass `--default-character-set=utf8mb4` to the mysql CLI.** Without it, Vietnamese status
> strings insert mangled (`Nợ` becomes `N?`) and every status comparison silently fails — the row
> looks fine in a listing but no filter ever matches it.

---

## 2. Work landed

Three windows, all now pushed. §2.1–2.4 are 2026-07-27/28; §2.4b–2.6 are 2026-07-29 (§2.6 landing in the evening); §2.7 is 2026-07-30.

### 2.1 Tax period module — built end to end (`hoangnv04`, 2026-07-27/28)

`Taxperiodsnapshot` was an orphan entity (mapped, referenced by nothing). It is now a complete
feature: `TaxperiodsnapshotService` (807 at the time, ~950 now), `TaxPeriodPageController`,
`templates/tax-period/{list,detail,preview}.html`, `constant/TaxRevenueGroup`,
`repository/TaxperiodsnapshotRepository`, 3 response DTOs + 2 request DTOs. **94 tests as of this
build-out, 106 as of §2.10.**

**A period is always a calendar quarter** (`2026-Q3`). Group 4 (monthly) is out of scope.

**Everything hangs off a chain**, because the row does not store its own group:

```
group(period N)          = nextPeriodTaxType(period N-1)
vatCarryforwardIn(N)     = vatCarryforwardOut(N-1)
group(first ever period) = Financialsetting.revenueGroup      <- the seed, and (since §2.10) an
                                                                  actively kept-in-sync mirror too
```

So closing must be sequential, and only the newest period may be amended.

**Tax formulas, hardcoded per the docx** ("công thức GTGT/TNCN của từng nhóm được FIX CỨNG trong
code"). Revenue, shared by all: `max(0, Σ Invoice.total − Σ Return.totalRefund)` over approved
customer returns.

| | Nhóm 1 (<1 tỷ) | Nhóm 2 (1–3 tỷ) | Nhóm 3 (3–50 tỷ) |
|---|---|---|---|
| GTGT | miễn | `Doanh thu × 1%` | `max(0, đầu ra − đầu vào − chuyển kỳ trước)` |
| TNCN | miễn | `Doanh thu × 0,5%` | `Thu nhập chịu thuế × 15%` |
| Khấu trừ đầu vào | không | không | có |
| Chuyển khấu trừ kỳ sau | không | không | có |

Group 3's taxable income = `max(0, revenue − giá vốn hàng bán − chi phí vận hành)` where

- **giá vốn** = `Σ(Invoicedetail.baseQtyDeducted × Batch.importPricePerBase)` **less** the cost of
  restockable lines on approved customer returns — because revenue is net of refunds too;
- **chi phí vận hành** = `Σ Expense.paid` for **approved `OPERATIONAL` slips not linked to a purchase
  invoice**. Purchase-linked slips are excluded (already in giá vốn); refund payouts are excluded
  (already off revenue); debt payments and employee advances are not costs of the period.

**Group 1 is genuinely tax-free**: `computePeriod` returns zeros, `updateLatest` **drops** any tax
figures posted for an exempt period, the edit form offers no tax inputs, and list/detail/preview say
"Miễn thuế" instead of `0đ`.

~~**Nothing is automatic about the group.** Crossing a revenue threshold only warns... `nextPeriodTaxType`
defaults to the current group and a human overrides it when closing.~~ **Reversed 2026-08-01 — see
§0.9/§2.10.** The group is now fully derived from revenue, and there is no human override left at
all. Transition rules, originally stated in the UI copy and now enforced in code by
`autoNextGroup()`: **1→2 applies from the very quarter the threshold was crossed (retroactively, via
`applyAutomaticGroupTransition()`); 2→3 keeps the old group until year end** (Nghị định 68) and only
auto-applies when the period being closed is the year's Q4.

**Who closes**: the Accountant. The Owner may close only when no enabled `ACCOUNTANT` account
exists — a permission that depends on live data, not a static matrix. Four ordered guards:
permission → already closed → out of order → period has not ended.

Amending the newest period edits the figures **as typed** (it is an amendment to a filed
declaration, not a recalculation) but re-derives `vatCarryforwardOut`, so a period can never both
owe tax and carry credit forward.

`revenueBetween(from, to)` / `revenueForYear(year)` are public so the threshold warning can reuse the
one definition of revenue instead of writing a second that would drift.

### 2.2 Expense — cash rules tightened (`hoangnv04`)

> **Historical.** Two bullets below were superseded on 2026-07-30 — see §0.3 and §2.8. Kept because
> the cash rule itself still stands.

- **Only the Owner pays cash.** `resolveSplit()` flips the default by role (an unsplit amount is all
  cash for the Owner, all banking for anyone else) and refuses any cash portion from a non-Owner.
  **Still in force.** ~~`markPaid()` re-checks against the slip's applicant~~ — that second check went
  away with the method; a slip can no longer be topped up at all, so there is nothing to re-check.
  This is what makes "stamp the creator's shift" safe: an Accountant's slip can never carry cash.
- **Cash always belongs to a shift** — see §0.4. **Still in force.**
- ~~**`amount` is locked when derived**~~ — reversed, see §0.3: the user types the amount and the
  server caps it at what the document still owes.
- ~~`markPaid()` lost its dead `currentAccountId` parameter~~ — the whole method is gone.

### 2.3 Purchase invoice — always created as debt (`hoangnv04`)

BA 2026-07-27: receiving goods and paying for them are separate events. `createPurchaseInvoice` now
forces `paid = 0` → status `Nợ`; `PurchaseInvoiceCreateRequest` has **no `paid` field** and the create
form has no "Đã thanh toán" input. Money moves onto the invoice **only** through an Expense slip
pointing at it. That makes `applyPayment()` the sole writer instead of one of two paths that could
disagree. **44 tests** (at the time — 70 as of §0.14).

**🔴 Partially superseded 2026-08-04, see §0.14.** `paid = 0` on creation is still universal — that
part of this section stands. But **who creates, and whether stock/debt exist yet, now depends on
whether an active Accountant exists** — no Accountant: this section's "always Nợ, one step" behavior,
unchanged; active Accountant: creation starts as Nháp/Chờ duyệt with no stock/debt at all until the
Owner duyệt. Read §0.14 before relying on "a purchase invoice is always created as Nợ" as a universal
rule.

### 2.4 Teammate work, 2026-07-27/28

- **Debt screen** — new `DebtService` (597 at the time, 682 now) + `DebtController` +
  `templates/debt/*`, `/owner/debts`
  and `/accountant/debts` (previously `PlaceholderController` routes).
- **Stock Adjustment dropped its approval workflow** (`c327520`, `50871c6`): vocabulary is now just
  `Nháp / Hoàn thành / Đã hủy` and it is no longer aggregated by `/owner/approvals`.
- **`ShiftreportService.ensureOpenShiftFor()` gained a role guard** (§0.4), and
  `totalBankingOut` is now computed and stored alongside `totalCashOut`.
- **`Taxperiodsnapshot.incomeTax`** column added by `nguyentruong16`; wired by `hoangnv04` the same
  day.
- **`Invoice.returnStatus`** column added (`a07d310`) — implemented two days later, see §2.5.
- **`Purchaseinvoice.totalVATInput`** — the `@Column(name = "totalVATInput ")` trailing-space typo was
  fixed (`dcb3d6c`). Native SQL against that column is safe again.
- **Return policy settings are enforced now**: `ReturnService` / `ReturnPurchaseService` read
  `Financialsetting.returnPolicyMaxDays` (blank = no limit, `0` = same day only) and
  `autoOffsetDebtOnRefund`.

### 2.4b Numeric-input hardening + product stock guard (`hoangnv04`, 2026-07-29)

Two small pieces, both prompted by "ô giá nhập lô hàng cho gõ dấu `+-`".

**`templates/fragments/number-input.html`** — the cause was not one field: browsers accept `e`, `E`,
`+`, `-` in *any* `<input type="number">`, the value silently becomes empty (`validity.badInput`),
the characters stay visible, and every `calculateSummary()` reads `NaN`. The fragment blocks those
keystrokes, deriving the rules from each element's own attributes rather than a hardcoded id list
(`-` only when there is no `min` or `min < 0`; `.` only when `step` is fractional or `"any"`). It
binds at document level so JS-added rows are covered with no re-wiring, and clears the field on the
paste/drop path where per-character filtering is impossible on a number input.

A teammate then extended the same fragment with a second, independent **`data-digits`** guard for
text/`tel` fields that hold digits but must not be `type=number` (phone, tax code, CCCD, account
number — leading zeros would be lost). It is now included by 8 screens: product, purchase-invoice,
return, return-purchase, stock-adjustment, customer ×2, supplier ×2.

Verified in the running app rather than only unit-tested: `12e+-5` into Giá nhập yields `125`,
`-3.7` into Số lượng yields `37`, a row added via "Thêm sản phẩm" behaves identically, Giá bán
(`step="0.01"`) still accepts `.`, and Backspace/arrows/Tab are untouched.

**Expense's money fields needed no change** — they are `type="text"` + `data-money`, and
`fragments/money-input` already strips every non-digit on each `input` event.

**`ProductService.validateStockBounds()`** — auditing the server side of every numeric field in
those three modules turned up exactly one hole: purchase quantity/price/addition/discount had
`@Min`/`@DecimalMin`, product `sellPrice`/`quantityRelativeToPrevious` were checked in
`validateAndResolveUnits`, Expense `amount`/`paid` were checked in `ExpenseService` — but
**`minStock`/`maxStock` only had the `min > max` check**, so a negative posted directly went to the
DB. The duplicated `min > max` test in create and update was extracted into the new helper alongside
the two non-negative checks. **5 tests** (`ProductServiceTest` is now 60).

**Expense create screen** also lost its decorative `Mã phiếu` box (an unbound `PC-…` placeholder);
the code is generated at save and shown on the detail screen.

### 2.5 Teammate work landed 2026-07-29 (18 non-merge commits)

**Stock Adjustment was rebuilt** (`StockadjustmentService` 1192 → **1388**), driven by
`Dac_ta_Income_StockAdjustment.xlsx` — a spec **that is not in this repo** (§6 item 3).

- **7 adjustment types**, up from 6: `DESTROY`, the new **`DESTROY_EMPLOYEE_FAULT`**,
  `INTERNAL_USE`, `SAMPLE`, `GIFT` (hand-picked) plus `COUNT_INCREASE` / `COUNT_DECREASE` (derived
  from a Stock Count). No `INTERNAL_TRANSFER` — single store.
- **Three overlapping-but-separate constant sets**, and the overlap is deliberate:
  `EMPLOYEE_LIABLE_TYPES = {DESTROY_EMPLOYEE_FAULT, COUNT_DECREASE}` (the only losses chargeable to
  an employee — one still has the physical goods, the other doesn't, but neither is a deductible
  cost once someone compensates); `VAT_OUTPUT_TYPES = {INTERNAL_USE, GIFT, SAMPLE}` (output VAT at
  sell price; the destroy types instead keep the input VAT already deducted at purchase); and
  `NO_EXPIRED_GOODS_TYPES`, **the same three but a separate constant** because it encodes drug
  safety rather than tax law. The comment says explicitly: changing one must not drag the other.
- **`unknownOriginBatchIds`** on the create request — surplus lines from a stock count that the user
  marks as untraceable get a **brand-new batch** (there is no real purchase behind them) instead of
  being folded into an existing one, which would fabricate purchase history. Un-marked lines are
  treated as a bookkeeping slip on a known batch, with no tax effect.
- **`cancel()` gained downstream-mutation guards.** Cancelling a `Hoàn thành` slip reverses the
  stock movement, but is refused when any of its batches was **sold** (`countSalesFromBatchAfter`)
  or **adjusted by a later slip** since. The error tells the user to write a new adjustment instead
  of unwinding history. Cancelling a count-sourced slip also reverts the Stock Count from
  `Đã điều chỉnh` back to `Đã duyệt` so another slip can be raised for it.
- Stock-count statuses are matched **accent-insensitively** and only the two needed values are
  mirrored, because the Stock Count screen (another teammate) owns the canonical spelling.
- Income and Invoicedetail are consumed **read-only through bare repositories** — the module
  deliberately adds no query methods to another module's repository file.

**Income gained employee reimbursement.** `IncomeType.EMPLOYEE` now points at one completed Stock
Adjustment; `listStockAdjustments()` offers only slips that are completed, attributable to the
chosen employee, and not already linked (one Income per adjustment, enforced on save too).
`ShiftreportService` deliberately does **not** break this out as its own line (BA 2026-07-28): it is
already inside `totalCashIn`/`totalBankingIn` and the shift only needs the number to be right.

**`Invoice.returnStatus` is implemented — and it is only a cache.** `ReturnService:770` writes
`NONE`/`PARTIAL`/`FULL`; adjustment and replacement invoices are seeded to `NONE`.
**`invoiceReturnCode()` recomputes it from the invoice lines on every read and is the source of
truth**, so rows predating the column are not wrong. In the same change **`Invoice.status` stopped
carrying return wording** and went back to pure debt lifecycle (`Còn nợ` / `Hoàn thành`); the list
and detail screens grew their own "Trạng thái trả hàng" badge column instead.

**Return-Purchase VAT fixed for groups 1/2.** `importPricePerBase` is GROSS, so only a deduction
group (3) splits net/VAT back out of it. Groups 1/2 now write `vatRate = vatAmount = 0` and
`preTaxAmount = lineRefund` **on the detail lines as well as the total**, so
`Σ preTaxAmount = totalRefund` and `Σ vatAmount = totalVATRefund = 0`. Zeroing only the total left
the detail screen contradicting itself (lines showing tax, total showing 0) and reports subtracting
the wrong number.

**Customer / Supplier uniqueness.** Neither table has a UNIQUE constraint beyond its PK, so
`CustomerService` (253) / `SupplierService` (264) became the only gate against duplicates — phone,
tax code, CCCD. Format is validated **before** uniqueness, so the user sees "CCCD phải 12 số"
instead of a duplicate error on an already-invalid value. The same helpers back both the save path
and a new live-check endpoint on `CustomerController`/`SupplierController`, consumed by the new
`fragments/unique-check.html`. Both list screens also got deterministic sorting by id.

**Debt screen** (`DebtService` 597 → **682**): walk-in sales with no `customerID` are bucketed under
a synthetic `WALK_IN_CUSTOMER_KEY = 0`, and customer refund debt is computed as the return's
obligation **minus what live Expense slips actually disbursed** — unlike purchase-invoice debt, the
return row itself never shrinks.

### 2.6 Notifications, dashboards and the threshold warning (teammate, 2026-07-29 evening)

**Notifications went from a read-only stub to a full feature.** `NotificationService` (296) now has
search / stats / `markAsRead` / `markAllAsRead` / `dismiss` / `createIfMissing` /
`resolveByDedupeKey`, backed by 5 constant classes
(`Notification{Type,Category,Severity,Status,ReferenceType}`), `NotificationPageController`, a
425-line screen and `V15__normalize_notification_table.sql`. Rows are **per account** and carry a
`dedupeKey`, so the same warning re-raised twice does not stack up. `WebConfig` injects the service
so every page's topbar gets an unread badge and a dropdown of the latest few.

**The revenue-threshold warning is finally built** — `TaxRevenueNotificationService` (256), warning
at **80%** of the next threshold and escalating once it is crossed. It reuses
`taxperiodsnapshotService.revenueForYear()`, which is exactly why that method was made public in
§2.1: there is still only one definition of revenue. Verified end to end on 2026-07-30 — seeding
revenue past a lowered threshold and opening the tax-period preview produced three notifications
(Owner + both Accountants) with distinct dedupe keys, `URGENT` / `KY_THUE`, rendered correctly.
**It is triggered by a human opening or closing a tax period** (two call sites in
`TaxPeriodPageController`), not by a scheduler — see §6 item 10.

**Dashboards**: `DashboardService` (671) + `RoleDashboardController` + `DashboardView` +
`templates/dashboard/role-dashboard.html`. The Owner view shows revenue over 7 days, việc cần xử lý,
hiệu suất nhân viên and phê duyệt gần đây. **Only Owner and Pharmacist exist** — §6 item 2.

**Income** gained more types, a reworked create/detail UI, and `Income.shiftReportOfAccountID`
(FK → ShiftReport). `IncomeService` is now 1210 lines.

### 2.7 Price Settings detail modal + product validation + two fixes (`hoangnv04`, 2026-07-30)

**"Chi tiết giá & thuế" modal** on `/owner/price-settings` — full write-up in `CLAUDE.md`
("Where things are"). The parts worth knowing here:

- Fetched per product from `GET /owner/price-settings/{id}/detail` (`@ResponseBody` on the page
  controller, same shape as `PurchaseInvoicePageController.getProcurementPlanDetails`), so opening
  the list costs nothing extra.
- The chart is **hand-rolled SVG**. No chart library: the CSP forbids external hosts and the theme
  bundle in `static/assets` must not be edited.
- The modal is **hand-rolled too**, because the theme ships Bootstrap as an ES module — there is
  **no `window.bootstrap`**, so `new bootstrap.Modal()` is unavailable even though declarative
  `data-bs-toggle` works. Same approach as the stock-count picker in `stock-adjustment/create.html`.
- Tax is projected on **one base unit** under `TaxperiodsnapshotService.currentRevenueGroup()`,
  branch for branch the same as `computePeriod()`. Cross-checked against hand arithmetic in the
  browser for all three groups.
- **An unknown cost stays `null` and renders `—`, never `0`.** A product with no in-stock lot has no
  cost basis; defaulting it to zero reported the entire sell price as profit. Group 2 still shows
  its full tax (that method never looks at cost); group 3 blanks input VAT, TNCN and profit.
- Tax lines keep **2 decimals** while prices stay whole đồng — rounding `23,81 − 20,33 = 3,48` to
  "24 − 20 = 3" made the panel contradict itself.

**12 tests** (`PricesettingServiceTest` 37 → 49).

**Product identity validation** — see §3. `ProductService.validateUniqueness()`, **10 tests**
(`ProductServiceTest` 60 → 70).

**Two bugs fixed in the day-old Notifications screen, which was completely unreachable:**

1. **All three `/…/notifications` pages returned 500.** `NotificationPageController` and
   `PlaceholderController` both mapped them → `IllegalStateException: Ambiguous handler methods
   mapped for '/owner/notifications'`. **This is a request-time failure, not a startup one**, so the
   app booted clean and only died when someone clicked the new sidebar link.
2. **The page then rendered half-blank with status 200.** `notification/list.html` called
   `~{fragments/toast :: toast}`; the fragment is named `flash` (the other 22 usages are right).
   A `TemplateInputException` mid-stream — the documented "blank page, status 200" signature.

**`PlaceholderController` trimmed from 9 routes to 1** (§5).

### 2.8 Expense reshaped end to end (`hoangnv04`, 2026-07-30/31)

The module's rules, screens and vocabulary all moved. Rules are in §0.3, §0.3b and §0.7; this section
is the build log. **77 tests** (`ExpenseServiceTest`, substantially rewritten).

**The bug that started it.** "Bỏ chọn *Đã chi đủ*, để trống ô tổng, chỉ điền tiền mặt 5.000 + chuyển
khoản 10.000" saved a slip with `paid = 0` **and both portions silently zeroed**: `resolvePaid()`
returned 0, then `resolveSplit()`'s `paid == 0` short-circuit threw the split away without a word.
Fixed by deriving the total from the split — then superseded a day later when `paid` disappeared from
the form entirely (§0.3). The derivation survives as the reason the server can still read a slip whose
total is blank.

**Screens rebuilt against the Income module** (the user's explicit reference):

- **List** — `.kpi-card` ×4, one `.main-card` holding heading + filters + table + paging,
  `.sup-table` with fixed column widths. Columns `Số tiền` + `Đã chi` became **`Số tiền đã chi` +
  `Hình thức`**, the latter derived exactly like `IncomeService.paymentDisplay()`.
- **Detail** — money moved out of the info grid into a right-hand "Thanh toán" card.
- **Create** — right column carries "Thanh toán" (payment-method radios + money steppers, copied from
  `create-income.html`) and "Nội dung phiếu chi"; **"Ngày chi" removed** since the server already
  defaults to today, matching the Income create screen which has no date either.

**Three things deliberately diverge from Income**, each after a user correction:

1. **No auto-balancing on the split.** Income's `balanceSplitPayment()` rewrites the other box as you
   type, which makes it impossible to clear one and retype. Expense prefills once, then leaves both
   boxes alone and reports a mismatch in words.
2. **Nothing on the create form is hidden behind "choose a type first"** — that was tried and reverted;
   fields appearing and disappearing as you type reads as flicker.
3. **The list table keeps a horizontal-scroll wrapper** (`.sup-table-wrap` + `min-width`). Income's
   version has none, so on a phone its eight columns are crushed to ellipsis; the old Expense list had
   `.table-responsive` and dropping it would have been a regression.

**Two traps worth remembering**, both documented in `CLAUDE.md` under "Conventions":

- The `money-input` fragment registers its `DOMContentLoaded` **after** the page's, so an initial
  `pmMoneyValue()` read sees the raw server value — `"500000.00"` reads as 50.000.000. Defer with
  `setTimeout(fn, 0)`. Only visible when a form re-renders after a validation error.
- Sorting "newest first" needed a **tiebreaker on id**: `resolveDate()` truncates to `atStartOfDay`,
  so every slip created the same day ties and the stream falls back to `findAll()` order — oldest
  first, which looks exactly like broken sorting.

### 2.9 Teammate work landed 2026-07-31 evening (`nguyentruong16`/`duc`, PRs #142–#143, 11 commits)

**`@Version` + guarded-save on `Invoice`/`Purchaseinvoice`** — full write-up in §0.8. Broke, then fixed
same-session, the git-ignored `PurchaseinvoiceServiceTest` and `InvoiceServiceTest` (§7.3).

**`DebtOffsetService`** (new, 554 lines) — manual "Bù trừ công nợ": the Owner opens a customer's or
supplier's combined debt screen, picks specific receivable lines and specific payable lines whose
totals must match exactly, and submits. The service creates one credit-only `Income` and one
credit-only `Expense` per matched pair (`paidByCredit = amount`, `paidByCash = paidByBanking = 0`,
`reason = "Bù trừ công nợ"`, both auto-approved/`Hoàn thành`) and reduces the underlying document —
`Invoice.debtAmount` directly for a customer invoice, `PurchaseinvoiceService.applyPayment()` for a
supplier purchase invoice. It writes straight to `IncomeRepository`/`ExpenseRepository` with its own
code-generation helpers (`generateIncomeCode()`/`generateExpenseCode()`, duplicated from the owning
services, not reused) — **it does not call `IncomeService.createIncome()` or
`ExpenseService.createExpense()` at all.**

**This is unrelated to `ReturnService.applyDebtOffset()`**, which still works exactly as before
(automatic netting of a return's refund against debt on that *same* invoice, at approval time). The
new tool nets a party's *whole* receivable/payable position, potentially across many unrelated
documents, and only runs when the Owner explicitly asks for it. It is also **not** the
"auto-generate an Income/Expense pair when a return nets debt" feature discussed and declined earlier
in this project's life (§0.2 note on the missing chứng từ pair) — that gap is technically still open;
this is a separate, manual instrument that happens to produce the same shape of paired credit rows.

`ExpenseService`/`IncomeService.paymentDisplay()` were extended to recognise a pure-credit state
("Cấn trừ công nợ") and credit-mixed-with-cash/banking combinations — but their own
`createExpense()`/`createIncome()` entry points still hardcode `paidByCredit = ZERO`. As of this
snapshot, `DebtOffsetService` is the **only** code path that ever writes a non-zero `paidByCredit`.

**`ReturnPurchaseService` bug fix**: supplier debt-offset used to do `purchase.setPaid(...)` then
`purchaseinvoiceRepository.save(purchase)` directly — skipping the "every write to `paid` must
re-derive `status`" rule (§0.5) the same way `e3f5864` fixed once already for the general case. Now
routes through `DebtService.recordPurchaseDebtOffset()` → `PurchaseinvoiceService.applyPayment()`,
which keeps `paid`/`status` in sync.

**Password rule tightened**: minimum 8 characters (`AccountPasswordService.MIN_PASSWORD_LENGTH`, was
6) **and must contain at least one letter and one digit**, checked character-by-character rather than
by regex so accented letters (`à`, `ñ`) still count as letters. Shared by both the change-password and
reset-password flows through one method.

**Pharmacist dashboard** (`DashboardService`, `templates/dashboard/role-dashboard.html`) grew
substantially (+474 / +746 lines) — **not reviewed in depth this session**; treat its current behavior
as unverified until someone actually reads it.

**Large "clean up comment" pass** (`06eab5c`, 13 files, net −231 lines) — shortened and
Vietnamese-ified verbose javadoc/inline comments across `ReturnService`, `StockadjustmentService`,
`CustomerService`, `SupplierService`, `ShiftreportService`, `ReturnPurchaseService`, and several
fragment templates. Spot-checked `ReturnService`'s diff line by line: **wording only, no logic
changed.**

### 2.10 Tax group transitions made fully automatic (`hoangnv04`, 2026-08-01)

Full mechanism write-up in §0.9 — this is the short version for the work-landed timeline.
`TaxperiodsnapshotService` gained `autoNextGroup()` (private) and two new public entry points,
`previewAutoNextGroup(period)` and `applyAutomaticGroupTransition()` (returns a
`GroupTransitionResult(changed, fromGroup, toGroup)` record); `closePeriod()`/`updateLatest()` no
longer accept a posted `nextPeriodTaxType` and instead derive it, syncing
`Financialsetting.revenueGroup` right after saving. `TaxRevenueNotificationService` gained
`checkGroupTransitionAndWarn()` (applies the transition, notifies if it changed anything via the new
`NotificationType.TAX_GROUP_CHANGED`, then falls through to the existing threshold warnings) and is
now what `TaxPeriodPageController` calls at all three touchpoints (list, preview, close) instead of
the old `warnIfRevenueThresholdReached()` directly. `FinancialsettingService` gained
`isRevenueGroupLocked()` and a `TaxperiodsnapshotRepository` dependency to back it.

Prompted by the user directly: earlier that day they'd noticed the Financial Settings "Nhóm doanh
thu" field was still freely editable and asked why, which surfaced that
`Financialsetting.revenueGroup` had never been kept in sync with the tax-period chain after the
first period — a real latent bug in `ReturnPurchaseService.revenueGroup()`, which reads that column
directly. The 1→2-vs-2→3 timing asymmetry (§0.9) was worked out with the user via `AskUserQuestion`
before writing any code, because the literal first draft of the request ("update `revenueGroup`
immediately in both cases") would have made `ReturnPurchaseService` disagree with the tax-period
screens about the current group for the rest of a 2→3-crossing year — confirmed wrong against the
worked example ("quý 2 vượt 3 tỷ vào 5/6 thì vẫn tính nhóm 2 đến hết năm").

### 2.11 Comment cleanup, six modules (`hoangnv04`, 2026-07-31 evening → 2026-08-01)

At the user's explicit request, across Product, Purchase Invoice, Expense, Permission Table,
Financial Settings and Price Settings — backend (entities, repositories, DTOs, constants, services,
page controllers; generated `@RestController`s checked too, but they carry no comments to begin
with) and frontend (Thymeleaf templates, inline `<script>`/`<style>` comments) alike. Mostly removed
commit-hash/date narrative (`BA 2026-07-30`, `{@code 17e606f}`, `Đổi từ 2026-07-30`) that belongs in
git history rather than code — the same category of cleanup as the teammate `06eab5c` pass above,
just covering different modules — while keeping every WHY-comment that explains a non-obvious
business rule or invariant.

Three real bugs turned up along the way, not just style:

- **`PurchaseinvoiceService.resolveInvoiceStatus()`'s javadoc had drifted ~80 lines from the method**
  it documented, sitting instead directly above `findPayableInvoices()` — moved back to the right
  place.
- **`ExpenseService` carried two fully-superseded English javadoc blocks**, stacked directly above
  their Vietnamese replacements for `applyApproval()` and `resolveAmount()`, still describing the
  deleted `markPaid()`/`AWAITING_PAYMENT`-top-up flow from before the 2026-07-30 Expense reshape
  (§0.3). Two of the dead references were literal `{@link #markPaid}` — broken javadoc links to a
  method that no longer exists. Deleted both blocks, keeping only the current Vietnamese doc.
- **`ExpenseReferenceOptionResponse.amount`'s field comment still cited `refundCash`/
  `refundBanking`**, both dropped from `Return` in `b81e80b` (§0.2) months before this pass. Replaced
  with a description of what the field actually holds today (the amount still available to commit
  against the document).
- **`ProductRowResponse.productId`'s comment claimed the PK was a `varchar`**; the field is
  `Integer`. Fixed.
- **`ProductPageController`'s class javadoc still named the long-removed `Chief Pharmacist` role.**
  Fixed to Owner/Pharmacist/Accountant, matching the real `@GetMapping` role prefixes.
- **One dead commented-out HTML block deleted** from `product/create.html` — an old "Mã hàng" input
  box, same pattern as the Expense module's already-removed placeholder field.

No logic changes anywhere in this pass — `git diff` was checked to confirm every changed line is a
comment (`//`, `*`, `/**`, or `<!-- -->`), and `mvn compile` / the full test suite were run after
each module to catch anything that slipped.

### 2.12 Product / Purchase Invoice UI pass + Financial Setting balance fields (`hoangnv04`, 2026-08-02)

Full mechanism write-up in §0.10 — this is the build log. Ran in two rounds: a first pass building
the plan-approved feature set, then a follow-up round of user-requested tweaks on top of it, plus
one bug fix reported after the follow-up shipped.

**Round 1** (planned, see the plan file convention — `Product Detail/List/Create`, `Purchase Invoice
List/Detail/Create`, `Financial Setting` balance fields): built as described in §0.10. Along the
way, hit and fixed the pre-existing `mvn compile` break from a teammate's
`Taxperiodsnapshot.cashBalanceAtPeriodEnd` → `quarterlyRevenue` rename (§7.3), and discovered a real
circular bean dependency the moment `PurchaseinvoiceService` first depended on `PricesettingService`
for the (at the time) editable "Giá bán" write-through — worked around with `@Lazy` on that
constructor param.

**Round 2** (user asked for four adjustments after trying round 1 in the browser):
1. Product List's "Nhà sản xuất" dropdown → folded into the expandable search as free-text
   `producerQuery`, replacing the exact-id `producerId` filter entirely.
2. Purchase Invoice List's inline from/to date boxes → a small arrow-pointer popover anchored under
   the preset dropdown, shown only for "Tùy chọn"; added Tháng trước/2 tháng trước/3 tháng trước
   presets.
3. Purchase Invoice Create's editable "Giá bán" column → reverted to display-only (a line under the
   product name), which also meant reverting the `PricesettingService` write-through and dependency
   from round 1 — the circular-dependency workaround (`@Lazy`) went away with it, since the
   dependency itself is gone.
4. Financial Setting's money fields → switched to `data-money`/`fragments/money-input` (this screen
   had never used that fragment; every field was plain `type="number"` before).

**Bug fix, reported after round 2**: the "Giá bán" reference (by then display-only) was still keyed
off the **base unit**, but the user pointed out it should follow the **đơn vị mặc định** — "là đơn
vị nhập hàng đó" (the same unit the "Đơn vị" column already shows). Root cause: `getSellPriceByProduct()`
filtered for `isBaseUnit` directly instead of reusing the `isDefault > isBaseUnit > lowest id`
priority `resolveImportUnit()`/`getImportUnitNameByProduct()` use — the two only ever agreed by
coincidence, on products whose base and default unit happen to be the same row. Fixed by extracting
a shared `resolveImportUnitByProduct()` so both call sites resolve identically; added a regression
test with a product whose base and default units are deliberately different, asserting the price
comes from the default one.

Verified in the running app via `javascript_tool` against the live dev server for every item above
— not just unit tests — including creating and then cancelling a throwaway purchase invoice to
confirm the write-through (round 1) actually moved `Productunit.sellPrice` with the correct
base-unit cascade before it was reverted, and confirming the bug-fix scenario live on the seed data
(`Ampicillin MKP 500`: base unit "Viên" 500đ, default unit "Hộp" 50.000đ — the reference now shows
50.000đ, matching "Đơn vị: Hộp").

---

### 2.13 Teammate work, 2026-08-02 evening → 2026-08-03 (`3753922`, ~40 commits merged from `develop`) + this session's documentation pass

Full mechanism write-ups for the two largest pieces are in §0.11/§0.12; this is the build log for
everything in the batch, module by module. Authors: nguyentruong16, DO NGOC DUC, Vu Cuong Thinh.

- **Accountant Dashboard** (`13111a4`) — closes former §6 item 2. New `service/AccountantDashboardService`
  (1253 lines) + `dto/response/AccountantDashboardResponse` (246 lines), route `/accountant/dashboard`,
  new `templates/dashboard/accountant-dashboard.html` (1018 lines). 6 metric cards (phiếu chi chờ
  duyệt, công nợ còn lại, thu hôm nay, hóa đơn chưa thanh toán, phiếu nhập trong ngày, hóa đơn VAT cần
  xử lý), a weekly Thu/Chi/Công nợ line chart (reuses `DashboardView.DashboardChart`/`ChartSeries`
  rather than inventing a new shape), up to 6 alerts (built from 5 filtered/sorted lists, falls back
  to a single success item), up to 8 recent activities merged from 5 sources, 4 quick actions.
  **`PlaceholderController.java` is now fully deleted** (`git log --diff-filter=D` confirms; not "down
  to 1 route" any more, §5 rewritten). 🟠 Every section is computed fresh on every page load via
  `findAllWithRelations()` (new, on `ExpenseRepository` too) on Invoice/Income/Expense/
  Purchaseinvoice/Return — full-table loads, no caching, no date bounding. Harmless on this seed-only
  dev DB; worth revisiting if transactional volume grows. Zero tests. `NavigationRenderingTest`
  (git-tracked, one of only 5) updated to add a `@MockitoBean AccountantDashboardService` stub.
- **Notification system — 1 producer → 3, plus pagination** (`1666ba3` + `ee84206`):
  - **New `service/WorkflowNotificationService`** (700 lines) — Return/Expense/StockCount/ShiftReport
    pending→approved/rejected, plus Income created (create-only, no approve/reject, matching §0.12).
    All the `NotificationType` constants it uses already existed as dead vocabulary before this
    commit; this is what makes them fire for the first time. Pattern: pending → notify all active
    Owner accounts (category `PHE_DUYET`); approved/rejected → `resolveAndSendResult()` first calls
    `resolveByReference(...)` to close the old pending notification, then notifies the original
    submitter. Confirmed callers: `ExpenseService`, `ReturnService`, `ShiftreportService`,
    `StockcountService`, `IncomeService`.
  - **New `service/InventoryNotificationService`** (522 lines) + **`InventoryNotificationScheduler`**
    (69 lines) — a third producer, scheduled. `scanStockAlerts()`: sums active-batch storage per
    product (`BatchRepository.sumActiveStorageGroupedByProduct()`, new) against `Product.minStock` →
    `LOW_STOCK`/`OUT_OF_STOCK`. `scanExpiryAlerts()`: iterates all batches with product eagerly
    fetched (`BatchRepository.findAllWithProductForNotifications()`, new) → `EXPIRING_BATCH`
    (30-day window) / `EXPIRED_BATCH`. Both constants also pre-existed as dead vocabulary. Runs on
    `ApplicationReadyEvent` and every 5 minutes via `@Scheduled(cron =
    "${app.notification.inventory-scan-cron}")` — **`ProjectApplication.java` gained
    `@EnableScheduling`** for the first time. New `application.properties` keys:
    `app.notification.expiring-batch-days=30`, `app.notification.inventory-scan-cron=0 */5 * * * *`.
    Fans out to Owner + Pharmacist, dedupes/self-resolves via `NotificationService.createIfMissing()`/
    `resolveByTypeAndReference()` (new).
  - `TaxRevenueNotificationService` (the previously-sole producer) is **unchanged**.
  - **List pagination**: `NotificationRepository.searchForAccount()` now returns `Page<Notification>`
    with a paired `countQuery`; `NotificationPageController` gained `page`/`size` (default 10, max
    50), auto-clamps out-of-range pages, and every read/dismiss action round-trips back to the same
    filtered/paginated view (`preserveListState()`) instead of resetting to page 1.
    `notification/list.html` gained a full numbered-window pagination control.
  - `isActionRequired()` tightened: previously any `_PENDING`-suffixed type counted; now requires
    category `PHE_DUYET` **and** the `_PENDING` suffix together.
  - **Zero tests for any of the three producers**, despite the module tripling in scope. `Glob` for
    `*NotificationServiceTest*`/`*WorkflowNotification*`/`*InventoryNotification*` under
    `src/test/java/com/example/project/service/` returns nothing.
- **Employee Note — brand-new feature, undocumented before this update** (`edb41ba` + `f5a9a4d`): the
  Owner writes free-text notes (`@NotBlank`, ≤2000 chars) about a specific staff account from a
  hand-rolled modal on `owner/users.html` (688 then 370 more lines added across the two commits); the
  employee reads their own notes read-only on `/profile` under a new "Ghi chú nhân viên" card. Built
  on a **pre-existing** `Employeenote` entity/table — `git log --follow` shows the entity predates
  this session's baseline unchanged, and `employeenote` already exists in `current-database.sql`
  (confirmed: `noteID`/`accountID`/`date`/`content`, no new migration) — the classic "generated stub
  → real logic" pattern this codebase repeats. **Not tied to the `DESTROY_EMPLOYEE_FAULT`/employee-
  liability gap** (§6 item 1) — a general HR note, no reference to it anywhere in
  `StockadjustmentService`/`IncomeService`.
  - `controller/EmployeenoteController` (131 lines) — `@RestController` at `/owner/users`:
    `GET/POST /{accountId}/notes`, `PUT /{accountId}/notes/{noteId}`. **No DELETE.** Owner-only via
    the `/owner/**` URL-prefix gate (a comment in the file spells this out explicitly).
  - `service/EmployeenoteService` (188 lines) — `getByAccountId()`, `create()`, `update()` (only ever
    changes `content`, preserves the original `date`), `normalizeContent()` (trim + length guard).
    `create()` resolves the target's role via `AccountpermissionRepository` purely to word the
    notification, then calls `notificationService.createIfMissing(...)` with dedupe key
    `EMPLOYEE_NOTE_CREATED_{noteId}_ACCOUNT_{accountId}`.
  - `repository/EmployeenoteRepository` — `findByAccountID_IdOrderByDateDescIdDesc` (newest first,
    id tiebreaker, same convention as elsewhere), `findByIdAndAccountID_Id` (scopes an update so a
    mismatched account/note pair 404s instead of silently editing someone else's note).
  - `dto/request/Employeenote{Create,Update}Request` (18 lines each, single `content` field),
    `dto/response/EmployeenoteResponse` (60 lines — `id`/`accountId`/`date`/`dateDisplay`/`content`).
  - `ProfileController` reads notes for the *current* account directly via the service
    (`model.addAttribute("employeeNotes", employeenoteService.getByAccountId(...))`), bypassing the
    `/owner/**` gate — how a Pharmacist/Accountant sees their own notes despite no `/owner/**` access.
  - `constant/NotificationType.EMPLOYEE_NOTE_CREATED` and `constant/NotificationReferenceType.EMPLOYEE_NOTE`
    — both new, additive.
  - Zero tests — `Glob` for `*Employeenote*` under `src/test/java/com/example/project/**` returns
    nothing. This is teammate work (`CuongthinhVu`), not `hoangnv04`'s.
- **Financial Setting fund reconciliation + tax formula overhaul** — see §0.11 in full.
- **Income role-gate removal + ShiftReport bug fix + Accountant shift-report access** — see §0.12.
- **Invoice / Print / Return** (`a512f62`, `f2975ae`, `5d77b06`, `0f52652`, `b7e492b`, `44e8268`):
  - `service/InvoiceService` (+327 lines, the single biggest file diff in this whole batch): the fund
    hook and invoice-type/numbering/prescription changes are §0.11; the print-DTO rewrite added
    `moneyAmountInWords()` (full Vietnamese number-to-words reader), `buildTaxAuthorityCode()`
    (`M{kind}-{yy}-{series}-{id:011d}` using `Financialsetting.vatInvoiceSeries`, default `"BGALS"`),
    buyer legal block (company vs. "Khách lẻ không lấy hóa đơn"), `paymentMethodShort()`,
    `formatDateLong()`/`formatSignedAt()` — all private, gated behind `signed`; the constructor
    gained `FinancialsettingService` and nothing else public. `SellProductOptionResponse` gained
    `requiresPrescription`; `listSellProducts()` switched to `findAllWithRelations()`.
  - `dto/response/InvoicePrintPageResponse` — dropped `dateDisplay`/`taxCode`; gained
    `invoiceSerialNumber`, `signed`, `dateLongDisplay`, `taxAuthorityCode`, `signedAtDisplay`,
    `pharmacyAddress`/`pharmacyLocationCode`/`pharmacyEmail`/`pharmacyBankAccountNumber`/
    `pharmacyBankName`, `buyerCompanyName`/`buyerTaxCode`/`buyerAddress`, `paymentMethodShort`,
    `totalInWords`.
  - `templates/invoice/print.html` — near-total rewrite (+570 lines): formal Vietnamese e-invoice
    layout, double-bordered frame with a faint watermark, `Mã CQT:` line (shown only when `signed` +
    `embed` param), seller/buyer legal blocks, amount-in-words, two-state signature block. No QR code.
  - `templates/invoice/create-invoice.html` (+182) — client-side mirror of the server prescription
    gate (`canSellPrescriptionProducts()`); product search switched substring `includes()` → prefix
    `startsWith()`; submit button starts `disabled`.
  - `templates/invoice/invoice-detail.html` (+212) — fixed the `returnStatus = FULL` display bug: a
    full return with refund rate <100% left a zero-qty replacement line reading like a data bug
    (`0 × price`); new `.retained-badge` ("Phần giữ lại (không hoàn)") explains it, shown on
    replacement-invoice lines with `quantity == 0`. Also added an "Xem hình ảnh hóa đơn" iframe
    preview modal (`/{id}/print?embed=1`, signed invoices only).
  - `service/ReturnService` (+72 lines total across several commits, not just `0f52652`): the
    `INVOICE_TYPE_VAT` backward-compat recognition (§0.11), new `WorkflowNotificationService` hooks,
    new `isTaxExempt()` (mirrors `ReturnPurchaseService.revenueGroup()`, reads
    `Financialsetting.revenueGroup` via `TaxRevenueGroup.isTaxExempt`), and the same 8-digit invoice-
    numbering rewrite `InvoiceService` got. `ReturnPurchaseService` (+12) gained `isTaxExempt()` too.
    `ReturnDetailPageResponse`/`ReturnPurchaseDetailPageResponse` both gained a `taxExempt` boolean
    alongside `deductionGroup` — **refines, not reverses**, the "groups 1/2 write zeros" rule (§3):
    the zero-writing math is unchanged, but the UI now distinguishes Group 1 (exempt, tax rows
    hidden) from Group 2 (zero shown with a "chưa khấu trừ" note) instead of showing both identically.
    `return/{create,detail}.html` and `return-purchase/{create,detail}.html` updated accordingly.
  - `repository/InvoiceRepository.findInPeriod` → renamed `findValidInPeriod` (§0.11);
    `InvoicedetailRepository.sumCostOfGoodsSoldInPeriod` got the same filter, its scope also widened
    from "group-3 only" to "group 3 always, group 2 when opting into the profit method";
    `ReturndetailRepository.sumRestockedCostInPeriod` **removed** (functional refactor, see §0.11).
  - **No new tests**: `InvoiceServiceTest.java` still has only 2 `@Test` methods (194 lines total,
    matches the long-standing "only 2" note); no `ReturnServiceTest`/`ReturnPurchaseServiceTest` exist
    at all — both remain zero-tests/top-risk despite this window's new logic.
- **Procurement plan** (`c152560` + `120d9ba`): search switched from substring (`contains`) to
  **prefix match** (`startsWith`) on code/name/barcode; new combo-product exclusion — any product
  whose `Type.sortType == "combo"` is filtered out of `findProducts()`, and a hard validation error
  (`"Không thể dự trù sản phẩm loại combo"`) fires if one slips through on save; product loading
  switched to `findAllWithRelations()`; detail-line UI (`create-`/`update-procurementplan.html`, +38
  lines each) now groups rows by supplier (`sortDetailLinesBySupplier()`, stable via a new
  `dataset.lineOrder`), re-triggered whenever a row's supplier changes.
- **Customer required-fields tightening** (`51ac42b`): new `CustomerService.validateRequiredFields()`
  makes `taxCode`/CCCD **and `address` mandatory** for every customer (previously optional — the
  javadoc explicitly calls the old "skip when blank" branch dead code now), and
  `bankAccountNumber`+`bankName` mandatory for company customers only. **This is a separate, narrower
  rule from the existing uniqueness-checking note in §3** — that note is about how blank values are
  compared for duplicates, not about whether blank is allowed at all (it no longer is).
  `customer/{create,detail}.html` add `*`/`required` markers, with JS toggling `required` on the two
  bank fields by customer type so a hidden field can't block submit.
- **Stock Count** (`bd6d3a1`, "fix stock count alert" — turned out to be a UI-consistency fix, not a
  logic bug): replaced raw `onclick="return confirm(...)"`/bare alert divs on
  `stock-count/{create,detail,list}.html` with the standard `fragments/toast::flash` +
  `fragments/confirm::confirm` pattern used everywhere else. Separately, `StockcountService` gained
  the `WorkflowNotificationService` integration (pending/approved/rejected) — a 4th consumer.
- **Search behavior change on Invoice Create** (`120d9ba`): product search now **actively gates
  prescription-required products out of search results and cart-adding entirely** unless a valid
  prescription code is entered (`isSellableProduct()`, new inline unlock hints) — previously the
  checkbox+code combination was only format-validated at submit time.
- New repository methods: `ExpenseRepository.findAllWithRelations()` (feeds the Accountant
  Dashboard), `IncomeRepository.sumByTypeInPeriod(incomeType, statuses, from, to)` (feeds
  `TaxperiodsnapshotService`'s supplier-commission/employee-income revenue figures, §0.11).

**This session's own work (`hoangnv04`, 2026-08-03) — documentation pass, then a correction:**
re-read the full diff since the 2026-08-02 snapshot (43 commits, 8,389 insertions across 84 files),
dispatched 7 parallel research passes over accountant-dashboard/notifications/employee-note/
financial-settings-&-tax/income-&-shiftreport/invoice-&-print-&-return/misc clusters, cross-verified
the two biggest claims by reading the actual service code directly (`FinancialsettingService.
applyFundDelta`/`.adjustFundBalances`, the `Financialsetting` entity diff, `TaxRevenueGroup`), ran
`mvn test` to get real numbers (had to fix `InvoiceServiceTest.java` first — see §7.3 instance 8),
and discovered a live-DB schema gap (`financialsetting.revenueGroupConfirmed`) via the resulting
`ProjectApplicationTests` failure. Rewrote `CLAUDE.md` and this file to match.

**Follow-up correction, same session**: the user flagged that `Financialsetting.version`
(`@Version`) and `.revenueGroupConfirmed` were added by the teammate commit without approval, and
asked for both removed — explicitly without touching the database or writing a migration. Reverted
both from the entity, reverted `FinancialsettingService`'s two fund-mutating methods and
`isRevenueGroupLocked()`/`saveSettings()` back to their pre-`ef633bf` shape (plain `.save(...)`,
single-reason lock), fixed the 5 now-obsolete tests in `FinancialsettingServiceTest.java` (29→24),
and adjusted the `financial-setting.html` confirm-modal JS (it had warned "sẽ được khoá vĩnh viễn"
for the revenue-group select too, which would now be false — narrowed the warning to the two fund
fields only, which still genuinely lock on first save). **This incidentally fixed the schema gap
too** — `ddl-auto=validate` doesn't complain about a DB column no entity maps, so removing
`revenueGroupConfirmed` from the entity made the missing-column check stop looking for it.
`ProjectApplicationTests` passes again and the real app boots, without anyone touching the live DB
or `current-database.sql`. Re-ran the full suite to confirm: 517/6/30 → **512/6/29**. Rewrote both
`CLAUDE.md` and this file again to reflect the correction throughout, not just append a note.

**Did not touch `current-database.sql`** in either pass, per explicit instruction. Did not add tests
for any of the newly-landed, zero-coverage teammate work (Accountant Dashboard, Employee Note,
`WorkflowNotificationService`, `InventoryNotificationService`) — that's feature development, out of
scope for a docs-only pass.

### 2.14 Purchase Invoice approval workflow (`hoangnv04`, 2026-08-04)

Full mechanism write-up in §0.14 — this entry is the build-log summary. BA request: when the pharmacy
has an active Accountant, the Accountant creates purchase invoices and the Owner only duyệt/từ chối;
Owner keeps the old direct-create path only while there is no active Accountant; Pharmacist loses
create entirely.

- `PurchaseInvoiceStatus.PENDING_APPROVAL = "Chờ duyệt"` added alongside the pre-existing but
  previously-unused `DRAFT = "Nháp"`.
- `PurchaseinvoiceService`: `hasActiveAccountant()`/`canCreatePurchaseInvoice()` (mirrors
  `TaxperiodsnapshotService.canClose`, §2.1); `createPurchaseInvoiceDraft()`/
  `createPurchaseInvoiceForApproval()`; `updatePurchaseInvoiceDraft()`/`submitPurchaseInvoiceDraft()`;
  `getDraftEditForm()`; `approvePurchaseInvoice()`; `rejectPurchaseInvoice()`;
  `deletePurchaseInvoiceDraft()`. Refactored the line-prep/detail-persist/stock-receipt logic out of
  `createPurchaseInvoice()` into shared private helpers (`prepareInvoiceHeader`, `buildInvoiceEntity`,
  `persistDetailLines`, `receiveStockForInvoice`) so the no-Accountant Owner path and the new
  `approvePurchaseInvoice()` path both receive stock through the identical code.
  `cancelPurchaseInvoice()` gained Nháp/Chờ duyệt guards. `AccountpermissionRepository` added to the
  constructor.
- `PurchaseInvoicePageController`: Owner's create routes gated behind `canCreatePurchaseInvoice`; new
  Accountant create/edit/delete routes; new Owner approve/reject routes; detail page exposes
  `canApprove`/`canReject`/`canEditDraft`/`canDeleteDraft`; Pharmacist's create routes (and its
  create-only `procurement-plan-details` endpoint) deleted.
- `purchase-invoice/create.html` reused for both create AND edit, and for both the Owner's
  single-button flow and the Accountant's dual-button (Lưu Nháp / Nộp duyệt) flow, via new
  `formAction`/`editMode`/`dualSubmit` model attributes. `detail.html` gained Duyệt/Từ chối/Sửa phiếu
  nháp/Xóa phiếu nháp buttons + matching confirm modals, all role/status-gated; the pre-existing Hủy
  phiếu nhập button/modal now excludes Nháp/Chờ duyệt.
- `PurchaseInvoiceCreateRequest`/`PurchaseInvoiceDetailCreateRequest`: added
  `@DateTimeFormat(pattern = "yyyy-MM-dd")` to every `LocalDate` field — a real bug found via live
  testing, see §0.14D.
- `SidebarMenuService.accountantMenu()`: new "Hàng hóa" `linkGroup` → `/accountant/products`.
- `entity/Purchaseinvoice.approvedAt`: `@NotNull`/`nullable = false` removed (user-approved schema
  change, live DB + `current-database.sql` hand-patched to match, no migration).
- `PurchaseinvoiceServiceTest` 53 → **70**; fixed the stale `SidebarMenuServiceTest.
  accountantMenu_matchesTheAgreedGroupOrder` assertion in passing (missing "Báo cáo ca").
- `mvn test` baseline: 519 → **536** run, 6→**5** failures (net −1, the stale-test fix), 29 errors
  unchanged.
- **Verified live, end to end**, both roles, using the seeded `thinhvc04`/`hoangnv05` Accountant
  accounts and `ngoctn01` (Owner) — full walkthrough in §0.14. Left the dev DB with only the
  pre-existing seed data afterward.

---

## 3. Business decisions currently in force

- **Single store, exactly 3 roles**: `OWNER`, `PHARMACIST`, `ACCOUNTANT`, stored as a plain
  `accountpermission.role` **string** (not an enum, not a lookup table).
- **A return slip computes, it does not pay** (§0.2).
- **A phiếu chi is one payment and is immutable** (§0.3) — replaces the old "amount is an obligation"
  rule. **A phiếu chi carries no debt state** (§0.3b). **Only `GOODS_PAYMENT` links a purchase
  invoice** (§0.7).
- **Only the Owner pays cash** (§2.2) — unchanged.
- **🔴 A purchase invoice's creation flow now depends on whether the pharmacy has an active
  Accountant (2026-08-04, §0.14) — REVISES the old universal "always Nợ, no approval step" rule
  (§2.3).** No active Accountant: Owner creates directly, still one step, still lands on `paid = 0` /
  `"Nợ"` immediately — that half is unchanged. Active Accountant: the Accountant creates a Nháp or
  submits straight to Chờ duyệt, **no stock is received and no debt exists until the Owner duyệt**
  (or it's rejected back to Nháp, or deleted outright while still Nháp). Pharmacist can no longer
  create at all. `Purchaseinvoice.approvedAt` (§0.13B) is now nullable and wired — closed, see §0.14.
- **Money is disbursed only once a slip is approved** — `ExpenseService.disbursedAmount()` returns
  `paid` for `AWAITING_PAYMENT`/`COMPLETED` and `ZERO` otherwise.
- ~~Everything touching a purchase invoice is `OPERATIONAL`~~ — **reversed 2026-07-31, see §0.7.**
  `PURCHASE_LINKABLE` is now `GOODS_PAYMENT` only; `DEBT_PAYMENT` is still deliberately excluded.
- **"Chi phí vận hành" has no sub-types.** Lương / điện / nước are just what the user types into
  `reason`.
- **🔴 Tax: quarters only, group changes are automatic, and as of 2026-08-03 groups 2/3 share the
  SAME GTGT method** (§0.9/§0.11/§2.10/§2.13 — the group-change automation reverses the "manual" rule
  §2.1 shipped with; the formula sharing then revises the original "group 3 uses deduction" spec).
  Nhóm 1 (<1 tỷ) **miễn thuế hoàn toàn**. Nhóm 2 (1–3 tỷ) **and Nhóm 3 (3–50 tỷ) both pay GTGT = 1%
  doanh thu** now (`TaxRevenueGroup.DIRECT_VAT_RATE`) — deduction/khấu trừ is no longer used by
  anyone; `isDeductionGroup()` survives only for `ReturnPurchaseService`/display. TNCN: Nhóm 2 = 0.5%
  doanh thu by default, **now with an optional profit-based method** selectable via
  `Financialsetting.taxCalculationMethod`; Nhóm 3 = **17%** thu nhập chịu thuế (was 15% —
  `TaxRevenueGroup.GROUP3_PIT_RATE`) = doanh thu − giá vốn hàng bán − chi phí vận hành. Group 4 is
  out of scope. Chuyển nhóm: 1→2 áp dụng ngay từ quý vượt ngưỡng, retroactively rewriting the
  previous closed period; 2→3 giữ nhóm cũ đến hết năm, only auto-applies at the year's Q4 close.
- **Price Settings is a direct `Productunit.sellPrice` editor** — explicitly not a markup calculator.
  Money on that screen carries **no decimals** (HALF_UP to whole đồng); other modules keep 2.
- **Base-unit price cascade infers customization from formula agreement** — no DB flag.
- **`Batch.importPricePerBase` is GROSS** (VAT-inclusive) — confirmed team convention.
- **A display status comes from the stored column**, never re-derived. An unrecognised value renders
  verbatim with a distinct "unknown" badge.
- **`Return.appliedRefundRate`** is wired but hardcoded to `100.00` — V1 always refunds 100%.
- **"Ký hóa đơn"** is a status flip that also rewrites `invoicePattern`'s 2nd char (K→C) — not a
  cryptographic signature.
- **Shifts are created lazily on the first real sale or the first cash payout**, never at login, and
  only for Owner/Pharmacist. **Accountant never gets a shift.**
- **An Owner-submitted slip auto-approves**; anyone else's goes to the Owner.
- **Purchase Invoice cancel** is a one-way internal-correction flip.
- **Stock Adjustment: 7 types, 3 separate rule sets, and cancel is guarded** (§2.5). A completed
  slip can only be cancelled while its batches are untouched by later sales or adjustments.
- **Surplus of unknown origin becomes a new batch**, never an increment of an existing one (§2.5).
- **Employee reimbursement is an ordinary Income** linked 1:1 to a completed Stock Adjustment, and
  the shift report does not give it its own line (§2.5).
- **Return-Purchase writes VAT zeros on the lines too for groups 1/2**, not just on the total —
  otherwise the detail screen contradicts itself (§2.5).
- **Customer/Supplier uniqueness: the service layer is still the real gate.** ~~The DB has no UNIQUE
  constraint to fall back on~~ — **reversed 2026-07-31**: the live DB now carries real `UNIQUE`
  indexes (`supplier.phone`/`.email`/`.taxCode`, `customer.phoneNumber`/`.taxCode`), added by hand and
  now reflected in `current-database.sql`. It changes less than it sounds: `Supplier.java`/
  `Customer.java` don't declare `@Column(unique = true)`, so Hibernate is unaware of it and
  `CustomerService`/`SupplierService`'s own check is still what runs on every normal save (§2.5). The
  DB index is a backstop for a race the service check doesn't cover — and since the entity doesn't
  know about it, that race surfaces as a raw `DataIntegrityViolationException`, not the friendly
  per-field message. Nothing catches that today.
- **`Invoice.returnStatus` is a cache; `invoiceReturnCode()` is the truth** (§2.5).
- **Product identity (BA 2026-07-30): tên bắt buộc + duy nhất; barcode và số đăng ký chỉ kiểm khi
  có nhập.** `ProductService.validateUniqueness()` handles create and edit from one place
  (`productId == null` = create; on edit the record is excluded from its own check).
  **Bỏ trống nghĩa là "chưa khai báo", không phải một giá trị rỗng dùng chung** — kiểm cả khi trống
  thì sản phẩm thứ hai không có số đăng ký sẽ bị báo trùng với sản phẩm đầu tiên cũng không có, chặn
  nhầm một trường hợp hoàn toàn hợp lệ. The name comparison is left to the column collation
  `utf8mb4_0900_ai_ci`, so it is accent- **and** case-insensitive with no `LOWER()` in the query —
  a property of the schema, not the code.
- **Product identity (2026-08-02): `minStock`/`maxStock`/`producerId`/`origin`/`typeId` are now
  required too**, alongside the name/uniqueness rule above (§0.10/§2.12).
- **Purchase Invoice's "Giá bán" reference is the đơn vị nhập (import unit), never the base unit,
  and is display-only** (§0.10/§2.12) — `resolveImportUnitByProduct()` backs both the "Đơn vị"
  column and the "Giá bán" line/column so they always agree. Editing it was tried and reverted;
  nothing on this screen writes back to `Productunit.sellPrice`.
- **🔴 Financial Setting's `cashSafeBalance`/`bankAccountBalance` are auto-reconciled from real
  transactions, as of 2026-08-03** (§0.11/§2.13) — **REVERSES** the 2026-08-02 "manual figures, no
  auto-reconciliation, explicitly deferred" decision immediately above. `applyFundDelta()` (Invoice
  sale, Income completion, ShiftReport approval-discrepancy) and `adjustFundBalances()` (Expense
  approve/cancel) are the two write-through mechanisms; each balance field still locks permanently
  the first time it's saved non-null.
- **🔴 Income has NO approval gate for any role, as of 2026-08-03** (§0.12) — REVERSES the earlier
  "Owner auto-completes, everyone else needs approval" rule. Every submission auto-completes;
  `STATUS_PENDING` is dead vocabulary for new rows.
- **🔵 `Invoice.invoiceType` is always `"Bán hàng"` now, as of 2026-08-03** (§0.11) — the old group-3
  `"Hóa đơn GTGT"` branch was removed along with the tax-formula change. Invoice numbering also lost
  its `"HD"` prefix (bare zero-padded 8-digit number).
- **🔵 Prescription-drug gating is now actively enforced, not just validated at submit** (2026-08-03,
  §2.13) — `InvoiceService.invoiceContainsPrescriptionProduct()` blocks a sale server-side; Create's
  product search hides/blocks Rx products client-side until a valid code is entered.
- **🔵 Customer required fields expanded (2026-08-03, §2.13): `taxCode`/CCCD AND `address` are now
  mandatory for every customer**, bank account number/name mandatory for company customers —
  narrower than, and separate from, the Customer/Supplier uniqueness rule below.
- **🔵 Accountant has read-only access to ShiftReport** (2026-08-03, §0.12) —
  `/accountant/shift-reports`, list + detail only. **Reverses the Role access table's old "ShiftReport:
  Owner + Pharmacist" line** (updated below). Paired with a "collect cash shortage" flow raising a
  `SHIFT_SHORTAGE` Income slip.

### Role access

| Module | Owner | Pharmacist | Accountant |
|---|---|---|---|
| Expense (Phiếu chi) | ✅ | ❌ no route | ✅ (approve/reject Owner-only; cash Owner-only) |
| Income (Phiếu thu) | ✅ | ✅ | ✅ |
| Tax period (Kỳ thuế) | ✅ view; close only if no active accountant | ❌ | ✅ view + close |
| Return (Trả hàng) | ✅ | ✅ | ✅ list/detail only |
| Return-Purchase (Trả NCC) | ✅ only | ❌ | ❌ |
| Purchase Invoice | ✅ | ✅ (incl. create) | ✅ list/detail |
| Debt (Công nợ) | ✅ | ❌ | ✅ |
| ShiftReport | ✅ | ✅ | ✅ list/detail only, since 2026-08-03 (never gets a shift itself) |
| Approval | ✅ only | ❌ | ❌ |
| Price Settings | ✅ only | ❌ | ❌ |

---

## 4. Dropped / de-scoped — do NOT rebuild without an explicit request

- **`PlaceholderController` is fully deleted (2026-08-02/03, §2.13), not just trimmed to 1 route.**
  Every menu item — including the Accountant's `Tổng quan`, the last holdout — now resolves to a real
  controller. See §5, rewritten.
- **🔴 Income's `STATUS_PENDING`/approval gate for non-Owner roles (2026-08-03, §0.12)** — every
  submission now auto-completes; the status constant and its counters are dead code for new rows,
  kept only to render pre-2026-08-03 slips. Do not build a new "submit for approval" UI for Income
  without asking first.
- **The `"Hóa đơn GTGT"` invoice-type value for new sales (2026-08-03, §0.11)** — every new sale
  invoice is `"Bán hàng"` now; the old value only appears on pre-`ef633bf` rows.
- **`Return.refundCash` / `refundBanking` / `refundCredit`** — dropped by design (§0.2).
- **`Return.expenseID` / `Return.incomeID`** — dropped earlier (`38515f8`). `Expense.returnID` /
  `Income.returnID` are the only link directions now, which is why each duplicate guard reads from
  its own side.
- **`ShiftReport.approvedBy`** — the physical column is gone from the DB. Only `approvedAt` is kept.
- **`StockAdjustment.createdBy` / `approvedBy` / `approvedAt`** — dropped from the entity and the
  live DB on 2026-07-29 (§0.6). The slip no longer records its creator at all. The docx has not
  caught up.
- **`Branch`** — entity, repository, service and controller are **deleted**. No `branchID` anywhere.
  Do not recreate regardless of what the docx still describes.
- **`CHIEF_PHARMACIST`** — fully removed as a role; its screens were absorbed into Owner.
- **`ProductWarranty`, `DailyReport`** — absent from the codebase entirely.
- **Tax group 4 (>50 tỷ, monthly periods)** — explicitly out of scope, which is why a period is
  always a quarter. `TaxRevenueGroup.isDeductionGroup()` is still written as `>= 3` so a
  hand-inserted group-4 row does not silently fall into the no-deduction path.
- **Personal income tax for group 4 (20%)** — not modelled.
- **Stock Adjustment's approve/reject workflow** — removed 2026-07-28.
- **Price Settings as a markup/cost calculator** — deliberately pivoted away from.
- **A "bù trừ công nợ" input on the Expense form** — `paidByCredit` exists and is written as `0`, but
  nothing collects a value.
- **A human-chosen `nextPeriodTaxType`** — removed from `TaxPeriodCloseRequest`/
  `TaxPeriodUpdateRequest` entirely on 2026-08-01 (§0.9). The "Nhóm áp dụng cho kỳ sau" dropdown on
  both Tax Period screens is gone, replaced by read-only computed text. Do not re-add it —
  `TaxperiodsnapshotService.autoNextGroup()` is now the only thing that decides the value.
- **A freely-editable "Nhóm doanh thu" on Financial Settings** — as of 2026-08-01 (§0.9) the field
  locks (`disabled`) the moment any tax period has ever closed; it is only editable before that, to
  seed the very first period.
- **Sidebar entries removed by the 2026-07-25 redesign** (screens still work by direct URL):
  Owner `Danh sách hóa đơn VAT`, `Báo cáo tổng hợp theo ngày`; Pharmacist `Loại hàng`,
  `Nhà sản xuất`, `Danh sách vị trí`, `Nhà cung cấp`, `Danh sách phiếu nhập`, `Điều chỉnh kho`;
  Accountant `Loại hàng`, `Nhà sản xuất`, `Nhà cung cấp`, `Thiết lập tài chính`,
  `Báo cáo ngày`, `Hóa đơn VAT`. **Accountant `Hàng hóa` was re-added to the sidebar 2026-08-04, §0.14**
  — no longer in this dropped list.
- **Pharmacist's Purchase Invoice create access, and its create-only `procurement-plan-details` AJAX
  endpoint (2026-08-04, §0.14)** — `/pharmacist/purchase-invoices/create` (GET+POST) deleted outright,
  not hidden. Explicit user decision, not a side effect — do not re-add without asking. List/detail/
  print access for Pharmacist is unaffected.

---

## 5. Sidebar — current per-role structure

`SidebarMenuService` is the single source of truth. `fragments/sidebar.html` renders purely from the
service's data — **change the service, not the template.** `linkGroup` renders a plain link;
`menuGroup` renders a collapsible section even with one child (deliberate, matches the mockups).

**Owner** — Tổng quan · Giao dịch (Bán hàng, Danh sách hóa đơn, Danh sách trả hàng) · Quản trị
(Danh sách người dùng, Bảng phân quyền) · Hàng hóa (Danh sách hàng hóa, Danh sách loại hàng,
Danh sách nhà sản xuất, Danh sách vị trí) · Cung ứng (Danh sách nhà cung cấp, Danh sách phiếu nhập,
Danh sách dự trù, Danh sách trả hàng NCC) · Kho (Danh sách điều chỉnh tồn, Danh sách kiểm kho) ·
Tài chính (Danh sách công nợ, Danh sách khoản thu, Danh sách khoản chi, **Kỳ thuế**, Thiết lập giá,
Thiết lập tài chính) · Khách hàng · Báo cáo ca · Phê duyệt

**Pharmacist** — Tổng quan · Bán hàng · Giao dịch (Danh sách hóa đơn, Danh sách trả hàng) · Hàng hóa
· Kho (Danh sách kiểm kho) · Tài chính (Danh sách khoản thu) · Khách hàng · Báo cáo ca

**Accountant** — Tổng quan (now a real dashboard, §2.13) · Tài chính (Danh sách công nợ, Danh sách
khoản thu, Danh sách khoản chi, **Kỳ thuế**) · **Hàng hóa (new 2026-08-04, §0.14 — `/accountant/products`,
list/detail only)** · Cung ứng (Danh sách phiếu nhập — **create moved here 2026-08-04 when this role
has an active account, §0.14**) · Khách hàng · Giao dịch (Danh sách hóa đơn, Danh sách trả hàng) ·
**Báo cáo ca (new 2026-08-03, §0.12 — read-only list/detail, Accountant never runs a shift itself)**

**`PlaceholderController` is FULLY DELETED (2026-08-02/03, §2.13)** — was "down to ONE route" as of
2026-07-30 (`/accountant/dashboard`), now zero. Every menu item across all three roles resolves to a
real controller; the class itself no longer exists in the codebase (`git log --diff-filter=D`
confirms). The eight routes deleted earlier (2026-07-30) were `/owner/suppliers`, `/owner/customers`,
`/pharmacist/customers`, `/owner/vat-invoices`, `/owner/daily-reports`, `/accountant/vat-invoices`,
`/accountant/daily-reports`, `/pharmacist/stock-outs/create` — all confirmed unreferenced and 404 at
the time. The ninth and last, `/accountant/dashboard`, was replaced by
`service/AccountantDashboardService` + `RoleDashboardController` (§2.13) on 2026-08-02/03, closing
what was §6 item 2.

Every sidebar route was re-checked on 2026-07-30 by logging in as each seed account:
**Owner 26/26, Pharmacist 10/10, Accountant 10/10 — all 200.** Not re-verified this session (the new
Accountant "Báo cáo ca" entry and the real dashboard route were not re-walked per-role) — worth a
quick pass next time the app is confirmed booting again (see the blocking schema issue, §0.11).

**The topbar page title also comes from the sidebar menu.** A screen whose URL is not a menu item
shows a fallback title — that is why adding the sidebar entry fixed the tax screen's header.

---

## 6. Database ↔ business mismatches still OPEN

Ordered newest/most concrete first.
1. **🔴 A `DESTROY_EMPLOYEE_FAULT` slip can never be billed to an employee.**
   `IncomeService.matchesResponsibleEmployee()` (`:951`) tries `adjustment.expenseID.accountID`
   first and falls back to `stockCountID.createdBy`. But **`StockadjustmentService` explicitly calls
   `setExpenseID(null)` on both create paths** (`:620`, `:739`), and **`Expense.accountID` is never
   written by any code** (item 8) — so the first branch is unreachable. Only `COUNT_DECREASE` slips
   resolve an employee, and they resolve to whoever *ran the stock count*, who is not necessarily at
   fault. `DESTROY_EMPLOYEE_FAULT` has neither a stock count nor an expense, so it never appears in
   the employee-reimbursement picker — even though `EMPLOYEE_LIABLE_TYPES` names it as one of
   exactly two liable types. Either something must populate `Stockadjustment.expenseID` /
   `Expense.accountID`, or the slip needs to carry the liable employee itself (which is awkward now
   that `createdBy` is gone). **Ask the BA / the spec owner before picking one.**
2. ~~**🟠 The Accountant has no dashboard.**~~ **CLOSED 2026-08-02/03 (§2.13).**
   `service/AccountantDashboardService` (1253 lines) is real, `/accountant/dashboard` renders it, and
   `PlaceholderController` is fully deleted (§5). 🟠 New, smaller gap in its place: every section is
   computed fresh on every page load via full-table `findAllWithRelations()` scans — no caching, no
   date bounding. Fine on this seed-only dev DB; revisit if transactional volume grows. Zero tests.
3. **🔴 `Dac_ta_Income_StockAdjustment.xlsx` is cited throughout the code but is not in the repo.**
   `StockadjustmentService`'s class javadoc (sheet 02), `EMPLOYEE_LIABLE_TYPES` (sheets 01/03),
   `VAT_OUTPUT_TYPES` (sheet 03) and `StockAdjustmentCreateRequest.unknownOriginBatchIds`
   (sheet 06) all defer to it. `docs/context/` holds only the four known files. It is the authority
   for everything in §2.5 — get a copy before changing that area.
4. **🟠 The .docx contradicts the code in five places, and it keeps growing** (§0.1b). Two entries
   have been corrected over time (`StockAdjustment` on 2026-07-29, `Batch.version` on 2026-07-31), but
   each rebuild has so far picked up new drift faster than it fixes old drift — currently
   `Invoice.status`, two `Expense` entries, and the still-missing `Invoice`/`Purchaseinvoice.version`.
5. **Thuế TNCN is modelled only for groups 2 and 3.** Group 4's 20% is not implemented, and the
   group-3 cost basis (giá vốn + chi phí vận hành không gắn phiếu nhập) is a choice made 2026-07-28,
   not something the docx spells out. Re-confirm with the BA if the number is ever disputed.
6. **A group-1 period is generated as a quarter, but the docx says group 1 needs no start/end**
   (i.e. an annual period). Kept quarterly on purpose so the period chain stays uniform — a yearly
   row sitting between quarterly ones would make `previousSnapshot` and the carry-forward ambiguous.
   Harmless while group 1 declares nothing, but it is a deliberate divergence from the document.
7. ~~**`FinancialSetting.balanceUpdatedAt` has no automatic writer / nothing derives the balances.**~~
   **REVERSED 2026-08-03 (§0.11/§2.13) — not closed, actively different.** `applyFundDelta()`
   (Invoice sale / Income completion / ShiftReport approval-discrepancy) and `adjustFundBalances()`
   (Expense approve/cancel) now keep both balances live, both via plain `.save(...)` after the
   `@Version`/`revenueGroupConfirmed` correction (§0.11) — **new gap in its place**: neither method
   catches `ObjectOptimisticLockingFailureException` any more (there's no version column left to
   conflict on), so a genuine concurrent-edit race on this row surfaces as a raw exception, not a
   friendly flash message. This is a deliberately accepted, smaller-scale version of the original gap.
8. **`Expense.accountID` is never set.** The column and mapping exist (for "chi cho nhân viên nào"),
   `supplierID`/`customerID` are derived from the linked document, but nothing populates
   `accountID` — including for `EMPLOYEE_ADVANCE_REPAYMENT`, where it is the obvious fit. **As of
   2026-07-29 this is no longer cosmetic** — item 1 depends on it.
9. **`Stockadjustment.expenseID` is always written as `null`.** Both create paths call
   `setExpenseID(null)` explicitly, so the FK to Expense exists in the schema and is never used.
   Same root cause as item 1.
10. **Both the revenue-threshold warning and the 1→2 auto-transition only fire when a human opens or
    closes a tax period.** The features are built (§2.6, §0.9/§2.10) and work, but their only call
    sites are in `TaxPeriodPageController` — there is no scheduler. Revenue can sit above the
    threshold for weeks with nobody told **and the chain left un-corrected**, simply because nobody
    visited the Kỳ thuế screens. The 2→3 transition doesn't share this exposure (§0.9 explains why).
    Whether the lack of a scheduler is acceptable is a BA question, not a code one.
11. **A paid-out return still displays as "Nợ".** `ReturnStatus` has no "đã hoàn tiền" state. Deriving
    the display from the linked Expense is the cheap option; a real status column needs a migration.
12. **`StockadjustmentService`'s class javadoc still describes "the approve/reject workflow that
    actually moves stock"** — that workflow was deleted on 2026-07-28. `StockAdjustmentStatus`'s
    javadoc is the accurate account. Cosmetic, but it is the first thing a newcomer reads.
13. **`Invoice.rootInvoiceID`** is unused. Not confirmed to be for adjustment-invoice chain tracking.
14. **`accountpermission.accountPermissionID`** is AUTO_INCREMENT in the DB but the app assigns the
    PK manually via `findMaxId()+1`.
15. **`Product.vatRateOverride`** has no UI to set it; read only by `InvoiceService` and
    `StockadjustmentService` (sales side only — `PurchaseinvoiceService` deliberately uses
    `Type.defaultVATRate`).
16. **Product List's "Yêu cầu đơn thuốc" column always renders "—".**
17. **"Nhóm mặt hàng"** is derived from `Type.sortType`, not its own column.
18. **Cross-module status-string inconsistency.** `ShiftReportStatus.APPROVED = "Đã duyệt"` vs
    `StockAdjustmentStatus` (which no longer has an approved state at all). `Income` keeps
    `STATUS_COMPLETED_LEGACY = "Duyệt"` alongside `STATUS_COMPLETED = "Hoàn thành"` because the label
    was renamed mid-development and old rows were never migrated — **both strings can appear.**
    `IncomeService` now carries a second copy of the same trick for stock adjustments
    (`STOCK_ADJUSTMENT_STATUS_COMPLETED_LEGACY = "Duyệt"`), and `StockadjustmentService` matches
    stock-count statuses **accent-insensitively** for the same reason.
19. **Income has an approve/reject workflow but is NOT in the unified Approval screen.** Never
    confirmed whether that is an oversight or a deliberate cut.
20. **3 date-storage conventions in active use.** `LocalDateTime` (`Invoice.date`,
    `Procurementplan.date`, `FinancialSetting.balanceUpdatedAt`, `Taxperiodsnapshot.recordedAt`);
    genuine `Instant.now()` (`Purchaseinvoice.date`, `Expense.date`, `Stockadjustment.date`);
    `Instant` via a `nowVn()` hack (`Return.returnDate`, `approvedAt`, all `Shiftreport` time
    fields). `Notification` mixes two within one entity (`createdAt` is `Instant`,
    `readAt`/`resolvedAt`/`expiresAt` are `LocalDateTime`). Top non-urgent cleanup.
21. **`@InitBinder` for vi-VN money exists only on `TaxPeriodPageController`.** Every other
    `data-money` form (Expense, Income, Purchase Invoice…) still returns a raw 400 error page if the
    separator-stripping JS does not run. Same 6-line fix applies; worth spreading.
22. **By design** — `InvoiceDetailCreateRequest.unitSellPrice`'s javadoc claims "optional override"
    but it is **not honored**; "Đơn giá" is hard-locked to `Productunit.sellPrice`.
23. **By design** — `PurchaseInvoiceDetailCreateRequest.noExpirationDate` is DTO-only, never
    persisted.
24. **By design** — `Purchaseinvoice.isValidForDeduction` is recomputed on every read from
    `(totalAmount, paid, dueDate)`; never trust the stored snapshot. `applyPayment()` leaves it
    alone. Note this makes deductibility **time-dependent**, which is precisely why a tax period is
    snapshotted rather than recomputed forever.
25. **By design** — `Invoice.returnStatus` is a cache; `ReturnService.invoiceReturnCode()` recomputes
    it on every read and is the source of truth, so un-backfilled rows are not wrong.
26. **Naming trap** — `Invoice.invoicePattern` is a 7-char type/series prefix, not unique, mutable
    after creation; `invoiceNumber` is the actual identifier.
27. ~~**🔴 BLOCKING: live DB is missing `financialsetting.revenueGroupConfirmed`.**~~ **RESOLVED
    same session (2026-08-03), not by patching the DB but by removing the unapproved column from the
    entity.** `Financialsetting` briefly gained `revenueGroupConfirmed` (and `@Version`) in `ef633bf`
    without approval; the user asked for both removed without any DB/migration change.
    `ddl-auto=validate` only checks that entity-mapped columns exist in the DB — it doesn't complain
    about a DB column no entity maps — so once `revenueGroupConfirmed` was removed from the entity,
    the missing-column check for it went away too. `ProjectApplicationTests` passes and the app boots
    again, with the live DB and `current-database.sql` both untouched. See §0.11 and §7. (`address`
    remains a genuine, harmless drift — already hand-patched into both — not treated as urgent.)
### Closed since the previous snapshot (do not re-report these)

- ~~`Purchaseinvoice.approvedAt` (`@NotNull`) has no service-layer writer, purchase invoice creation
  very likely broken~~ — **CLOSED 2026-08-04, §0.14.** The column is now nullable (user-approved
  schema change), and `approvedAt` is genuinely wired: stamped by `approvePurchaseInvoice()` for the
  Accountant-created Nháp/Chờ duyệt flow, or immediately at creation for the no-Accountant Owner flow.
  The docx's `Nháp`/`Chờ duyệt` scaffolding theory was correct — see §0.14 for the full workflow that
  was built against it.

- ~~No app-wide gate ensures Financial Setting is completed before the system is used~~ — **built**
  this evening (§0.13A): `config/SetupConfirmedInterceptor` blocks every role from every screen until
  `Financialsetting.setupConfirmed = true`, which requires `revenueGroup` + both fund balances filled
  together in one save. Was reserved earlier the same day (former item -0 in §9) pending a teammate's
  column; the column landed (`0ff6daf`) and the feature was built the same evening.

- ~~The revenue-threshold warning is not built~~ — built 2026-07-29 (§2.6). What remains is *when*
  it fires, which is item 10, not whether it exists.
- ~~`NotificationService` is read-only and `NotificationRepository` is bare~~ — both fully built out
  (§2.6), with `V15` normalising the table.
- ~~All three `/…/notifications` pages return 500~~ — the duplicate `PlaceholderController` mappings
  were deleted (§2.7). Same class of bug to watch for on the next screen that graduates.
- ~~`PlaceholderController` serves 9 unbuilt routes~~ — down to 1 on 2026-07-30, then **fully
  deleted 2026-08-02/03** (§2.13/§5). Item 2 (Accountant dashboard) is closed along with it.
- ~~Product allows duplicate names / unchecked số đăng ký~~ — `validateUniqueness()` (§3).
- ~~`FinancialSetting.balanceUpdatedAt`/balances have no automatic writer~~ — reversed, not merely
  closed, on 2026-08-03: they're now actively auto-reconciled (§0.11/§2.13). New, different gaps
  opened in the same change — see item 7 and item 27.
- ~~Income: Owner auto-completes, everyone else needs approval~~ — the whole gate is gone as of
  2026-08-03, not just changed; every role auto-completes now (§0.12).
- ~~ShiftReport is Owner + Pharmacist only~~ — Accountant gained read-only list/detail access on
  2026-08-03 (§0.12).

- ~~`Invoice.returnStatus` is a brand-new orphan column read by nothing~~ — implemented 2026-07-29
  by `ReturnService` (§2.5). It is a cache, not the source of truth; see item 25.
- ~~Product `minStock`/`maxStock` accept negative values~~ — `ProductService.validateStockBounds()`
  (§2.4b).
- ~~Numeric form fields accept `e`/`+`/`-`~~ — `fragments/number-input` (§2.4b), on 8 screens.
- ~~Customer/Supplier can be duplicated because the DB has no UNIQUE constraint~~ — enforced in the
  service layer plus a live-check endpoint (§2.5). The DB still has no constraint, so the service
  remains the only gate.
- ~~`IncomeService` can lock an Accountant out~~ — fixed by the role guard in `ensureOpenShiftFor()`.
- ~~`ShiftReport.totalBankingOut` unwired~~ — now computed and stored.
- ~~`Financialsetting.autoOffsetDebtOnRefund` / `returnPolicyMaxDays` not enforced~~ — both are read
  and applied by `ReturnService` / `ReturnPurchaseService`.
- ~~`Taxperiodsnapshot` is an orphan entity~~ — fully built out (§2.1).
- ~~`Purchaseinvoice.totalVATInput` has a trailing space in `@Column`~~ — fixed by `dcb3d6c`.

---

## 7. Tests — current roster

**`mvn test`: 536 run, 5 failures, 29 errors.** New baseline as of 2026-08-04 (was 462 → 491 → up
to 517 mid-session → 512 → 519 after the `setupConfirmed` build → **536** after this session's
Purchase Invoice approval workflow). Full breakdown:

- **+17 net tests** vs. 519, all in `PurchaseinvoiceServiceTest` (53→**70**) — the new Nháp/Chờ duyệt/
  duyệt/từ chối/xóa workflow, §0.14.
- **−1 failure** vs. 519: `SidebarMenuServiceTest.accountantMenu_matchesTheAgreedGroupOrder` was fixed
  in passing (its expected group list was missing the already-shipped "Báo cáo ca" group — unrelated
  pre-existing staleness, just happened to get touched while adding the Accountant's new "Hàng hóa"
  sidebar entry, §0.14). Everything else unchanged.

I hand-fixed `InvoiceServiceTest.java` on 2026-08-03 just to get the suite to compile at all (missing
constructor arg, §7.3 instance 8) — that fix is retained; the run count above is after it.

### 7.1 The 5 failures — all stale tests, no product bugs

| Test | Cause |
|---|---|
| `ProductServiceTest.updateProduct_rejectsAddingAUnitRow` | Asserts adding a unit row on Edit still throws — no longer true, intentionally. |
| `ProductServiceTest.updateProduct_rejectsRemovingAUnitRow` | Functionality is correct; the test checks the old error string. |
| `ProfileServiceTest.getProfile_roleIsCashier_shouldDisplayVietnameseRoleName` | References the removed `CASHIER` role. |
| `ProfileServiceTest.getProfile_multiplePermissions_shouldJoinDistinctRoles` | Expects the old `chief_pharmacist` label. |
| `SidebarMenuServiceTest.ownerMenu_includesAbsorbedChiefPharmacistItems` | Expects `/owner/stock-adjustments/create` in the Owner menu; that URL has never been a menu item. |

~~`SidebarMenuServiceTest.accountantMenu_matchesTheAgreedGroupOrder`~~ — **fixed 2026-08-04**, was
missing "Báo cáo ca" from its expected group list.

**29 errors, ONE cause**: `SecurityConfig` requires `ReasonAwareSessionExpiredStrategy`/
`SessionExpiryReasonRegistry`, but `NavigationRenderingTest` (8) and `ProductPageControllerTest` (21)
`@Import(SecurityConfig.class)` without providing them. **Test-context only — the real app boots
fine**, verified again live this session for the new Purchase Invoice flows specifically.

### 7.2 Full class roster

| Test class | Count | Status | Tracked? |
|---|---|---|---|
| `service/TaxperiodsnapshotServiceTest` | **109** | ✅ green | git-ignored |
| `service/ExpenseServiceTest` | **84** | ✅ green | git-ignored |
| `service/ProductServiceTest` | 86 | ❌ 2 stale failures | git-ignored |
| `service/PurchaseinvoiceServiceTest` | **70** | ✅ green (53→70, 2026-08-04, §0.14) | git-ignored |
| `service/PricesettingServiceTest` | 49 | ✅ green | git-ignored |
| `ProductPageControllerTest` | 21 | ❌ all error (context gap) | git-ignored |
| `service/OwnerPermissionServiceTest` | 18 | ✅ green | git-ignored |
| `service/SidebarMenuServiceTest` | 13 | ❌ **1** stale failure (was 2, one fixed 2026-08-04) | git-ignored |
| `service/ProfileServiceTest` | 13 | ❌ 2 stale failures | **tracked** |
| `service/ProductImageStorageServiceTest` | 11 | ✅ green | git-ignored |
| `NavigationRenderingTest` | 8 | ❌ all error (context gap) | **tracked** |
| `CustomAccountDetailsServiceTest` | 8 | ✅ green | **tracked** |
| `controller/PermissionControllerTest` | 6 | ✅ green | git-ignored |
| `service/OwnerUserServiceTest` | 6 | ✅ green | **tracked** |
| `service/FinancialsettingServiceTest` | **31** | ✅ green | git-ignored |
| `service/InvoiceServiceTest` | 2 | ✅ green | git-ignored |
| `ProjectApplicationTests` | 1 | ✅ green | **tracked** |

**Only 5 test files are tracked by git** — `.gitignore` line 34 is
`/src/test/java/com/example/project`. Everything else, including all 109 `TaxperiodsnapshotServiceTest`
cases, **does not appear in `git log`, `git show`, or a fresh clone.** Changes to those files are
invisible to PR review — a real hazard when handing over. **No test files exist yet for**
`AccountantDashboardService`, `EmployeenoteService`, `WorkflowNotificationService`, or
`InventoryNotificationService` — confirmed via `Glob`/directory listing, not `git diff` (which is
blind to git-ignored files).

### 7.3 🔴 The git-ignored test hazard, now observed EIGHT times

A code change — a teammate's, or your own — can break these tests without a compile-time signal from
anywhere else. Sometimes at **compile time** (the whole `mvn test` run fails even though
`mvn compile` is clean), sometimes at **context-load time**, sometimes at plain runtime with a clean
compile. Eight real instances (seven documented before this update, the eighth is new this session):

| What changed | What broke |
|---|---|
| `InvoiceService.createSaleInvoice()` gained an `allowDebt` parameter | `InvoiceServiceTest` still called the 2-arg version → `NoSuchMethodError` ×2 |
| **`WebConfig` gained a `ShiftreportService` dependency** (`09fde03`) | `PermissionControllerTest` context stopped loading → 6 errors |
| **`WebConfig` gained a `NotificationService` dependency** (2026-07-29, for the topbar badge) | same test, same failure → 6 errors again |
| The sidebar gained a `Thông báo` group for all three roles | 3 `SidebarMenuServiceTest` order assertions → 8 failures total |
| **`Invoice`/`Purchaseinvoice` gained `@Version`, saves rerouted through `saveAndFlush` (2026-07-31)** | `PurchaseinvoiceServiceTest` 17/44 broken (NPE, then "wanted but not invoked"), `InvoiceServiceTest` 2/2 broken (same NPE) — both mocked `.save(...)` only |
| **`TaxPeriodCloseRequest`/`TaxPeriodUpdateRequest` lost `nextPeriodTaxType`, `FinancialsettingService` gained a `TaxperiodsnapshotRepository` constructor param (2026-08-01, self-inflicted — the author's own change, not a teammate's)** | `TaxperiodsnapshotServiceTest` failed to *compile at all* (every test still calling the removed setter); `FinancialsettingServiceTest` failed every single test with `NoSuchMethodError` on the old 1-arg constructor |
| `Taxperiodsnapshot.cashBalanceAtPeriodEnd` → `quarterlyRevenue` rename (2026-08-02, teammate) | `TaxperiodsnapshotServiceTest` broke at two call sites, the same way it broke `mvn compile` itself |
| **`InvoiceService`'s constructor gained a `FinancialsettingService` parameter (2026-08-03, part of the §0.11 fund-reconciliation work)** | `InvoiceServiceTest.java` (line 64-66) still passed only 10 args, one short → compile failure, whole suite wouldn't run. **Fixed this session**: added a `financialsettingService = mock(FinancialsettingService.class)` field, threaded it through at the constructor's 9th-of-11 position (between `financialsettingRepository` and `returnRepository`). No stub needed — `applyFundDelta` is void and the test doesn't assert on the fund side-effect. |

**A ninth way to lose these files, self-inflicted (2026-07-31):** bulk-editing
`ExpenseServiceTest.java` with PowerShell `Get-Content -Raw` + `Set-Content` **destroyed every
Vietnamese string in it** — PS 5.1 reads with the ANSI codepage, so `Tiền điện` came back as
`Tiá»n Ä‘iá»‡n`. Because these files are git-ignored **there was nothing to restore from**; recovery
worked only by re-decoding (read as UTF-8 → encode cp1252 → decode UTF-8, verified 0 replacement
characters). **Edit these files with the editor tool, never with a shell one-liner.**

**The `@Version` instance, in more detail** (same evening as the ninth item, easy to conflate — they
are different): `PurchaseinvoiceService`/`InvoiceService` replaced every internal `.save(...)` call
with a guarded `.saveAndFlush(...)` wrapper (§0.8) to make `ObjectOptimisticLockingFailureException`
surface as a friendly error. `PurchaseinvoiceServiceTest` and `InvoiceServiceTest` only ever stubbed
`.save(...)`; an unstubbed Mockito method silently returns `null` rather than failing the build, so
every test that used the returned entity's `id` NPE'd, and once a `saveAndFlush` stub was added, every
`verify(repository).save(...)` then failed with "wanted but not invoked". Fixed by adding matching
`.saveAndFlush(...)` stubs and retargeting the `verify()` calls.

All eight were fixed. Four rules follow (unchanged, still the right ones):

1. **Run the full suite after pulling teammate work**, not just the module you touched. The
   2026-08-02→2026-08-03 pull (43 commits) came in with the whole suite refusing to compile
   (`InvoiceServiceTest` constructor mismatch) plus one new genuine schema-validation error — the
   worst single instance of this hazard yet, because it wasn't just test breakage, it was a real
   blocking bug (§6 item 27) that only surfaced *because* the test suite was run at all.
2. **When you add a dependency to `WebConfig`, mock it in every `@WebMvcTest` that imports it.**
3. **When you add `@Version` to an entity, grep every git-ignored test that mocks its repository for
   `.save(` and add a matching `.saveAndFlush(` stub/verify.**
4. **When you change a service's constructor or a request DTO's fields, grep every git-ignored test
   that constructs it or calls the removed setter, before you consider the change done.** Applies to
   your own changes just as much as a teammate's.

### 7.4 Services with **no** tests at all (descending risk)

1. **`ReturnService`** (1492+) / **`ReturnPurchaseService`** (1012+) — top risk, unchanged position.
   Zero tests, changed heavily *again* this window: `isTaxExempt()`, `WorkflowNotificationService`
   hooks, the invoice-numbering rewrite, on top of everything from before.
2. **`StockadjustmentService`** (1388) — zero, most business rules of any service in the codebase.
3. **`IncomeService`** (1210+) — zero, and as of 2026-08-03 it just lost its entire approval gate
   (§0.12) with no test coverage of the new behavior at all.
4. **`InvoiceService`** (1480+, grew ~330 lines this window) — only 2 FEFO tests, despite gaining the
   fund-reconciliation hook, the invoice-numbering/type rewrite, and server-side prescription gating.
5. **`AccountantDashboardService`** (1253) — brand new, zero tests, computed from full-table scans.
6. **`WorkflowNotificationService`** (700) — brand new, zero tests, decides who gets notified about
   every pending approval across 4 modules.
7. **`InventoryNotificationService`** (522) — brand new, zero tests, runs on a schedule against
   production data with no test coverage of its dedupe/resolve logic.
8. **`DebtService`** (682+) — zero.
9. **`ShiftreportService`** (659+) — zero, despite owning cash reconciliation and just having a real
   double-counting bug fixed in it this window (§0.12) with no regression test added.
10. **`ApprovalService`** (348) — zero.
11. **`SupplierService`** (264) / **`CustomerService`** (253) — zero, and `CustomerService` just
    gained a whole new required-fields validation rule (§2.13) with no test covering it.
12. **`EmployeenoteService`** (188) — brand new, zero tests.
13. **`NotificationService`** / **`TaxRevenueNotificationService`** — zero, unchanged gap, now
    joined by the two new notification services above (6/7).
14. `TypeService` / `ProcurementplanService` / `StockcountService` — unchanged gaps.

---

## 8. Verification notes (how this was checked, so you can repeat it)

Every feature above was exercised against the running app, not just unit-tested. Useful recipes:

- **To check whether `current-database.sql` still matches the live DB** (how §0.1 was verified),
  dump the live columns and set-diff them against the file rather than eyeballing:

  ```
  mysql -uroot -p123456 --default-character-set=utf8mb4 -N -e "SELECT LOWER(TABLE_NAME), LOWER(COLUMN_NAME) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='hang_ngoc_pisms' ORDER BY 1,2;"
  ```

  Expect exactly two live-only tables — `flyway_schema_history` and `passwordresettoken`. Anything
  else means the SQL file has drifted. **Note `ddl-auto = validate` will NOT catch a dropped
  column** if the entity dropped the field too, so a green `ProjectApplicationTests` is not proof on
  its own.
- **To read `Pharmacy-Database-Description.docx` without Word**: it is a zip —
  `zipfile` → `word/document.xml`, replace `</w:p>` with newlines and `</w:tc>` with ` | `, strip
  the remaining tags, unescape entities. Good enough to grep for a field name.

- The dev DB has no transactional data, so **insert throwaway rows** for whatever the screen reads.
  For the tax period that means an `invoice` + `invoicedetail` + `batch` (giá vốn), an `expense`, a
  `purchaseinvoice`, and a preceding `taxperiodsnapshot` to seed the chain. Delete them afterwards.
- **`--default-character-set=utf8mb4` on every mysql invocation.** A return inserted without it gets
  `status = 'N?'` and silently never appears in any picker.
- **Browser navigation lags a request behind.** Re-issue `navigate` with `force: true`, and start
  every injected page script with a `location.pathname` guard.
- Scope form lookups by id (`#expenseForm`, `#closePeriodForm`); `document.querySelector('form')`
  finds the topbar's hidden logout form first.
- To bypass a page's `confirm()` in an automated check, call
  `HTMLFormElement.prototype.submit.call(form)` — it skips submit listeners. Remember that also skips
  the `data-money` separator-stripping listener, so post raw digits (or rely on the `@InitBinder`
  where one exists).
- Searching a response for the literal `pm-toast-error` gives a **false positive** — that string is
  in the toast fragment's CSS on every page. Match the message inside the `<span>` instead.
- `preview_stop` does not reliably kill the process — check `Get-NetTCPConnection -LocalPort 8080`.
  Be careful killing a stray java PID: it may be the server you just started.

---

## 9. Suggested next steps (nothing here is started)

-1. ~~URGENT: fix the live-DB `revenueGroupConfirmed` schema gap~~ — **RESOLVED same session** by
   removing the unapproved column from the entity instead of patching the DB (§6 item 27, §0.11).
-0. ~~🟡 RESERVED: `Financialsetting.setupConfirmed` unified locking~~ — **BUILT this same day, evening
   window, §0.13.** The teammate column landed (`0ff6daf`), and both open UX questions below got
   answered by the user before the build started: (1) all three fields lock **together, in one shot**,
   not independently — resolved; (2) the gate is **app-wide**, blocking every role from every other
   screen until setup is done, not scoped to just the Financial Setting screen — resolved ("chặn hết"
   for both questions). `config/SetupConfirmedInterceptor` implements this, verified live for both
   Owner and Pharmacist. Nothing left to decide here.
-0b. **🔴 `FinancialsettingService`'s fund saves still have no optimistic-lock guard, even though
   `@Version` is back on `Financialsetting` as of this evening (§0.13A).** `applyFundDelta()`/
   `adjustFundBalances()` both call plain `.save(...)`, not a guarded `saveAndFlush()` — nobody has
   rebuilt the friendly-message wrapper the other three `@Version`'d entities have. A genuine
   concurrent-edit race between an Owner editing Financial Settings and a concurrent sale/expense/
   shift touching the same row now surfaces as a raw `ObjectOptimisticLockingFailureException`
   instead of a flash message. Building the guard is a self-contained, low-risk follow-up — nothing
   else depends on deciding this first.
-0c. ~~**🔴 verify `Purchaseinvoice.approvedAt` doesn't break purchase-invoice creation**~~ — **DONE
   2026-08-04, §0.14.** It did need real service-layer wiring, and the underlying question ("what
   workflow is this meant to support") turned out to be exactly the Nháp/Chờ duyệt approval flow the
   docx was hinting at — built, tested, and live-verified. Nothing left to decide here.
0. **Get the docx owner to fix Expense/Invoice/Tax entries** (§0.1b) — now larger than before:
   `Invoice.status`, two Expense entries, the invoice-numbering/type change, and every tax-formula
   number (group 3's PIT rate, the GTGT method) are all stale as of 2026-08-03. Not re-verified this
   session — the docx itself needs a fresh read before anyone trusts it as a spec.
0b. **Read the Pharmacist dashboard rewrite before trusting it** (§2.9) — 474 new lines in
    `DashboardService`, 746 in the template, landed 2026-07-31 and still not reviewed in depth.
0c. **Decide whether `DebtOffsetService` needs tests** (§2.9) — it is the only code path that writes a
    non-zero `Expense`/`Income.paidByCredit`, moves real debt off `Invoice`/`Purchaseinvoice`, and has
    zero coverage.
1. **🔴 Settle how a `DESTROY_EMPLOYEE_FAULT` slip names its liable employee** (§6 item 1). Still
   unresolved — confirmed unaffected by any of this session's changes, including the new Employee
   Note feature, which is a separate, general-purpose HR note.
2. ~~Decide what the Accountant's dashboard should show~~ — **DONE, §6 item 2 closed, §2.13.**
3. **Get `Dac_ta_Income_StockAdjustment.xlsx` into `docs/context/`** (§6 item 3) — the code defers
   to four of its sheets and nobody on a fresh clone can see them.
4. **First tests for ALL FOUR notification write paths, not just one** (§7.4 items 6/7/13) —
   `WorkflowNotificationService` and `InventoryNotificationService` are brand new on top of the
   already-untested `NotificationService`/`TaxRevenueNotificationService`. `createIfMissing`/
   `resolveByReference`/`resolveByTypeAndReference` decide whether any of these fires, suppresses as a
   duplicate, or self-resolves — zero coverage on any of it, across all four services.
5. **Fix the 6 stale tests** to get a clean baseline — all are assertion updates, no product changes.
6. **First tests for the untested giants** — `ReturnService`/`ReturnPurchaseService` (2500+ combined)
   remain the top exposure, `StockadjustmentService` (1388) close behind, and as of 2026-08-03
   `IncomeService` and `InvoiceService` both changed significantly with zero new coverage
   (§7.4 items 3/4). `AccountantDashboardService`/`EmployeenoteService` are brand new and untested too.
7. **Ask the docx owner to close the last two entries** — plus everything new in item 0 above.
8. **Decide whether the threshold warning — and the group auto-transition — needs a scheduler**
   (§6 item 10). The new `InventoryNotificationScheduler` (§2.13) proves scheduling is now trivial to
   add in this codebase (`@EnableScheduling` is already on); it would be a small lift to give tax
   notifications the same treatment, but it hasn't been asked for.
9. **Decide what to do about the un-migrated schema** (§0.1) — this session's `revenueGroupConfirmed`
   episode (added without approval, then reverted from the entity rather than patched into the DB) is
   another data point for whether this class of change should go through real Flyway migrations with
   sign-off, instead of ad hoc entity edits that may or may not get hand-applied to the DB.
10. **Spread the vi-VN money `@InitBinder`** to the other `data-money` forms (§6 item 21).
11. **Show refund status on the Return screen** (§6 item 11) — derive it from the linked Expense.
12. **Re-walk every sidebar route per role** (§5) — not done since 2026-07-30, and two roles' menus
    changed since (`Báo cáo ca` for Accountant, the new Accountant dashboard replacing the placeholder).
13. **Confirm with the BA whether the `ef633bf` tax-formula overhaul (§0.11) is actually correct** —
    it landed with no docs, no commit body, and no test coverage explaining the reasoning behind
    dropping GTGT deduction for group 3 or moving the PIT rate to 17%. High-stakes, silent change.
