package de.schliweb.makeacopy.ui.export;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ExportPrefsHelperInstrumentedTest {

  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    // Clear preferences before each test
    SharedPreferences prefs = ExportPrefsHelper.getPrefs(context);
    prefs.edit().clear().commit();
  }

  @Test
  public void testLastImportUriPersistence() {
    String testUri = "content://com.android.providers.media.documents/document/image%3A123";

    // Initially null
    assertNull(ExportPrefsHelper.getLastImportUri(context));

    // Set and retrieve
    ExportPrefsHelper.setLastImportUri(context, testUri);
    assertEquals(testUri, ExportPrefsHelper.getLastImportUri(context));
  }
}
