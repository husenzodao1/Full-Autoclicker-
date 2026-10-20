# The manifest-declared service/activity and the layout-referenced TestPadView are kept by the
# rules aapt2 generates. Nothing in this app uses reflection.

# Keep class names in crash stack traces readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
