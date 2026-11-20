# Project-specific R8 rules. The IME service and settings activity are referenced from the
# manifest and kept automatically.

# Privacy (docs/SECURITY.md): release builds carry no verbose/debug/info logging at all. Warnings
# and errors stay; they only ever carry exception class names, never user text.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
