# Android multilingual backend-driven form label suite

This suite validates the Android side of the backend multilingual form-label contract from `docs/android-multilingual-profile-form-test.md`.

Android must resolve backend-owned form strings as:

```kotlin
labels[currentLanguageCode] ?: labels["en"]
```

Android must not:

- show blank labels;
- show raw JSON such as `{ "en": "..." }`;
- hardcode translations for backend-owned form labels;
- translate advisories or backend copy on device.

## Backend prep

Run before each flow because the flows share the deterministic dynamic-profile mobile `+919900000002`:

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/seed_android_dynamic_profile_test_context.py --reset --apply
../venv/bin/python scripts/audit_android_multilingual_form_labels.py
```

The backend test lane is:

- `X-Tenant-ID: android-dynamic-test`
- `project_id=0f7e0a6b-8472-5d6d-8a14-a9d000000001`
- bootstrap `/api/v1/app-config/bootstrap?project_id=0f7e0a6b-8472-5d6d-8a14-a9d000000001`

## Android flows

```powershell
maestro test maestro\37a-multilingual-farmer-hi.yaml
maestro test maestro\37b-multilingual-farmer-kn-fallback.yaml
maestro test maestro\37c-multilingual-farmer-mr-fallback.yaml
maestro test maestro\37d-multilingual-farmer-pa-fallback.yaml
```

## Expected behavior

| Flow | Language | Backend expectation | Android expectation |
| --- | --- | --- | --- |
| `37a` | Hindi (`hi`) | Hindi keys exist for audited forms | Render Hindi keys where backend provides them; never raw JSON |
| `37b` | Kannada (`kn`) | Native keys incomplete | Render English fallback |
| `37c` | Marathi (`mr`) | Native keys incomplete | Render English fallback |
| `37d` | Punjabi (`pa`) | Native keys incomplete | Render English fallback |

## Latest Android evidence

Validated on emulator `emulator-5554`:

- `37a-multilingual-farmer-hi.yaml` passed: Hindi state opened the backend-driven farmer form, rendered Hindi-capable profile copy, and did not expose raw label JSON.
- `37b-multilingual-farmer-kn-fallback.yaml` passed: Kannada state rendered English fallback labels.
- `37c-multilingual-farmer-mr-fallback.yaml` passed: Marathi state rendered English fallback labels.
- `37d-multilingual-farmer-pa-fallback.yaml` passed: Punjabi state rendered English fallback labels.

Screenshots captured:

- `37a-multilingual-farmer-hi`
- `37b-multilingual-farmer-kn-fallback`
- `37c-multilingual-farmer-mr-fallback`
- `37d-multilingual-farmer-pa-fallback`

## Product UX note

The current first-run language list is acceptable for deterministic Maestro coverage, but it should not be treated as final production UI. When more languages are enabled, prefer a compact language selector:

- show the most likely 2-3 languages based on device locale, state, or project geography;
- open a searchable bottom sheet or picker for more languages;
- show native name plus English name;
- keep backend form-label language code separate from the timeline for fully translated app chrome.

Existing Maestro coverage complements this label check:

- `07` parcel registration;
- `09` soil profile;
- `08` crop-cycle create;
- `12` activity log;
- `14`-`19` stale-context and conflict guidance;
- `26` multi-conflict pending drawer.
