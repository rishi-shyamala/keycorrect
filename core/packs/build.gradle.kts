plugins { id("keycorrect.android.library") }

android { namespace = "dev.keycorrect.core.packs" }

// The swipe pack's loader hands out :core:swipenet's encoder.
dependencies { api(project(":core:swipenet")) }
