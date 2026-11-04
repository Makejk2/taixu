# Plugin / Skill management integration contract

## Delivery status
Partial implementation; no compilation, tests, CI, APK, external mutation or commits executed.
Production read and native inspection adapters exist. Mutation service transactions, native UI and
app_config routing are NOT wired. Factory requires a real transaction implementation; there is no
unsafe default/fake mutation implementation.

## Files / assembly
- tools/.../admin/PackageAdministration.kt: ToolRegistry / WebPluginRepository / AgentSkillRepository /
  SkillInstallationManager real list and native document inspection APIs; safe metadata only.
- harness/.../admin/packages/PackageAdminBackend.kt: authority port, immutable proposal, one-use native
  callback gate, epoch invalidation and factory. Call disableManagement on global super-admin disable.
- Integrator owns AppAdminConfigRouter.kt and DI. Route module=plugin|skill before ordinary config;
  unknown modules must fail closed. Expose list / inspect / propose / status / apply / cancel, never
  approveNative or setManagementEnabled to model JSON. NativePackageDocument and desired snapshot
  must come from trusted native picker/verified inspection, not caller-controlled snapshots.
- Implement PackageAdminAuthority using trusted executor actor + operation/main-lane journal and
  ManagementActorGuard, not session ID prefixes alone. Members may suggest, never approve.
- Native preview displays target, version, expected/desired manifest fingerprints, real archive SHA,
  verified source status, permissions and capabilities difference, deleteData risks and partial states.
  Do not serialize full inspection object, manifest, prompts, scripts, environment, paths or exception logs.

## Real API mapping / service patch points (outside this lane's lease)
1. ToolManager.kt owns installMutex. install/update use installInternal -> installRecipe; uninstall
   defaults deleteData=false. Add expected current manifest + desired manifest + fixed distro/target
   overloads and compare under installMutex immediately before effects. Existing runExclusive plus
   public install collection would deadlock; never wrap that call as CAS. Make UI calls use the same
   guarded implementation. Retain InstallTransactionManager begin/commit/rollback ownership there;
   directory rollback does not imply arbitrary install scripts were rolled back.
2. ToolRegistry.kt updateSigned(SignedRegistryRequest) actually verifies downloaded signatures;
   inspectLocal reads actual URI, importLocal(expected=...) validates actual payload and manifest.
   Add registry mutation lock and expected catalog digest comparison shared by UI imports/signed sync.
   Do not label source=REMOTE as signature verified; current API does not expose verification evidence.
   Metadata fingerprint is SHA256(serialized identity), NOT archive SHA or signature proof.
3. WebPluginRepository.kt import(uri, expected) and remove(expected) use operations lock and actual
   immutable local store checks. snapshot(expected) rehashes actual payload. Wire a native-owned URI,
   perform expected-current snapshot CAS under operations and expose verification evidence. Web remove
   deletes package payload, not a promise of process application data cleanup. No process install.
4. SkillInstallationManager.kt inspectZipBytes / prepareMarketSkill / commitInstallation are real package
   APIs. Current install path has staging/rename but no shared lifecycle CAS with UI / repository.
   Add shared skill lifecycle lock, digest actual retained archive bytes and installed tree, preserve
   prior directory+DB state until commit, rollback both or report partial. Explicit target is native
   app-owned storage, never a model hostpath. No inferred version when API lacks one.
5. AgentSkillRepository in core/database/.../AgentSkillEntity.kt setEnabled/deleteCustom/addCustom
   currently bypass directorySyncMutex used by syncFromDirectories. Introduce a shared transaction
   API for all writers and directory install, compare complete expected row/tree under that lock.
   Builtin/immutable checks are enforced inside service transaction, not solely in preview.
   deleteCustom is row-only today: preview distinguishes row removal from file/data deletion.

## Durable ownership / cancel / epoch contract
PackageLifecycleTransactions must persist operation ID, owner, immutable target and snapshots before
first external effect. execute calls authorize under service lock immediately before commit/effect;
bridge must coordinate epoch revocation with commit authorization, not use a stale boolean read.
Disabling management invalidates pending proposals and future commits; already-started effects cannot
be represented as magically revoked. Record interrupted/partial/unknown and never auto-replay scripts.
status/cancel are owner-scoped; cancellation is cooperative, leaves durable partial record and does not
claim rollback succeeded without verifying. No raw logs/errors returned. A retry is a new native
preview+approval, not revival of consumed proposal. Persistent proposal/recovery is still integration
work (current proposals intentionally expire on process death).

## Action availability
- Real safe list(plugin/skill), local native plugin/skill inspection: implemented adapters.
- propose/native epoch gate/status/cancel dispatch: implemented, requires native authority and service
  transaction binding. Not callable as a completed lifecycle until those bindings exist.
- plugin install/update/remove/signed sync; skill install/update/remove/enable/disable/sync: declared
  transaction contract only; fail closed by not assembling factory without real implementation.
- Arbitrary filesystem paths, fabricated signature/hash evidence, registry.call-as-install, logs and
  secrets in responses: not supported.


## Native sync implementation (current increment; not runtime verified)
`NativePackageSyncTransactions` wraps the existing web/process/skill owner chain. A sync
proposal uses reserved `id=native-sync`, `target=native-config`, operation `sync` and
module `plugin` / `process_plugin` / `skill`. `web_plugin` sync is unsupported. These
identifiers are not filesystem paths. Existing proposal/prepare/native approval consumes
one proposal, then execute persists queued metadata before launching a coroutine. Status
and cancel remain session-owner-scoped. Recovery changes queued/running to unknown_partial
and never retries an external effect. Journal stores metadata only, not settings keys.

Supported source configuration: existing native `RegistryPreferences.manifestUrl`,
`signatureUrl`, `publicKey` (SettingsDataStore registry_* settings), HTTPS plus nonempty
Ed25519 key. The sync API never accepts a model key, URL or digest. Approval fingerprint
binds all three native values; apply rereads and compares them, then calls the real
`ToolRegistry.updateSigned`. This is user-configured trust, NOT an official pinned trust
anchor (the existing pinned key is empty); do not describe it as official verification.
It updates the signed catalog, not installed plugin binaries. The remote catalog bytes
are not downloaded during preview, so preview does not approve an individual catalog
content digest; authenticity at execution is provided by signature validation.

Main composition wiring required (outside this lane): pass `registryPreferences = get<RegistryPreferences>()`
and `nativeSkillRoots = { <the existing native workspace/root resolver's complete List<SkillScanRoot>> }`
to `createPackageManagement`. The latter is a native callback, never tool-argument decoding.
Its implementation must return the same authoritative hostDir/guestPrefix mapping used by
native skill discovery; this lane found no production SkillScanRoot construction and does
not invent guest mappings. Defaults reject absent configuration. Thus DI/root integration
is **not complete** until the real root resolver is supplied by the main composition.
The callback is not the sync implementation: the owner already calls the actual
`AgentSkillRepository.syncFromDirectories` API with frozen roots.

Skill previews fingerprint ordered canonical roots, guest mappings and tree bytes (no
symlinks; at most 10,000 entries and 64 MiB per root); prepare and execute compare them.
This detects pre-apply content changes, but is not an immutable private copy: filesystem
writers outside the repository can race after verification. Repository directorySyncMutex
serializes its own sync calls only. Strong atomic tree approval still requires native
read-only staging or repository integration under the actual filesystem writer lock.
The sync-owner mutex does not claim to serialize unrelated settings/UI writers. Changes
after the final check and before a service effect remain a documented limitation. No
build, test, CI, network update or real package install was run for this increment.
