package com.bitchat.android.ui.privacy

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View

/** Disable system password-provider prompts within panchat, without changing device settings. */
fun applyPanchatAutofillPolicy(activity: Activity) {
    activity.window.decorView.importantForAutofill =
        View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
}

fun Application.disablePanchatAutofill() {
    registerActivityLifecycleCallbacks(
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) =
                applyPanchatAutofillPolicy(activity)

            override fun onActivityResumed(activity: Activity) =
                applyPanchatAutofillPolicy(activity)

            override fun onActivityStarted(activity: Activity) {}

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivityStopped(activity: Activity) {}

            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {}
        }
    )
}
