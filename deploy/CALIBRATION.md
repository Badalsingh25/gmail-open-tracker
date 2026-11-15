# Calibrating against ground truth

Goal: replace "we think it is accurate" with a measured number for **your** recipients' mail clients, and tune the
scorer from the mistakes. Nothing here has been run against real mail yet. `sample/` holds **synthetic** data that
only shows what the report looks like; its numbers mean nothing.

## Why this matters
Every threshold in `tracker.scoring.*` and every User-Agent / IP rule is a starting guess. Mail clients differ, and some
guesses will be wrong (for example, whether Outlook desktop's image fetches look like a real open). The only way to
know is to send test mail where you control what the "recipient" does.

## Protocol

Use accounts you own. For each client below, repeat the scenarios, sending **one separate email per scenario**
with a unique tag in the subject (`CAL-<client>-S1` ...).

| Scenario | What you do | Expected |
|---|---|---|
| S1 | Deliver it and **never open it** (leave it for 24 h) | NOT_OPENED, measures prefetch/scanner false alarms |
| S2 | Open it right away with images shown | OPENED |
| S3 | Open it **more than an hour later** with images shown | OPENED |
| S4 | Open it with **images blocked** | OPENED (the tracker is expected to miss this; it measures the blind spot) |
| S5 | Open it and **click the tracked link** | CLICKED |
| S6 | **Look at your own Sent copy first**, wait about 2 minutes, then open it as the recipient | OPENED (tests the own-view filter) |

Clients worth covering: Gmail web, Gmail iOS/Android app, Outlook.com web, Outlook desktop (Windows), Outlook mobile,
Apple Mail with Mail Privacy Protection **on**, Apple Mail with it **off**, Yahoo Mail, Thunderbird, and a work/school
mailbox behind a security gateway if you have one. Aim for several repetitions per cell, and at least ~30 emails per
outcome class before trusting a percentage.

Send each test through the extension with **Track opens** ticked, to a single recipient, so there is one pixel per email.

## Quick plan (about 45 minutes, 18 emails)

Enough to see whether the scoring is roughly right for the clients you actually send to. Use your own accounts.

1. **Sender:** one Gmail account in Chrome with the extension (v0.7+). **Recipients:** your other Gmail account, your
   college account, and Gmail on your phone (one real device counts as a separate client).
2. Send **one tracked mail per recipient per scenario**, to a single recipient each, with the tag in the subject
   (`CAL-gmailweb-S2`, `CAL-college-S5` ...). Tag format: `CAL-<client>-S<number>`, no extra dashes in `<client>`.
3. Run these scenarios per recipient: **S1** (leave unopened, check after a few hours), **S2**, **S5**, **S6**. That is
   4 mails x 3 recipients = 12; add **S3** and **S4** for the main Gmail account for 14, and repeat S2 once more for
   each recipient if you have the patience (18). Put a link in every mail so S5 is possible.
4. Let the tracker generate the answer sheet, then correct it where you did something different:
   ```bash
   node calibrate.mjs --make-template --api https://YOUR-SERVICE.onrender.com --key YOUR_API_KEY --out ground-truth.csv
   node calibrate.mjs --truth ground-truth.csv --api https://YOUR-SERVICE.onrender.com --key YOUR_API_KEY
   ```
   Open the CSV first: `expected` is filled in from the scenario number (S1 NOT_OPENED, S2/S3/S4/S6 OPENED, S5
   CLICKED), so change any row where you did something else. Mails that were never sent should be deleted from it.
5. Read the **By scenario** and **Mistakes** sections. Typical findings: S1 rows reported as opened (prefetch counted
   too high), S6 rows missed (the own-view filter is too wide), S4 rows missed (expected: blocked images are invisible).
   Fix one thing, restart, re-run: verdicts are recomputed from stored events, so you do not resend anything.

Treat results from 18 mails as a sanity check, not a measurement: the ~30 per class guidance below still applies.

## Run

1. Copy `ground-truth.template.csv` to `ground-truth.csv` and add a row per email (`tag,address,client,scenario,expected`).
2. Compare with what the tracker recorded:
   ```bash
   node calibrate.mjs --truth ground-truth.csv --api https://api.example.com --key YOUR_API_KEY
   ```
   (or save `/api/messages/recent` to a file and use `--input recent.json`).

You get a confusion matrix, recall / precision / false-alarm rate (strict and counting "possibly opened" as positive),
accuracy per client, and a list of every mistake with the evidence the tracker used.

## Tuning loop

1. Look at the mistakes. A **false alarm** usually means a prefetch or scanner was scored as human; a **missed open**
   usually means a real client was scored as automated.
2. Change one thing: a threshold in `tracker.scoring.*` (environment/`application.yml`), a marker in
   `SignalExtractor`, or a rule in `OpenScorer`.
3. Restart the API service and re-run `calibrate.mjs`. **Verdicts are recomputed from stored raw events on every
   read**, so you do not need to resend the test emails; the same events are re-scored.
4. Keep the change only if the numbers improve across clients, not just the one you were staring at.

## Honest limits
- Apple Mail with Privacy Protection will show S2/S3 as missed opens: Apple fetches images for the user, so a real open
  is indistinguishable from a prefetch. That is reported as "not measurable", not guessed. Only link clicks help there.
- S4 (images blocked) is invisible to any image-based tracker.
- Results are only as good as your sample. Re-run when mail providers change behaviour.
