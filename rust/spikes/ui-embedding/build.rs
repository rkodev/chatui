fn main() {
    // `ComponentContainer` and `component-factory` are experimental Slint
    // builtins. Enable them for this spike only; production crates do not use
    // them yet.
    //
    // SAFETY: the build script is single-threaded at this point and no other
    // thread reads the process environment concurrently.
    unsafe {
        std::env::set_var("SLINT_ENABLE_EXPERIMENTAL_FEATURES", "1");
    }

    // The testing backend needs compiler debug info to expose the
    // accessibility tree to tests.
    let config = slint_build::CompilerConfiguration::new().with_debug_info(true);
    slint_build::compile_with_config("ui/embedding.slint", config)
        .expect("failed to compile ui/embedding.slint");
}
