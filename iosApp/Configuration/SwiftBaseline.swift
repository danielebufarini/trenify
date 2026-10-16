// Compiler/language/architecture contract shared by every Xcode target.
// compiler() compares patch versions even when --version displays only 6.4.
#if !compiler(>=6.4.0) || compiler(>=6.4.1)
#error("Trenify requires exactly Swift compiler 6.4.0")
#endif
#if !swift(>=6.0) || swift(>=7.0)
#error("Trenify requires Swift 6 language mode")
#endif
#if !arch(arm64)
#error("Trenify supports ARM64 only")
#endif
