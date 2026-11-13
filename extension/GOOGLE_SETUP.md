# Separate per-recipient copies (optional Google sign-in)

Link tracking and single-recipient emails need none of this. It is only needed to **send one personalised copy per
recipient**, which is the only way to know *which person* opened. Gmail's compose window cannot do that by itself, so
the extension sends the copies through the Gmail API using your own Google sign-in. Without it, multi-recipient
emails fall back to one shared copy.

What the extension asks Google for: only `gmail.send` (send mail as you). It cannot read your mail.

## One-time setup (about 10 minutes)

1. **Get a stable extension ID.** In `extension/`:
   `node scripts/make-extension-key.mjs`
   Add the printed `"key": "..."` line to `public/manifest.json` and note the ID it prints.
2. **Create the Google credentials** at https://console.cloud.google.com :
   - Create a project, then **APIs & Services → Library → Gmail API → Enable**.
   - **OAuth consent screen**: user type *External*, fill the basics, add your own address under **Test users**.
   - **Credentials → Create credentials → OAuth client ID → Application type: Chrome extension**, Item ID = the extension ID from step 1.
3. Put the client ID in `public/manifest.json`, replacing `YOUR_CLIENT_ID.apps.googleusercontent.com` under `oauth2.client_id`.
4. `npm run build`, then reload the unpacked extension from `dist/`.

The first separate send opens a Google sign-in prompt.

## Things to know
- While the consent screen is in **Testing** status, Google expires the sign-in about every 7 days; you will be asked again.
- Copies are sent **from your primary Gmail address** (not an alias) and appear in your Sent folder, one per recipient.
- Each person sees only their own address in *To*. Cc/Bcc visibility is lost by design.
- Only **new, plain** messages are split. Replies/forwards, messages with attachments or inline images fall back to one
  shared copy (the extension tells you why), because splitting them would lose the thread or the files.
- If one copy fails, the extension tells you who got it and who did not; retrying only sends to the failed ones.
- Sending many recipients counts against Gmail's normal daily sending limits.
