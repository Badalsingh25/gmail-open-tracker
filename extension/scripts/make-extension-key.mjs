#!/usr/bin/env node
// Google sign-in needs a STABLE extension ID. For an unpacked extension that comes from the "key" field in
// manifest.json. This prints a fresh key and the ID it produces. The private half is discarded on purpose:
// it is only needed to package/publish the extension, not to run it unpacked.
//
//   node scripts/make-extension-key.mjs
import { generateKeyPairSync, createHash } from "node:crypto";

const { publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const der = publicKey.export({ type: "spki", format: "der" });
const key = der.toString("base64");
const id = [...createHash("sha256").update(der).digest().subarray(0, 16)]
  .map(b => String.fromCharCode(97 + (b >> 4)) + String.fromCharCode(97 + (b & 15))).join("");

console.log("Extension ID (give this to Google when creating the OAuth client):\n  " + id);
console.log('\nAdd this line to extension/public/manifest.json (top level):\n  "key": "' + key + '",');
