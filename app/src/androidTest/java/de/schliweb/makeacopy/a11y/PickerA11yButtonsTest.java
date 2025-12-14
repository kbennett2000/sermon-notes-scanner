package de.schliweb.makeacopy.a11y;

import androidx.fragment.app.testing.FragmentScenario;
import androidx.lifecycle.Lifecycle;
import androidx.test.espresso.Espresso;
import androidx.test.espresso.assertion.ViewAssertions;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.ui.export.picker.CompletedScansPickerFragment;
import de.schliweb.makeacopy.testutil.ViewAssertionsEx;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PickerA11yButtonsTest {

    @Test
    public void select_all_none_haveContentDescription_andMinTouchTarget() {
        FragmentScenario.launchInContainer(
                CompletedScansPickerFragment.class,
                null,
                R.style.Theme_MakeACopy,
                Lifecycle.State.RESUMED
        );

        int[] ids = new int[] { R.id.button_select_all, R.id.button_select_none };
        for (int id : ids) {
            Espresso.onView(ViewMatchers.withId(id))
                    .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
                    .check(ViewAssertionsEx.minTouchTargetAndContentDescription(48));
        }
    }
}
