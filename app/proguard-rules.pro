# The math and units engines are pure Kotlin with no reflection, so R8 needs no help to
# keep them correct. Rules are deliberately absent rather than defensively broad: a
# blanket -keep would hide a real shrinking bug behind a larger APK.
