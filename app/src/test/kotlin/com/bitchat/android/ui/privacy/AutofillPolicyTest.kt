package com.bitchat.android.ui.privacy

import android.app.Activity
import android.app.Application
import android.view.View
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class AutofillPolicyTest {
    @Test
    fun `whole window excludes new password fields from autofill`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        applyPanchatAutofillPolicy(activity)
        activity.setContentView(EditText(activity))
        assertEquals(
            View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
            activity.window.decorView.importantForAutofill,
        )
        controller.pause().stop().destroy()
    }
}
