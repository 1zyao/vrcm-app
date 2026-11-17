/*
 * The C ABI the phone core exposes, written out by hand.
 *
 * `cargo build` emits a static library, not headers, so this header is the contract between the Rust
 * side (`crates/mobile-core/src/lib.rs`, functions `vrcx_core_start` / `vrcx_core_free`) and whatever
 * calls it. Keep the two in sync: a signature change here without one there fails at link time, which
 * is the good kind of failure.
 *
 * Both platforms' entry points do the same thing:
 *   Android  Java_io_github_vrcmteam_vrcm_core_NativeCore_start  (JNI)
 *   iOS      vrcx_core_start                                     (this file)
 * Each returns `{"port":<u16>,"token":"<43+ chars>"}` or `{"error":"…"}`.
 */
#ifndef VRCX_CORE_H
#define VRCX_CORE_H

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Starts the core against `data_dir` and serves the command bus on an ephemeral loopback port.
 *
 * `data_dir` is the app's private data directory (on iOS, `Application Support`); the host creates its
 * own `VRCX-0` folder inside it, the same layout the desktop uses.
 *
 * Returns a NUL-terminated JSON string. Must be released with `vrcx_core_free`. Returns NULL only if
 * the reply itself could not be allocated.
 */
char *vrcx_core_start(const char *data_dir, const char *device_name);

/* Releases a string returned by `vrcx_core_start`. Passing NULL is a no-op. */
void vrcx_core_free(char *value);

#ifdef __cplusplus
}
#endif

#endif /* VRCX_CORE_H */
