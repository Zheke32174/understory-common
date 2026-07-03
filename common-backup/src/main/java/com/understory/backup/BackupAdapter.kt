package com.understory.backup

/**
 * Per-app glue between an Understory app and the backup machinery.
 *
 * Each app implements one [BackupAdapter] and registers it with the
 * orchestrator app (vault-backup or device-backup). The adapter owns:
 *   - WHAT to export (e.g. aegis exports its entry list; passgen exports
 *     the encrypted vault blob; firewall exports its blocklist).
 *   - HOW to re-apply on import (write the imported payload back into
 *     the app's storage, run any post-import migration).
 *
 * Cleartext payloads are app-specific JSON / CBOR / proto. The adapter
 * is responsible for its *own* schema versioning inside [export], and for
 * handling older [schemaVersion] values gracefully on [import].
 *
 * Adapters MUST be pure data — no Activity / Composable / View imports —
 * so the orchestrator can call them from a foreground IntentService or
 * from the backups app's own process via cross-app intent without
 * dragging UI dependencies along.
 */
interface BackupAdapter {

    /** Reverse-DNS app id, e.g. "com.understory.aegis". */
    val appId: String

    /** Current export schema version. Bump on incompatible payload changes. */
    val schemaVersion: Int

    /**
     * Returns the cleartext payload to feed into a [BackupCodec]. Caller
     * must zero this when finished; we suggest wrapping the call in a
     * try/finally that does `result.fill(0)`.
     *
     * Throws if the app is in a state where export is impossible (vault
     * locked, no entries to export, etc.). Caller should surface the
     * message to the user.
     */
    fun export(): ByteArray

    /**
     * Apply a previously-exported [payload]. [schemaVersion] is the value
     * recorded in the envelope header at export time, so the adapter can
     * branch on legacy formats. Throws if the payload is incompatible
     * with this app version (no silent data loss).
     *
     * @return human-readable summary of what was imported, e.g.
     *         "Imported 7 TOTP entries; 1 duplicate skipped".
     */
    fun import(payload: ByteArray, schemaVersion: Int): String
}
