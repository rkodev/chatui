fn main() {
    // `ComponentContainer` and `component-factory` are experimental Slint
    // builtins. The shell embeds the feature windows through them, so the
    // `slint!` macro must see the opt-in at compile time. See
    // `docs/operations/M3.6-desktop-view-integration/embedding-decision.md`.
    println!("cargo:rustc-env=SLINT_ENABLE_EXPERIMENTAL_FEATURES=1");

    // The Slint testing backend needs compiler debug info to expose the
    // accessibility tree to tests. Keep it out of release builds.
    if std::env::var("PROFILE").as_deref() == Ok("debug") {
        println!("cargo:rustc-env=SLINT_EMIT_DEBUG_INFO=1");
    }
    println!("cargo:rerun-if-changed=build.rs");
}
