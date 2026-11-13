# Calibrating against ground truth

Goal: replace "we think it is accurate" with a measured number for **your** recipients' mail clients, and tune the
scorer from the mistakes. Nothing here has been run against real mail yet. `sample/` holds **synthetic** data that
only shows what the report looks like; its numbers mean nothing.

## Why this matters
Every threshold in `tracker.scoring.*` and every User-Agent / IP rule is a starting guess. Mail clients differ, and some
guesses will be wrong (for example, whether Outlook desktop's image fetches look like a real open). The only way to
know is to send test mail where you control what the "recipient" does.

## Protocol

Use accounts you own. For each client below, repeat the five scenarios, sending **one separate email per scenario**
with a unique tag in the subject (`CAL-<client>-S1` ...).

| Scenario | What you do | Expected |
|---|---|---|
| S1 | Deliver it and **never open it** (leave it for 24 h) | NOT_OPENED, measures prefetch/scanner false alarms |
| S2 | Open it right away with images shown | OPENED |
| S3 | Open it **more than an hour later** with images shown | OPENED |
| S4 | Open it with **images blocked** | OPENED (the tracker is expected to miss this; it measures the blind spot) |
| S5 | Open it and **click the tracked link** | CLICKED |

Clients worth covering: Gmail web, Gmail iOS/Android app, Outlook.com web, Outlook desktop (Windows), Outlook mobile,
Apple Mail with Mail Privacy Protection **on**, Apple Mail with it **off**, Yahoo Mail, Thunderbird, and a work/school
mailbox behind a security gateway if you have one. Aim for several repetitions per cell, and at least ~30 emails per
outcome class before trusting a percentage.

Send each test through the extension with **Track opens** ticked, to a single recipient, so there is one pixel per email.

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
