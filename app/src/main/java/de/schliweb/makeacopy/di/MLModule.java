package de.schliweb.makeacopy.di;

import android.content.Context;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import de.schliweb.makeacopy.ml.corners.DocQuadDetector;
import de.schliweb.makeacopy.ml.docquad.DocQuadOrtRunner;
import javax.inject.Singleton;

/** Hilt module that provides ML-related dependencies as singletons. */
@Module
@InstallIn(SingletonComponent.class)
public class MLModule {

  @Provides
  @Singleton
  DocQuadOrtRunner provideDocQuadOrtRunner(@ApplicationContext Context context) {
    try {
      return DocQuadOrtRunner.getInstance(context, DocQuadDetector.DEFAULT_MODEL_ASSET_PATH);
    } catch (Exception e) {
      throw new RuntimeException("DocQuad model load failed", e);
    }
  }
}
