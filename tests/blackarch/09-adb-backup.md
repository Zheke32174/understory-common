# 09 — adb backup data extraction

**Threat class**: backup-channel exfil
**Tool**: `adb backup` (and the manifest-driven backup framework that
some attacker tools probe).

**passgen defense**: `android:allowBackup="false"` plus
`<data-extraction-rules>` in `res/xml/data_extraction_rules.xml`
excluding every domain (root, file, database, sharedpref, external).

## Test 1: adb backup attempt

```bash
adb backup -f /tmp/passgen-backup.ab com.understory.passgen
# Tap "Back up my data" on the device prompt (if it even appears)
```

**Expected**: one of two outcomes:
- The OS refuses the backup outright and the .ab file is empty / has no
  data section
- The .ab file contains only a tiny manifest, no app data

To inspect the .ab:

```bash
# .ab files are zlib-compressed tar streams with a 24-byte header
dd if=/tmp/passgen-backup.ab bs=1 skip=24 | zlib-flate -uncompress | tar tvf -
```

**Expected**: empty or only meta entries; no `f/vault.bin`, no
`sp/passgen_settings.xml`, no `db/*`.

## Test 2: full-device backup mentions passgen

```bash
adb backup -f /tmp/all.ab -all
```

**Expected**: passgen's package is skipped from the backup entirely
(allowBackup=false excludes it from -all).

## Pass/fail

✅ Test 1: passgen's data dir is not extracted via backup
✅ Test 2: passgen is skipped from full-device backup
