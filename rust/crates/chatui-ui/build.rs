fn main() {
    // The Slint testing backend needs compiler debug info to expose the
    // accessibility tree to tests. Keep it out of release builds.
    if std::env::var("PROFILE").as_deref() == Ok("debug") {
        println!("cargo:rustc-env=SLINT_EMIT_DEBUG_INFO=1");
    }
    println!("cargo:rerun-if-changed=build.rs");
}
