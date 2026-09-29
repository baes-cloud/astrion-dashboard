# R8 rules for the release build.
#
# The app uses kotlinx.serialization only through the untyped JsonElement API
# (no @Serializable classes) and registers cards as plain instances, so nothing
# is looked up by reflection and the libraries' bundled consumer rules suffice.

# Keep line numbers so stack traces from the remote stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
