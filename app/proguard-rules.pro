# PDFPaper R8 rules (T14).
#
# The app and the engine (com.pdfpaper.engine) are plain Kotlin using no
# reflection, so no class or member keeps are required: R8 follows the direct
# calls from the manifest entry point. Compose, Activity, Lifecycle and
# Navigation ship their own consumer rules via their AARs and need no help here.
#
# Keep source/line attributes so release stack traces stay actionable in crash
# reports (the class names are obfuscated, the line numbers are not).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# If reflection is ever introduced (e.g. a plugin/serialization layer), add the
# matching keep rules here rather than disabling shrinking globally.

# The engine ships a JDK-ImageIO *fallback* raster decoder
# (`edit.EditImages.defaultDecoder`, shared with the JVM tools). Android never
# reaches it: every app call passes `AndroidRasterDecoder`. R8 still sees the
# reference from the reachable `defaultDecoder()` method, so the JDK-only
# classes it names must be allowed to be absent. A follow-up removes the
# ImageIO fallback from :engine (see AGENTS.md, known issues).
-dontwarn javax.imageio.**
-dontwarn java.awt.image.**
